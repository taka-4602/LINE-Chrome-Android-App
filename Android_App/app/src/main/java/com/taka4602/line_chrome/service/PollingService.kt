package com.taka4602.line_chrome.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import com.taka4602.line_chrome.data.LineRepository
import com.taka4602.line_chrome.data.PollingInterval
import com.taka4602.line_chrome.line.ChatSummary
import com.taka4602.line_chrome.line.LineClient
import com.taka4602.line_chrome.line.LineServiceError
import com.taka4602.line_chrome.line.Message
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Holds the `fetchOps` long-poll open for as long as the user is signed in.
 *
 * This has to be a foreground service: a long-lived socket is exactly what
 * Android kills in the background, and there is no push channel to fall back on
 * — LINE's own delivery goes through its app, not ours.
 *
 * Note the `dataSync` service type comes with a daily runtime budget on
 * Android 14+, so the connection can be stopped by the system after several
 * hours and will need the app to be reopened.
 */
class PollingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null
    private lateinit var notifier: Notifier

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifier = Notifier(this)
        LineRepository.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // startForeground must happen either way: a service started with
        // startForegroundService and then stopped without it is killed with
        // ForegroundServiceDidNotStartInTimeException.
        startInForeground("Connecting…")
        if (!LineRepository.sessionStore.polling.deliveryEnabled) {
            Log.i(TAG, "every delivery path is off; nothing to run")
            LineRepository.setConnection(
                LineRepository.Connection.Degraded(OFF_REASON)
            )
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (pollJob == null) {
            pollJob = scope.launch { pollLoop() }
            scope.launch { syncLoop() }
            // Opening a chat means its notification has served its purpose.
            scope.launch {
                LineRepository.openChat.collect { mid -> mid?.let(notifier::clearChat) }
            }
        }
        return START_STICKY
    }

    /**
     * Delivery that does not depend on the long-poll working.
     *
     * A long-poll with nothing to say and one that is silently broken look
     * identical from here — both just sit there — so this stops trying to tell
     * them apart and simply checks on a timer regardless.  It costs one request
     * per cycle: `getLastOpRevision` says whether the account moved at all, and
     * the expensive per-chat sweep only runs when it has.
     *
     * It stands down while ops are visibly arriving, so a healthy long-poll
     * pays almost nothing for this.
     */
    private suspend fun syncLoop() {
        while (scope.isActive) {
            val pace = interval(PollingInterval.SafetyNetCheck)
            if (pace == 0L) {
                // Switched off.  Idle rather than return, so turning it back on
                // does not need the service restarting.
                delay(IDLE_TICK_MS)
                continue
            }
            delay(pace)
            if (LineRepository.client == null) continue
            // A zero quiet period means never skip, which this gets for free:
            // no elapsed time is ever below it.
            val quiet = interval(PollingInterval.SafetyNetQuiet)
            if (System.currentTimeMillis() - LineRepository.lastOpAt < quiet) continue

            try {
                notify(LineRepository.refreshViaTalkService())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "sync sweep failed: ${e.message}")
            }
        }
    }

    private fun startInForeground(text: String) {
        val notification = Notifier.serviceNotification(this, text)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, Notifier.SERVICE_NOTIFICATION_ID, notification, type)
    }

    private suspend fun pollLoop() {
        while (scope.isActive) {
            val client = LineRepository.client
            if (client == null) {
                // Signed out, or the session has not been restored yet.
                delay(2_000)
                continue
            }

            // Turned off, so there is nothing to probe and nothing to fail.
            // Hand to the fallback, which is the only other thing this loop
            // drives; with that off too the safety net is carrying delivery on
            // its own and this loop has no work at all.
            val polling = LineRepository.sessionStore.polling
            if (!polling.isOn(PollingInterval.LongPollFloor)) {
                if (polling.isOn(PollingInterval.FallbackCheck)) {
                    fallbackSession("Long-poll turned off in Settings")
                } else {
                    idle("Long-poll and fallback turned off in Settings")
                }
                continue
            }

            val failure = try {
                liveSession(client)
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            if (!scope.isActive) return
            if (failure == null) continue

            // A dead token looks like a poll failure but is not one — get the
            // session back and resume rather than dropping to the fallback,
            // which would fail exactly the same way.
            if (failure is LineServiceError && LineRepository.recoverSession()) {
                Log.i(TAG, "session recovered; resuming poll")
                client.resetPollRoute()
                continue
            }

            // The per-candidate probe results say far more than the last
            // exception does — except when the route was fine and we walked away
            // from it on purpose, where they would be misleading noise.
            val detail = if (failure is ShortPollDisabled) {
                failure.message.orEmpty()
            } else {
                client.pollDiagnostics.ifEmpty {
                    failure.message?.take(160) ?: failure::class.java.simpleName
                }
            }
            Log.w(TAG, "long-poll unavailable: $detail", failure)
            fallbackSession(detail)

            // Only re-probe on the way back round.  Retrying the whole
            // candidate list between every refresh is what starved the
            // fallback before: one hung candidate costs minutes.
            client.resetPollRoute()
        }
    }

    /** Long-poll until it stops working, then throw. */
    private suspend fun liveSession(client: LineClient) {
        withContext(Dispatchers.IO) { client.seedRevision() }
        var instantEmpties = 0
        var shortPolling = false
        publishLive(client, shortPolling = false)

        while (scope.isActive) {
            val startedAt = System.currentTimeMillis()
            val ops = withContext(Dispatchers.IO) { client.fetchOps() }
            scope.ensureActive()
            val elapsed = System.currentTimeMillis() - startedAt

            if (ops.isNotEmpty()) {
                instantEmpties = 0
                notify(LineRepository.applyOps(ops))
            } else if (elapsed < INSTANT_MS) {
                // Not every poll endpoint holds the request open.  /P3
                // fetchOperations answers at once with an empty list when there
                // is nothing new — it works, it simply is not a long-poll.
                // Space the calls out rather than treating it as broken; LINE
                // bans accounts that poll hard.
                if (++instantEmpties >= SHORT_POLL_AFTER && !shortPolling) {
                    // Short-polling turned off means the user would rather have
                    // the fallback than a route that has to be asked repeatedly.
                    if (!LineRepository.sessionStore.polling.isOn(PollingInterval.ShortPoll)) {
                        throw ShortPollDisabled(client.activePollRoute?.toString())
                    }
                    shortPolling = true
                    Log.i(TAG, "${client.activePollRoute} answers immediately; short-polling")
                    publishLive(client, shortPolling = true)
                }
            } else {
                instantEmpties = 0
            }

            val pace = interval(
                if (shortPolling) PollingInterval.ShortPoll else PollingInterval.LongPollFloor
            )
            val remaining = pace - elapsed
            if (remaining > 0) delay(remaining)
        }
    }

    /** The route only resolves on the first poll, so this is published late. */
    private fun publishLive(client: LineClient, shortPolling: Boolean) {
        LineRepository.setConnection(
            LineRepository.Connection.Live(client.activePollRoute?.toString(), shortPolling)
        )
        startInForeground(if (shortPolling) "Connected (checking regularly)" else "Connected")
    }

    /**
     * Keep messages flowing over the plain TalkService calls for a while, then
     * return so the long-poll gets another chance.
     */
    private suspend fun fallbackSession(detail: String) {
        LineRepository.setConnection(LineRepository.Connection.Degraded(detail))
        val session = interval(PollingInterval.FallbackSession)

        if (interval(PollingInterval.FallbackCheck) == 0L) {
            // Nothing to run here, but returning would put the caller straight
            // back into probing the long-poll — which is expensive and, if it
            // fails fast, a hot loop.  Sit the session out instead.
            startInForeground("Fallback is off")
            delay(maxOf(session, PROBE_BACKOFF_MS))
            return
        }

        startInForeground("Checking every ${interval(PollingInterval.FallbackCheck) / 1000}s")

        // At least one check before handing back, so a zero-length session means
        // "check once, then re-probe" rather than "do nothing, then re-probe".
        val until = System.currentTimeMillis() + session
        do {
            try {
                notify(LineRepository.refreshViaTalkService())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "fallback refresh failed: ${e.message}")
            }
            val pace = interval(PollingInterval.FallbackCheck)
            if (pace == 0L) return // switched off mid-session
            delay(pace)
        } while (scope.isActive && System.currentTimeMillis() < until)
    }

    /**
     * Nothing for this loop to do.  Ticks rather than returns, so switching a
     * path back on in Settings is picked up without restarting the service.
     */
    private suspend fun idle(reason: String) {
        val state = LineRepository.Connection.Degraded(reason)
        // Compared by value, so this only redraws when the reason actually
        // changes rather than on every tick.
        if (LineRepository.connection.value != state) {
            LineRepository.setConnection(state)
            startInForeground(reason)
        }
        delay(IDLE_TICK_MS)
    }

    /** [PollingInterval.ShortPoll] is off and the route needs short-polling. */
    private class ShortPollDisabled(route: String?) :
        Exception("$route answers immediately and short-polling is off")

    private fun notify(messages: List<Pair<ChatSummary, Message>>) {
        if (messages.isEmpty() || !LineRepository.sessionStore.notificationsEnabled) return
        for ((chat, msg) in messages) {
            notifier.notifyMessage(chat, msg, LineRepository.nameOf(msg.sender))
        }
    }

    /**
     * The platform has decided this service has run long enough.
     *
     * specialUse is not supposed to carry a runtime budget, so reaching here at
     * all means the platform reads it differently than documented — but leaving
     * without answering is not an option either way.  Miss the few seconds this
     * allows and the process is killed outright with RemoteServiceException,
     * which loses the connection just the same and takes the app down with it.
     */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(TAG, "foreground service timed out (type $fgsType); stopping before we are killed")
        LineRepository.setConnection(
            LineRepository.Connection.Degraded("Background time limit reached — reopen the app")
        )
        stopSelf(startId)
    }

    override fun onDestroy() {
        pollJob = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PollingService"

        /** Shown wherever the service is not running because the user said so. */
        const val OFF_REASON = "Polling turned off in Settings"

        /** Under this, a poll cannot be said to have blocked on anything. */
        private const val INSTANT_MS = 1_000L

        /** Consecutive instant empty answers before treating this as a short-poll. */
        private const val SHORT_POLL_AFTER = 3

        /** How often a switched-off loop looks to see whether it is back on. */
        private const val IDLE_TICK_MS = 2_000L

        /**
         * Floor on re-probing the long-poll when the fallback is off and there
         * is therefore nothing to pace the outer loop.  Probing runs the whole
         * candidate list, so this must never be zero.
         */
        private const val PROBE_BACKOFF_MS = 30_000L

        /**
         * The cadences below are user-settable — see [PollingInterval] for what
         * each one paces and what it defaults to.  They are read per tick rather
         * than once, so an edit in Settings applies without restarting this
         * service.
         */
        private fun interval(which: PollingInterval): Long =
            LineRepository.sessionStore.polling.millis(which)

        fun start(context: Context) {
            if (!LineRepository.sessionStore.polling.deliveryEnabled) {
                // Starting only to stop again would flash a notification for no
                // reason.  onStartCommand still checks, for the paths that do
                // not come through here.
                LineRepository.setConnection(
                    LineRepository.Connection.Degraded(OFF_REASON)
                )
                return
            }
            val intent = Intent(context, PollingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stop through the framework rather than by delivering ourselves an
         * intent.
         *
         * This clears the started state immediately, so [START_STICKY] does not
         * bring the service back — which it would if the process went away
         * before our own stop intent had been delivered.
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, PollingService::class.java))
        }
    }
}
