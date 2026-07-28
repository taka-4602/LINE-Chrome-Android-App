package com.taka4602.line_chrome.data

import android.content.SharedPreferences

/**
 * Every cadence the poller runs on, as one overridable list.
 *
 * These were constants in [com.taka4602.line_chrome.service.PollingService] and
 * [LineRepository].  The defaults are still those values, and are what the
 * comments there were tuned for — the point of exposing them is that the right
 * trade-off is not the same for everyone: LINE bans accounts that poll hard, so
 * how close to that line to sit is the user's call, not ours.
 *
 * Zero is accepted by all of them and is always the least-work option, but it
 * does not mean the same thing for all of them — see [zeroLabel].  For the four
 * that actually drive requests it turns that loop off; for the two that are
 * waits rather than cadences it means "do not wait", which is the opposite.
 * Anything else is held between [minSeconds] and [maxSeconds] on both read and
 * write, so a value that predates a change to a range cannot leak back out of
 * storage.
 */
enum class PollingInterval(
    val key: String,
    val label: String,
    val description: String,
    val defaultSeconds: Int,
    val minSeconds: Int,
    val maxSeconds: Int,
    val zeroLabel: String,
    val zeroDescription: String,
) {
    LongPollFloor(
        key = "poll_long_floor",
        label = "Long-poll floor",
        description = "Shortest gap between long-poll requests. The poll normally " +
            "blocks until something happens, so this only takes effect when it " +
            "returns straight away — it stops a fast endpoint being hammered.",
        defaultSeconds = 2,
        minSeconds = 1,
        maxSeconds = 60,
        zeroLabel = "Off",
        zeroDescription = "The long-poll does not run at all. Messages arrive only " +
            "through the fallback and the safety net, if those are still on.",
    ),
    ShortPoll(
        key = "poll_short",
        label = "Short-poll interval",
        description = "Used when the poll endpoint answers immediately instead of " +
            "holding the request open. Lower means messages arrive sooner and the " +
            "account is polled harder.",
        defaultSeconds = 5,
        minSeconds = 2,
        maxSeconds = 300,
        zeroLabel = "Off",
        zeroDescription = "Refuse to short-poll. An endpoint that turns out to " +
            "answer immediately is dropped for the fallback instead.",
    ),
    FallbackCheck(
        key = "poll_fallback",
        label = "Fallback check",
        description = "How often to ask TalkService directly while the long-poll " +
            "is unavailable.",
        defaultSeconds = 10,
        minSeconds = 5,
        maxSeconds = 600,
        zeroLabel = "Off",
        zeroDescription = "No fallback. A long-poll that stops working is left to " +
            "the safety net.",
    ),
    FallbackSession(
        key = "poll_fallback_session",
        label = "Fallback session length",
        description = "How long to stay on the fallback before probing the " +
            "long-poll again. Probing costs a run through the whole candidate " +
            "list, so this is deliberately long.",
        defaultSeconds = 10 * 60,
        minSeconds = 60,
        maxSeconds = 60 * 60,
        zeroLabel = "One check",
        zeroDescription = "Do not wait: run a single fallback check, then go " +
            "straight back to probing the long-poll.",
    ),
    SafetyNetCheck(
        key = "poll_sync",
        label = "Safety-net check",
        description = "A long-poll with nothing to say and one that is silently " +
            "broken look identical, so this checks on a timer regardless. One " +
            "request per tick while the account is idle.",
        defaultSeconds = 25,
        minSeconds = 10,
        maxSeconds = 600,
        zeroLabel = "Off",
        zeroDescription = "No safety net. A long-poll that fails silently is not " +
            "noticed until you open the app.",
    ),
    SafetyNetQuiet(
        key = "poll_sync_quiet",
        label = "Safety-net quiet period",
        description = "Skip the safety-net check when the long-poll delivered " +
            "something this recently. Raising it means the net runs more often.",
        defaultSeconds = 60,
        minSeconds = 10,
        maxSeconds = 60 * 60,
        zeroLabel = "Never skip",
        zeroDescription = "Do not wait: run the safety-net check every time, even " +
            "while the long-poll is visibly delivering.",
    ),

    /**
     * Paces `LineRepository.watchOpenChat`.  "Open" here means the chat the user
     * currently has on screen — this is unrelated to LINE's OpenChat feature,
     * which this client does not implement.
     */
    OpenChatRefresh(
        key = "poll_open_chat",
        label = "On-screen chat refresh",
        description = "Extra refresh for the conversation you are currently " +
            "reading, on top of the poll. Costs one request per tick, and stops " +
            "when you leave the chat or background the app.",
        defaultSeconds = 3,
        minSeconds = 1,
        maxSeconds = 120,
        zeroLabel = "Off",
        zeroDescription = "No extra refresh. The chat on screen updates no faster " +
            "than the poll delivers.",
    );

    /** Zero is always legal and always means [zeroLabel]; everything else is bounded. */
    fun clamp(seconds: Int) = if (seconds <= 0) 0 else seconds.coerceIn(minSeconds, maxSeconds)

    companion object {
        /**
         * The three that actually bring messages in.  With all of them off the
         * foreground service has nothing left to do, so it is stopped rather
         * than left sitting in the notification shade doing nothing.
         */
        val deliveryPaths = listOf(LongPollFloor, FallbackCheck, SafetyNetCheck)
    }
}

/**
 * The stored overrides, on the same SharedPreferences file as the rest of
 * [SessionStore].
 *
 * Reads are deliberately not cached: the loops that use these read them once
 * per tick, so an edit in Settings takes effect on the next one without the
 * service needing to be restarted.
 */
class PollingSettings(private val prefs: SharedPreferences) {

    fun seconds(interval: PollingInterval): Int =
        interval.clamp(prefs.getInt(interval.key, interval.defaultSeconds))

    fun millis(interval: PollingInterval): Long = seconds(interval) * 1_000L

    /** False when this one is switched off — see [PollingInterval.zeroLabel]. */
    fun isOn(interval: PollingInterval): Boolean = seconds(interval) > 0

    fun set(interval: PollingInterval, seconds: Int) =
        prefs.edit().putInt(interval.key, interval.clamp(seconds)).apply()

    fun isDefault(interval: PollingInterval): Boolean =
        seconds(interval) == interval.defaultSeconds

    /** Back to the values the comments in the poller were written for. */
    fun reset(interval: PollingInterval) = prefs.edit().remove(interval.key).apply()

    fun resetAll() = prefs.edit()
        .apply { PollingInterval.entries.forEach { remove(it.key) } }
        .apply()

    val anyOverridden: Boolean get() = PollingInterval.entries.any { !isDefault(it) }

    /** Whether any path that brings messages in is still switched on. */
    val deliveryEnabled: Boolean get() = PollingInterval.deliveryPaths.any { isOn(it) }
}
