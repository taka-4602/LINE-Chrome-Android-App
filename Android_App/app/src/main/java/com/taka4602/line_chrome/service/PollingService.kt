package com.taka4602.line_chrome.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.taka4602.line_chrome.data.LineRepository
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
        startInForeground("Connecting…")
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
            delay(SYNC_INTERVAL_MS)
            if (LineRepository.client == null) continue
            if (System.currentTimeMillis() - LineRepository.lastOpAt < SYNC_QUIET_MS) continue

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
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
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
            // exception does.
            val detail = client.pollDiagnostics.ifEmpty {
                failure.message?.take(160) ?: failure::class.java.simpleName
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
                    shortPolling = true
                    Log.i(TAG, "${client.activePollRoute} answers immediately; short-polling")
                    publishLive(client, shortPolling = true)
                }
            } else {
                instantEmpties = 0
            }

            val interval = if (shortPolling) SHORT_POLL_INTERVAL_MS else MIN_POLL_INTERVAL_MS
            val remaining = interval - elapsed
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
        startInForeground("Checking every ${FALLBACK_INTERVAL_MS / 1000}s")

        val until = System.currentTimeMillis() + FALLBACK_SESSION_MS
        while (scope.isActive && System.currentTimeMillis() < until) {
            try {
                notify(LineRepository.refreshViaTalkService())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "fallback refresh failed: ${e.message}")
            }
            delay(FALLBACK_INTERVAL_MS)
        }
    }

    private fun notify(messages: List<Pair<ChatSummary, Message>>) {
        if (messages.isEmpty() || !LineRepository.sessionStore.notificationsEnabled) return
        for ((chat, msg) in messages) {
            notifier.notifyMessage(chat, msg, LineRepository.nameOf(msg.sender))
        }
    }

    override fun onDestroy() {
        pollJob = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PollingService"
        /** Floor between polls, so a long-poll that returns instantly cannot spin. */
        private const val MIN_POLL_INTERVAL_MS = 2_000L

        /** Under this, a poll cannot be said to have blocked on anything. */
        private const val INSTANT_MS = 1_000L

        /** Consecutive instant empty answers before treating this as a short-poll. */
        private const val SHORT_POLL_AFTER = 3

        /**
         * Pacing for an endpoint that answers immediately.  Still prompt enough
         * to feel live, slow enough not to look like a hammering client.
         */
        private const val SHORT_POLL_INTERVAL_MS = 5_000L

        /** How often the fallback asks TalkService for anything new. */
        private const val FALLBACK_INTERVAL_MS = 10_000L

        /** How long to stay on the fallback before re-probing the long-poll. */
        private const val FALLBACK_SESSION_MS = 10 * 60_000L

        /**
         * Safety-net cadence.  One request per tick when the account is idle,
         * so this is cheap enough to leave running permanently — LINE bans
         * accounts that poll hard, which rules out anything more eager.
         */
        private const val SYNC_INTERVAL_MS = 25_000L

        /** Skip the safety net while the long-poll is visibly delivering. */
        private const val SYNC_QUIET_MS = 60_000L

        fun start(context: Context) {
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
