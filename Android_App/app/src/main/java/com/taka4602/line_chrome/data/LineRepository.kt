package com.taka4602.line_chrome.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.taka4602.line_chrome.line.Chat
import com.taka4602.line_chrome.line.ChatSummary
import com.taka4602.line_chrome.line.ChatType
import com.taka4602.line_chrome.line.Contact
import com.taka4602.line_chrome.line.ContentType
import com.taka4602.line_chrome.line.Crypto
import com.taka4602.line_chrome.line.LineClient
import com.taka4602.line_chrome.line.LineServiceError
import com.taka4602.line_chrome.line.LineTransportError
import com.taka4602.line_chrome.line.Message
import com.taka4602.line_chrome.line.OpType
import com.taka4602.line_chrome.line.Operation
import com.taka4602.line_chrome.line.Profile
import com.taka4602.line_chrome.line.isSessionDead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Single owner of the [LineClient] and of every piece of state the UI renders.
 *
 * A process-wide singleton on purpose: the polling foreground service and the
 * Activity have to observe the same message store, and a session is expensive
 * enough (a PIN confirmation, in the worst case) that it must survive an
 * Activity restart.
 */
object LineRepository {

    private const val TAG = "LineRepository"

    /** One request per chat, so the fallback sweep is paced well apart. */
    private const val FALLBACK_SWEEP_INTERVAL_MS = 60_000L

    /** Treat a recovery this recent as still good, rather than logging in again. */
    private const val RECOVERY_GRACE_MS = 30_000L

    /**
     * Pacing for the forced re-login below.  It doubles up to the ceiling, so a
     * network that stays down costs a handful of attempts an hour rather than a
     * login storm — LINE flags accounts for the latter.
     */
    private const val RELOGIN_RETRY_MS = 15_000L
    private const val RELOGIN_RETRY_MAX_MS = 5 * 60_000L

    /** Uploads are buffered whole in memory, so cap what we will attempt. */
    private const val MAX_IMAGE_BYTES = 25 * 1_000_000
    private const val MAX_VIDEO_BYTES = 60 * 1_000_000

    /** How long to keep chasing a message the upload should have created. */
    private const val UPLOAD_SYNC_ATTEMPTS = 8
    private const val UPLOAD_SYNC_DELAY_MS = 1_000L

    /** A just-created object has no thumbnail yet, so retry briefly. */
    private const val RECENT_MEDIA_MS = 120_000L
    private const val MEDIA_RETRY_DELAY_MS = 3_000L
    private const val MEDIA_RETRY_LIMIT = 4

    /**
     * Refresh cadence for the conversation currently on screen — settable, so
     * this is read per tick rather than held.  See [PollingInterval].
     */
    private val openChatIntervalMs: Long
        get() = session.polling.millis(PollingInterval.OpenChatRefresh)
    private const val OPEN_CHAT_COUNT = 30

    /** How often the refresh above looks to see whether it has been turned on. */
    private const val OPEN_CHAT_IDLE_TICK_MS = 2_000L

    sealed interface AuthState {
        /** Before the stored token has been checked — the app shows a splash. */
        data object Starting : AuthState
        data object LoggedOut : AuthState
        data object Connecting : AuthState
        /** Waiting on the user to approve [pincode] on their primary device. */
        data class PinRequired(val pincode: String) : AuthState
        data class LoggedIn(val profile: Profile) : AuthState
        data class Failed(val message: String) : AuthState
    }

    private lateinit var appContext: Context
    private lateinit var session: SessionStore
    private lateinit var credentials: CredentialStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    var client: LineClient? = null
        private set

    private val _auth = MutableStateFlow<AuthState>(AuthState.Starting)
    val auth: StateFlow<AuthState> = _auth.asStateFlow()

    private val _chats = MutableStateFlow<List<ChatSummary>>(emptyList())
    val chats: StateFlow<List<ChatSummary>> = _chats.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>>(emptyList())
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    private val _groups = MutableStateFlow<List<Chat>>(emptyList())
    val groups: StateFlow<List<Chat>> = _groups.asStateFlow()

    /** chatMid -> messages, oldest first. */
    private val _messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Message>>> = _messages.asStateFlow()

    /**
     * chatMid -> (readerMid -> the newest message id that reader has read).
     *
     * One entry per reader rather than per message, because a read receipt is
     * cumulative: it says "I have read up to here", which settles every earlier
     * message at the same time.  Held apart from [_messages] because it arrives
     * long after the message is stored and, in a group, keeps changing as more
     * people catch up.
     *
     * Not persisted, but not lost either: [loadReadReceipts] refills it from
     * the server when a chat is opened, so a restart costs nothing visible.
     */
    private val _readReceipts = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val readReceipts: StateFlow<Map<String, Map<String, String>>> = _readReceipts.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    /** How message delivery is currently getting through, if at all. */
    sealed interface Connection {
        data object Idle : Connection
        /**
         * The poll is working.  [shortPolling] means the endpoint answers
         * immediately rather than holding the request open, so events arrive
         * within a few seconds rather than instantly.
         */
        data class Live(val route: String?, val shortPolling: Boolean = false) : Connection
        /** fetchOps is failing; falling back to polling the TalkService calls. */
        data class Degraded(val reason: String) : Connection
    }

    private val _connection = MutableStateFlow<Connection>(Connection.Idle)
    val connection: StateFlow<Connection> = _connection.asStateFlow()

    fun setConnection(state: Connection) {
        _connection.value = state
    }

    /** Everything the profile page renders, for a person or a group. */
    data class ProfileInfo(
        val mid: String,
        val name: String,
        val statusMessage: String?,
        val picturePath: String?,
        val isGroup: Boolean,
        val isSelf: Boolean,
        /** The nickname you set for them — LINE shows this instead of their own name. */
        val nickname: String?,
        /** The name they chose for themselves. */
        val realName: String?,
    )

    private val _profiles = MutableStateFlow<Map<String, ProfileInfo>>(emptyMap())
    val profiles: StateFlow<Map<String, ProfileInfo>> = _profiles.asStateFlow()

    private val _openChat = MutableStateFlow<String?>(null)

    /** Observed by the poller so it can retire a notification the user just read. */
    val openChat: StateFlow<String?> = _openChat.asStateFlow()

    /** Set by the UI so the poller knows not to raise a notification for it. */
    var openChatMid: String?
        get() = _openChat.value
        set(value) { _openChat.value = value }

    private val unread = mutableMapOf<String, Int>()
    private val displayNames = mutableMapOf<String, String>()
    private val pictures = mutableMapOf<String, String?>()

    val sessionStore: SessionStore get() = session

    fun init(context: Context) {
        if (::appContext.isInitialized) return
        appContext = context.applicationContext
        session = SessionStore(appContext)
        credentials = CredentialStore(appContext)

        val token = session.accessToken
        if (token == null) {
            _auth.value = AuthState.LoggedOut
        } else {
            scope.launch { restore(token, session.refreshToken) }
        }
        scope.launch { watchOpenChat() }
    }

    /**
     * Keep the conversation on screen fresher than the global poll manages.
     *
     * The op poll covers every chat at once and is paced for that; the one the
     * user is actually reading is worth an extra request every few seconds.
     * [openChatMid] is cleared when the app is backgrounded, so this stops on
     * its own rather than polling for a screen nobody is looking at.
     */
    private suspend fun watchOpenChat() {
        openChat.collectLatest { mid ->
            if (mid == null) return@collectLatest
            while (true) {
                val pace = openChatIntervalMs
                if (pace == 0L) {
                    // Switched off.  Tick rather than return, so turning it back
                    // on does not need the chat reopening.
                    delay(OPEN_CHAT_IDLE_TICK_MS)
                    continue
                }
                delay(pace)
                val c = client ?: continue
                runCatching { fetchMessages(c, mid, OPEN_CHAT_COUNT) }
                    .onFailure { Log.d(TAG, "open chat refresh failed: $it") }
            }
        }
    }

    private fun newClient() = LineClient(appContext.filesDir) { access, refresh ->
        session.saveTokens(access, refresh)
    }

    // -- authentication ----------------------------------------------------

    private suspend fun restore(token: String, refresh: String?) {
        _auth.value = AuthState.Connecting
        val c = newClient().apply { useToken(token, refresh) }
        try {
            val profile = c.verifyLogin()
            client = c
            _auth.value = AuthState.LoggedIn(profile)
            refreshChats()
        } catch (e: Exception) {
            // The stored token is stale, which is routine — LINE expires it
            // every few days.  Hand off to the same recovery the rest of the
            // app uses: refresh token first, stored credentials after.
            Log.i(TAG, "saved session rejected ($e); recovering")
            client = c
            if (recoverSession()) {
                refreshChats()
            } else if (!credentials.hasCredentials) {
                session.clear()
                client = null
                _auth.value = AuthState.LoggedOut
            }
        }
    }

    /** A fresh code each attempt, so a stale prompt cannot be approved by accident. */
    private fun randomPin() = "%06d".format(Crypto.random.nextInt(1_000_000))

    fun loginWithEmail(email: String, password: String, remember: Boolean = true) {
        scope.launch {
            _auth.value = AuthState.Connecting
            val c = newClient()
            try {
                c.loginWithEmail(email, password, randomPin()) {
                    _auth.value = AuthState.PinRequired(it)
                }
                session.email = email
                val profile = c.verifyLogin()
                client = c
                if (remember) credentials.save(email, password) else credentials.clear()
                // A fresh manual sign-in supersedes any cached recovery refusal.
                lastRecoveryAt = 0L
                _auth.value = AuthState.LoggedIn(profile)
                refreshChats()
            } catch (e: Exception) {
                Log.w(TAG, "login failed", e)
                _auth.value = AuthState.Failed(e.messageForUser())
            }
        }
    }

    val savedEmail: String? get() = credentials.email

    /** A password is on file, so unattended re-login is possible at all. */
    val canReloginUnattended: Boolean get() = credentials.hasCredentials

    /** Whether that password may actually be used.  See [recoverSession]. */
    var autoReloginEnabled: Boolean
        get() = session.autoReloginEnabled
        set(value) {
            session.autoReloginEnabled = value
            // Switching it on should take effect now, not after the grace
            // window on the refusal that was just cached.
            if (value) lastRecoveryAt = 0L
        }

    fun forgetCredentials() = credentials.clear()

    // -- session recovery --------------------------------------------------

    private val sessionMutex = Mutex()
    private var lastRecoveryAt = 0L
    private var lastRecoveryOk = false
    private var reloginJob: Job? = null

    /**
     * Whether a failed re-login is worth repeating.
     *
     * A rejected password is the user's problem and no amount of retrying will
     * fix it.  Everything else — no network, a timeout, LINE having a bad minute
     * — is temporary by nature.
     */
    private val Exception.needsTheUser: Boolean
        get() = this is LineServiceError && code == 1

    /**
     * Put a dead session back together without involving the user.
     *
     * LINE expires the access token every few days. The refresh token renews it
     * cheaply and is tried first; when that is rejected too, a stored email and
     * password can log in outright — normally without a PIN, because the device
     * certificate from the first login is still on disk.
     *
     * Serialised, because several callers tend to notice the same dead session
     * at once and logging in four times over would be a good way to get the
     * account flagged.
     */
    suspend fun recoverSession(): Boolean = sessionMutex.withLock {
        // Every outcome is remembered, failures included.  Recording only the
        // successes would leave a wrong password re-attempting loginV2 on every
        // call from every loop — a login storm, and a good way to get flagged.
        if (System.currentTimeMillis() - lastRecoveryAt < RECOVERY_GRACE_MS) {
            return@withLock lastRecoveryOk
        }

        val current = client
        if (current != null) {
            val refreshed = runCatching {
                withContext(Dispatchers.IO) {
                    current.refreshAccessToken()
                    current.verifyLogin()
                }
            }
            if (refreshed.isSuccess) {
                Log.i(TAG, "access token refreshed")
                return@withLock recorded(true)
            }
            Log.i(TAG, "refresh token rejected: ${refreshed.exceptionOrNull()}")
        }

        if (!session.autoReloginEnabled) {
            Log.i(TAG, "session dead and automatic re-login is switched off")
            _auth.value = AuthState.Failed("Session expired. Please sign in again.")
            return@withLock recorded(false)
        }

        val email = credentials.email
        val password = credentials.password()
        if (email == null || password == null) {
            Log.w(TAG, "session dead and no stored credentials — signing out")
            _auth.value = AuthState.Failed("Session expired. Please sign in again.")
            return@withLock recorded(false)
        }

        var pinAsked = false
        return@withLock try {
            val c = newClient()
            withContext(Dispatchers.IO) {
                c.loginWithEmail(email, password, randomPin()) {
                    // Only reachable if the device certificate is gone, in
                    // which case this genuinely needs the user.
                    pinAsked = true
                    _auth.value = AuthState.PinRequired(it)
                }
                c.verifyLogin()
            }
            client = c
            c.myProfile?.let { _auth.value = AuthState.LoggedIn(it) }
            Log.i(TAG, "re-logged in with stored credentials")
            recorded(true)
        } catch (e: Exception) {
            // Failing to reach LINE is not the same as being logged out of it,
            // and answering the first with Failed strands the app: that stops
            // the poller, and the poller is the only thing that would have
            // retried.  One badly-timed blip during a token refresh would park
            // us on the login screen until a human noticed — which is the exact
            // outcome this setting exists to prevent.
            if (pinAsked || e.needsTheUser) {
                Log.w(TAG, "automatic re-login needs the user", e)
                _auth.value = AuthState.Failed(e.messageForUser())
            } else {
                Log.w(TAG, "automatic re-login failed; retrying in the background", e)
                // Do not tear the session down over this.  Signed in, the UI
                // keeps its screen and its cached messages while the retry runs
                // underneath; only a session that never got established has
                // anything to show for the failure.
                if (_auth.value !is AuthState.LoggedIn) _auth.value = AuthState.Connecting
                startReloginWatchdog()
            }
            recorded(false)
        }
    }

    private fun recorded(success: Boolean): Boolean {
        lastRecoveryAt = System.currentTimeMillis()
        lastRecoveryOk = success
        return success
    }

    /**
     * Keep forcing a re-login for as long as the stored password is still
     * believed good.
     *
     * [recoverSession] only ever runs because some caller wanted a request to
     * succeed; nothing in the app retries a dead session on its own once the
     * poller has been stopped.  This is that missing loop, and it gives up only
     * when it succeeds, when the credentials go away, or when LINE says
     * something only the user can answer.
     */
    private fun startReloginWatchdog() {
        if (reloginJob?.isActive == true) return
        reloginJob = scope.launch {
            var wait = RELOGIN_RETRY_MS
            while (true) {
                delay(wait)
                if (_auth.value is AuthState.LoggedIn) return@launch
                if (!session.autoReloginEnabled || !credentials.hasCredentials) return@launch
                // This *is* the paced retry the grace window exists to enforce,
                // so do not let the cached refusal answer on its behalf.
                lastRecoveryAt = 0L
                if (recoverSession()) return@launch
                // recoverSession decided the user is needed after all.
                if (_auth.value is AuthState.Failed) return@launch
                wait = (wait * 2).coerceAtMost(RELOGIN_RETRY_MAX_MS)
            }
        }
    }

    /**
     * Run a call, and if LINE says the session is gone, rebuild it and retry once.
     */
    private suspend fun <T> authed(block: suspend (LineClient) -> T): T? {
        val c = client ?: return null
        return try {
            block(c)
        } catch (e: LineServiceError) {
            if (!e.isSessionDead) throw e
            Log.i(TAG, "session rejected (code ${e.code}); recovering")
            if (!recoverSession()) null else client?.let { block(it) }
        }
    }

    fun loginWithToken(token: String) {
        scope.launch {
            _auth.value = AuthState.Connecting
            val c = newClient().apply { useToken(token.trim()) }
            try {
                val profile = c.verifyLogin()
                session.saveTokens(c.authToken ?: token.trim(), null)
                client = c
                _auth.value = AuthState.LoggedIn(profile)
                refreshChats()
            } catch (e: Exception) {
                _auth.value = AuthState.Failed(e.messageForUser())
            }
        }
    }

    /** Raise a one-line confirmation in whichever screen is on top. */
    fun showStatus(message: String) {
        _status.value = message
    }

    fun dismissError() {
        if (_auth.value is AuthState.Failed) _auth.value = AuthState.LoggedOut
        _status.value = null
    }

    fun logout() {
        scope.launch {
            // Before anything else: a retry in flight would sign us straight
            // back in with credentials this is about to clear.
            reloginJob?.cancel()
            runCatching { client?.logout() }
            client = null
            session.clear()
            // Signing out has to forget the password as well, or the next
            // session error would quietly sign straight back in.
            credentials.clear()
            unread.clear(); displayNames.clear(); pictures.clear()
            _chats.value = emptyList()
            _contacts.value = emptyList()
            _groups.value = emptyList()
            _messages.value = emptyMap()
            _profiles.value = emptyMap()
            _auth.value = AuthState.LoggedOut
        }
    }

    // -- chat list ---------------------------------------------------------

    /**
     * Rebuild the "Chats" tab.
     *
     * LINE has no call that returns this directly — the official client keeps a
     * local message box synced from fetchOps.  It is assembled here from one
     * `getRecentMessagesV2(count=1)` per chat, issued eight at a time.  Fine for
     * tens of chats, noticeable for hundreds.
     */
    fun refreshChats() {
        if (client == null) return
        scope.launch {
            _syncing.value = true
            try {
                authed { withContext(Dispatchers.IO) { buildSummaries(it) } }
                lastSweep = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "chat refresh failed", e)
                _status.value = e.messageForUser()
            } finally {
                _syncing.value = false
            }
        }
    }

    private var lastSweep = 0L

    /**
     * Refresh unless we just did.
     *
     * Called when the app is resumed and when the Chats tab is selected — the
     * list is otherwise only ever rebuilt by the refresh button, which makes a
     * stalled long-poll look like an app that has simply stopped working.
     */
    fun refreshChatsIfStale(maxAgeMs: Long = 20_000L) {
        if (System.currentTimeMillis() - lastSweep < maxAgeMs) return
        refreshChats()
    }

    /**
     * @return the incoming messages this sweep revealed, in the same shape as
     *   [applyOps], so a caller polling this way can notify identically.
     */
    private suspend fun buildSummaries(
        c: LineClient,
    ): List<Pair<ChatSummary, Message>> = coroutineScopeIO {
        val names = LinkedHashMap<String, String>()
        val types = LinkedHashMap<String, Int?>()
        val pics = LinkedHashMap<String, String?>()

        val groupList = c.getChats()
        _groups.value = groupList
        for (chat in groupList) {
            names[chat.chatMid] = chat.chatName ?: "(no name)"
            types[chat.chatMid] = chat.chatType
            pics[chat.chatMid] = chat.picturePath
        }

        val contactList = c.getContacts(c.getAllContactIds())
        _contacts.value = contactList.sortedBy { it.name.lowercase() }
        for (contact in contactList) {
            names.putIfAbsentCompat(contact.mid, contact.name)
            types.putIfAbsentCompat(contact.mid, ChatType.PEER)
            pics.putIfAbsentCompat(contact.mid, contact.picturePath)
        }

        displayNames.putAll(names)
        pictures.putAll(pics)

        val gate = Semaphore(8)
        val latest = names.keys.map { mid ->
            async {
                mid to runCatching { gate.withPermit { c.getRecentMessages(mid, 1) } }
                    .getOrElse {
                        if (it is LineServiceError) Log.d(TAG, "skipping $mid: $it")
                        emptyList()
                    }
                    .lastOrNull()
            }
        }.awaitAll().toMap()

        // Group senders are not necessarily in your contact list — someone you
        // never added, or who has since left.  Resolve those in one extra batch
        // so the preview shows a name instead of a raw MID.
        val unknown = latest.values
            .mapNotNull { it?.sender }
            .filter { it != c.mid && it !in displayNames }
            .distinct()
        if (unknown.isNotEmpty()) {
            runCatching { c.getContacts(unknown) }
                .onSuccess { extra -> extra.forEach { displayNames[it.mid] = it.name } }
                .onFailure { Log.d(TAG, "could not resolve ${unknown.size} sender(s): $it") }
        }

        // Read now, not on the way in.  The fan-out above is one request per
        // chat and takes seconds, and the long-poll keeps delivering
        // throughout — a snapshot taken before it would not know about a
        // message that arrived, and was notified for, while it ran, so this
        // sweep would announce it a second time.  That lands on the same
        // notification id, which re-alerts rather than stacks: one notification
        // that made the sound twice, a few seconds apart.
        //
        // The stored copy is the base, not merely a fallback for a restarted
        // process.  The summaries below are rebuilt from groups and contacts
        // alone, so a conversation with someone in neither — a stranger who has
        // just messaged — drops straight back out of _chats, and reading only
        // from there would forget a message we have already notified for and
        // announce it again on the sweep after.  The live list overlays it,
        // being the fresher of the two wherever both have an entry.
        val previousLastIds = session.lastSeenIds + _chats.value
            .mapNotNull { s -> s.lastMessage?.id?.let { s.chatMid to it } }
            .toMap()

        // A chat whose last message id moved since the previous sweep has
        // something we have not seen.  With nothing to compare against every
        // chat looks new at once, which is a fresh sign-in rather than a
        // backlog, so that one case still reports nothing.
        val changed = latest.entries.mapNotNull { (mid, msg) ->
            if (msg == null || previousLastIds.isEmpty()) return@mapNotNull null
            // An absent entry is a conversation that did not exist last time —
            // a first message from someone new, or a group we have just been
            // added to.  Skipping those was silently swallowing exactly the
            // messages most worth hearing about.
            if (previousLastIds[mid] == msg.id) return@mapNotNull null
            if (msg.sender == c.mid || mid == openChatMid) return@mapNotNull null
            mid to msg
        }
        changed.forEach { (mid, _) -> unread[mid] = (unread[mid] ?: 0) + 1 }

        val summaries = names.keys
            .mapNotNull { mid ->
                val msg = latest[mid] ?: return@mapNotNull null
                ChatSummary(
                    chatMid = mid,
                    chatType = types[mid],
                    name = names[mid] ?: mid,
                    picturePath = pics[mid],
                    lastMessage = msg,
                    senderName = msg.sender?.let { senderLabel(it, c.mid) },
                    unreadCount = unread[mid] ?: 0,
                )
            }
            .sortedByDescending { it.timestamp }
        _chats.value = summaries
        rememberLastSeen()

        val byMid = summaries.associateBy { it.chatMid }
        return@coroutineScopeIO changed.mapNotNull { (mid, msg) -> byMid[mid]?.let { it to msg } }
    }

    /**
     * Snapshot what has been seen, so a restarted process resumes from here
     * rather than from nothing.
     *
     * Worth doing on every batch and not just on a sweep: dying right after a
     * long-poll notification would otherwise leave the stored ids behind the
     * ones already notified, and the next sweep would report them a second time.
     */
    private fun rememberLastSeen() {
        // Merged, for the same reason the read above is: a sweep drops chats it
        // cannot attribute to a group or a contact, and replacing wholesale
        // would throw away what was seen in them.
        session.lastSeenIds = session.lastSeenIds + _chats.value
            .mapNotNull { s -> s.lastMessage?.id?.let { s.chatMid to it } }
            .toMap()
    }

    private fun senderLabel(mid: String, selfMid: String): String =
        if (mid == selfMid) "You" else displayNames[mid] ?: mid

    fun nameOf(mid: String?): String = mid?.let { displayNames[it] ?: it } ?: ""

    fun pictureOf(mid: String?): String? = mid?.let { pictures[it] }

    // -- profiles ----------------------------------------------------------

    private fun isGroupMid(mid: String) = mid.startsWith("c") || mid.startsWith("r")

    /** Whatever the chat list already knows, so the page has something to draw at once. */
    private fun cachedProfile(mid: String): ProfileInfo? {
        val group = isGroupMid(mid)
        val known = _contacts.value.find { it.mid == mid }
        val name = known?.name ?: displayNames[mid] ?: return null
        return ProfileInfo(
            mid = mid,
            name = name,
            statusMessage = known?.statusMessage,
            picturePath = known?.picturePath ?: pictures[mid],
            isGroup = group,
            isSelf = mid == client?.mid,
            nickname = known?.displayNameOverride,
            realName = known?.displayName,
        )
    }

    /**
     * Load a profile, showing anything cached straight away and refreshing behind it.
     *
     * Groups have no contact record — there is no roster call in the protocol
     * either — so they are assembled from the chat list instead.
     */
    fun loadProfile(mid: String) {
        val c = client ?: return
        cachedProfile(mid)?.let { publish(it) }

        scope.launch {
            try {
                val fresh = when {
                    isGroupMid(mid) -> groupProfile(mid)

                    mid == c.mid -> c.myProfile?.let {
                        ProfileInfo(
                            mid = it.mid,
                            name = it.displayName ?: it.mid,
                            statusMessage = it.statusMessage,
                            picturePath = it.picturePath,
                            isGroup = false,
                            isSelf = true,
                            nickname = null,
                            realName = it.displayName,
                        )
                    }

                    else -> withContext(Dispatchers.IO) { c.getContacts(listOf(mid)) }
                        .firstOrNull()
                        ?.let { contact ->
                            // Keep the shared name and picture caches in step, so
                            // chat rows pick up a rename without a full sweep.
                            displayNames[contact.mid] = contact.name
                            pictures[contact.mid] = contact.picturePath
                            ProfileInfo(
                                mid = contact.mid,
                                name = contact.name,
                                statusMessage = contact.statusMessage,
                                picturePath = contact.picturePath,
                                isGroup = false,
                                isSelf = false,
                                nickname = contact.displayNameOverride,
                                realName = contact.displayName,
                            )
                        }
                }
                // Never leave the page spinning: an unknown MID still deserves
                // a page, even if all we can show is the MID itself.
                if (fresh != null) publish(fresh)
                else if (_profiles.value[mid] == null) publish(placeholder(mid))
            } catch (e: Exception) {
                Log.w(TAG, "profile load failed for $mid", e)
                // A cached profile is already on screen; only complain when
                // there is nothing at all to show.
                if (_profiles.value[mid] == null) {
                    publish(placeholder(mid))
                    _status.value = e.messageForUser()
                }
            }
        }
    }

    private fun groupProfile(mid: String): ProfileInfo? {
        val chat = _groups.value.find { it.chatMid == mid }
        val summary = _chats.value.find { it.chatMid == mid }
        val name = chat?.chatName ?: summary?.name ?: displayNames[mid] ?: return null
        return ProfileInfo(
            mid = mid,
            name = name,
            statusMessage = null,
            picturePath = chat?.picturePath ?: summary?.picturePath,
            isGroup = true,
            isSelf = false,
            nickname = null,
            realName = null,
        )
    }

    private fun placeholder(mid: String) = ProfileInfo(
        mid = mid,
        name = displayNames[mid] ?: mid,
        statusMessage = null,
        picturePath = pictures[mid],
        isGroup = isGroupMid(mid),
        isSelf = mid == client?.mid,
        nickname = null,
        realName = null,
    )

    private fun publish(info: ProfileInfo) {
        if (_profiles.value[info.mid] == info) return
        _profiles.value = _profiles.value + (info.mid to info)
    }

    /**
     * Who has spoken in a conversation.
     *
     * Not a member list — the protocol exposes no roster call — just the
     * senders visible in the messages loaded so far, which is why the UI labels
     * it as such.
     */
    fun participantsOf(chatMid: String): List<String> {
        val self = client?.mid
        return _messages.value[chatMid].orEmpty()
            .mapNotNull { it.sender }
            .filter { it != self }
            .distinct()
    }

    // -- messages ----------------------------------------------------------

    private val _loadingMessages = MutableStateFlow(false)

    /** True only for an explicit load, not the background refresh every few seconds. */
    val loadingMessages: StateFlow<Boolean> = _loadingMessages.asStateFlow()

    fun loadMessages(chatMid: String, count: Int = 50) {
        val c = client ?: return
        // Independent of the messages themselves, so it is not held up by them
        // and does not hold them up: the read label appears a moment after the bubbles do.
        loadReadReceipts(chatMid)
        scope.launch {
            _loadingMessages.value = true
            try {
                fetchMessages(c, chatMid, count)
            } catch (e: Exception) {
                Log.w(TAG, "loadMessages($chatMid) failed", e)
                _status.value = e.messageForUser()
            } finally {
                _loadingMessages.value = false
            }
        }
    }

    /**
     * Reload a conversation on request.
     *
     * Also retries any attachment that previously failed or had no thumbnail,
     * because "reload" plainly means the pictures too — re-reading only the
     * text around a broken image would look like the button did nothing.
     */
    fun reloadConversation(chatMid: String) {
        for (msg in _messages.value[chatMid].orEmpty()) {
            val id = msg.id ?: continue
            for (preview in listOf(true, false)) {
                val state = _media.value[mediaKey(id, preview)]
                if (state is MediaState.Failed || state is MediaState.NoPreview) {
                    mediaRetries.remove(mediaKey(id, preview))
                    retryMedia(msg, preview)
                }
            }
        }
        loadMessages(chatMid)
    }

    private suspend fun fetchMessages(c: LineClient, chatMid: String, count: Int) {
        val fetched = authed { withContext(Dispatchers.IO) { it.getRecentMessages(chatMid, count) } }
            ?: return
        val merged = merge(_messages.value[chatMid].orEmpty(), fetched)
        _messages.value = _messages.value + (chatMid to merged)
        clearUnread(chatMid)
    }

    /**
     * Refresh over the plain TalkService calls, for when fetchOps will not work.
     *
     * fetchOps is the only push-shaped channel LINE offers, so when it fails
     * there is nothing to fall back to except asking again. The `/S4` calls
     * behind the chat list are known-good — this is slower and chattier than a
     * long-poll, but it is the difference between a working inbox and a dead
     * one.
     *
     * The open chat costs one request, so it refreshes every cycle; the whole
     * chat list costs one request per chat, so it sweeps at most once a minute.
     *
     * @return incoming messages worth notifying about.
     */
    suspend fun refreshViaTalkService(): List<Pair<ChatSummary, Message>> {
        val c = client ?: return emptyList()
        _lastCheck.value = System.currentTimeMillis()

        // getLastOpRevision is a single request that says whether the account
        // moved at all.  The sweep below costs one request *per chat*, and
        // LINE bans accounts that hammer it, so it is only worth paying for
        // once this says something happened.
        val revision = runCatching {
            authed { withContext(Dispatchers.IO) { it.getLastOpRevision() } }
        }.onFailure { Log.d(TAG, "revision check failed: $it") }.getOrNull()
        if (revision != null) {
            if (revision == lastSeenRevision) return emptyList()
            lastSeenRevision = revision
        }

        openChatMid?.let { mid ->
            runCatching { fetchMessages(c, mid, 50) }
                .onFailure { Log.d(TAG, "refresh of $mid failed: $it") }
        }

        return runCatching { buildSummaries(c) }
            .onSuccess { lastSweep = System.currentTimeMillis() }
            .onFailure { Log.w(TAG, "sweep failed: $it") }
            .getOrDefault(emptyList())
    }

    /** Cursor for the cheap "did anything happen" check above. */
    private var lastSeenRevision = 0L

    /** When an op last arrived by long-poll, so the safety net can stand down. */
    @Volatile
    var lastOpAt = 0L
        private set

    private val _lastCheck = MutableStateFlow(0L)

    /** When the safety net last ran — proof that background delivery is alive. */
    val lastCheck: StateFlow<Long> = _lastCheck.asStateFlow()

    /**
     * Combine what we already hold with a fresh fetch, oldest first.
     *
     * Merging rather than replacing matters twice over: a message that arrived
     * by long-poll while the fetch was in flight would otherwise vanish, and a
     * copy we decrypted locally (our own sealed sends, where we know the
     * plaintext outright) must not lose to an undecryptable copy from the
     * server.
     */
    private fun merge(existing: List<Message>, fetched: List<Message>): List<Message> {
        val byKey = LinkedHashMap<String, Message>()
        for (msg in existing + fetched) {
            val key = msg.id ?: "${msg.createdTime}/${msg.sender}"
            val previous = byKey[key]
            byKey[key] = when {
                previous == null -> msg
                previous.text.isNullOrEmpty() -> msg
                msg.text.isNullOrEmpty() -> previous
                else -> msg
            }
        }
        val merged = byKey.values.sortedBy { it.createdTime ?: 0L }

        // Hand back the instances we already had when nothing actually moved.
        // A re-fetch produces fresh Message objects, and the ByteArray in
        // `chunks` compares by identity, so those would never be equal — the
        // StateFlow would re-emit on every refresh and recompose the whole
        // conversation several times a minute for no reason.
        val unchanged = merged.size == existing.size && merged.indices.all { i ->
            merged[i].id == existing[i].id && merged[i].text == existing[i].text
        }
        return if (unchanged) existing else merged
    }

    fun send(chatMid: String, text: String, replyToId: String? = null) {
        if (client == null || text.isBlank()) return
        scope.launch {
            try {
                val sent = authed {
                    withContext(Dispatchers.IO) {
                        it.sendMessage(chatMid, text, replyTo = replyToId)
                    }
                }
                if (sent != null) appendMessage(chatMid, sent)
            } catch (e: Exception) {
                Log.w(TAG, "send failed", e)
                _status.value = e.messageForUser()
            }
        }
    }

    /**
     * Drop the unread badge, locally only.
     *
     * Deliberately tells LINE nothing.  A read receipt is visible to the other
     * party, and opening a chat to glance at it is not the same thing as
     * wanting them to know you read it — see [sendReadReceipt].
     */
    fun clearUnread(chatMid: String) {
        unread.remove(chatMid)
        val current = _chats.value
        // Called on every refresh of the open chat, so bail out rather than
        // rebuilding an identical list several times a minute.
        if (current.none { it.chatMid == chatMid && it.unreadCount > 0 }) return
        _chats.value = current.map {
            if (it.chatMid == chatMid) it.copy(unreadCount = 0) else it
        }
    }

    private val _sendingImage = MutableStateFlow(false)
    val sendingImage: StateFlow<Boolean> = _sendingImage.asStateFlow()

    /**
     * Upload an image or video into a chat.
     *
     * The upload creates the message on LINE's side, so there is nothing to
     * append locally — the conversation is re-read afterwards to pick it up.
     */
    fun sendMedia(chatMid: String, uri: Uri) {
        if (client == null || _sendingImage.value) return
        scope.launch {
            _sendingImage.value = true
            try {
                val mime = appContext.contentResolver.getType(uri).orEmpty()
                val isVideo = mime.startsWith("video")
                val isGif = mime == "image/gif"

                val data = withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } ?: error("Could not read the selected file.")

                // The whole file is held in memory to be sent in one request,
                // so refuse anything that would risk the heap rather than
                // failing halfway through the upload.
                val cap = if (isVideo) MAX_VIDEO_BYTES else MAX_IMAGE_BYTES
                if (data.size > cap) {
                    error(
                        "That file is ${data.size / 1_000_000}MB; " +
                            "the limit is ${cap / 1_000_000}MB."
                    )
                }

                val mediaType = when {
                    isVideo -> "video"
                    isGif -> "gif"
                    else -> "image"
                }
                val name = fileNameOf(uri)
                    ?: "${System.currentTimeMillis()}.${if (isVideo) "mp4" else "jpg"}"
                val duration = if (isVideo) videoDurationMs(uri) else null

                val before = messageIdsIn(chatMid)
                authed {
                    withContext(Dispatchers.IO) {
                        it.sendMedia(chatMid, data, mediaType, name, duration)
                    }
                } ?: error("Not signed in.")

                _status.value = if (isVideo) "Video sent" else "Image sent"
                awaitUploadedMessage(chatMid, before)
            } catch (e: Exception) {
                Log.w(TAG, "media send failed", e)
                _status.value = imageFailureMessage(e)
            } finally {
                _sendingImage.value = false
            }
        }
    }

    private fun messageIdsIn(chatMid: String): Set<String> =
        _messages.value[chatMid]?.mapNotNull { it.id }?.toSet().orEmpty()

    /**
     * Wait for an upload to surface as a message.
     *
     * The upload creates the message server-side, but it is not in the message
     * box the instant the HTTP call returns — fetching once straight away
     * usually comes back without it, which left the conversation looking as
     * though nothing had been sent.
     */
    private suspend fun awaitUploadedMessage(chatMid: String, before: Set<String>) {
        val c = client ?: return
        repeat(UPLOAD_SYNC_ATTEMPTS) {
            runCatching { fetchMessages(c, chatMid, OPEN_CHAT_COUNT) }
            if ((messageIdsIn(chatMid) - before).isNotEmpty()) return
            delay(UPLOAD_SYNC_DELAY_MS)
        }
        Log.d(TAG, "uploaded message has not appeared in $chatMid yet")
    }

    /** LINE wants the clip length in milliseconds; missing is better than wrong. */
    private fun videoDurationMs(uri: Uri): Long? = runCatching {
        // MediaMetadataRetriever only became AutoCloseable in API 29, and
        // minSdk here is 24.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    // -- downloading media -------------------------------------------------

    sealed interface MediaState {
        data object Loading : MediaState
        data class Ready(val file: File) : MediaState
        /** No thumbnail exists, but the object itself can still be opened. */
        data object NoPreview : MediaState
        data class Failed(val reason: String) : MediaState
    }

    private val _media = MutableStateFlow<Map<String, MediaState>>(emptyMap())

    /** Downloaded objects, keyed by [mediaKey]. */
    val media: StateFlow<Map<String, MediaState>> = _media.asStateFlow()

    /** Three at a time: thumbnails arrive in bursts as a conversation scrolls. */
    private val downloadGate = Semaphore(3)

    fun mediaKey(messageId: String?, preview: Boolean): String =
        "$messageId@${if (preview) "preview" else "original"}"

    private fun mediaFile(messageId: String, preview: Boolean): File {
        val dir = File(appContext.filesDir, "media").apply { mkdirs() }
        return File(dir, "${messageId}_${if (preview) "preview" else "original"}")
    }

    /**
     * Fetch an attached object, caching it on disk.
     *
     * Media is not carried in the message — it lives in object storage keyed by
     * the message id, and in a sealed chat it is encrypted with key material
     * that only the decrypted message body holds.
     *
     * @param preview fetch the thumbnail rather than the full object.
     */
    fun requestMedia(msg: Message, preview: Boolean = true) {
        val id = msg.id ?: return
        val key = mediaKey(id, preview)
        if (_media.value[key] != null) return

        val cached = mediaFile(id, preview)
        if (cached.exists() && cached.length() > 0) {
            _media.value = _media.value + (key to MediaState.Ready(cached))
            return
        }

        _media.value = _media.value + (key to MediaState.Loading)
        scope.launch {
            try {
                if (client == null) error("Not signed in.")
                val fetched = downloadGate.withPermit {
                    authed { withContext(Dispatchers.IO) { fetchWithFallback(it, msg, preview) } }
                }
                if (fetched == null) {
                    _media.value = _media.value + (key to MediaState.NoPreview)
                    scheduleRecentRetry(msg, preview)
                    return@launch
                }

                // An empty object is not a success.  Writing a zero-byte file
                // and calling it Ready is what put an empty bubble on screen
                // that only filled in once it was tapped.
                if (fetched.bytes.isEmpty()) error("The server returned an empty object.")

                withContext(Dispatchers.IO) { cached.writeBytes(fetched.bytes) }
                _media.value = _media.value + (key to MediaState.Ready(cached))

                // When a thumbnail request had to fall back, what we hold *is*
                // the original — so file it under that key too and spare the
                // viewer a second download of the same bytes.
                if (fetched.fellBackToOriginal) {
                    val originalFile = mediaFile(id, preview = false)
                    withContext(Dispatchers.IO) { cached.copyTo(originalFile, overwrite = true) }
                    _media.value = _media.value +
                        (mediaKey(id, preview = false) to MediaState.Ready(originalFile))
                }
            } catch (e: Exception) {
                Log.w(TAG, "media download failed for $id (preview=$preview): ${e.message}")
                _media.value = _media.value +
                    (key to MediaState.Failed(e.messageForUser()))
                scheduleRecentRetry(msg, preview)
            }
        }
    }

    private val mediaRetries = mutableMapOf<String, Int>()

    /**
     * Try again shortly for a message that has only just been created.
     *
     * A freshly uploaded object has no thumbnail for a second or two. Without
     * this the first failed attempt is cached and the bubble stays empty until
     * the conversation is reopened — which is exactly what you see right after
     * sending something.
     */
    private fun scheduleRecentRetry(msg: Message, preview: Boolean) {
        val created = msg.createdTime ?: return
        if (System.currentTimeMillis() - created > RECENT_MEDIA_MS) return

        val key = mediaKey(msg.id, preview)
        val attempts = mediaRetries[key] ?: 0
        if (attempts >= MEDIA_RETRY_LIMIT) return
        mediaRetries[key] = attempts + 1

        scope.launch {
            delay(MEDIA_RETRY_DELAY_MS)
            retryMedia(msg, preview)
        }
    }

    private class Fetched(val bytes: ByteArray, val fellBackToOriginal: Boolean)

    /**
     * Fetch an object, falling back to the original when no thumbnail exists.
     *
     * A thumbnail is only ever an optimisation and LINE does not always have
     * one. For an image the original serves perfectly well — Coil scales it for
     * the bubble.
     *
     * @return null when there is no thumbnail and fetching the original instead
     *   would be pointless, which the caller shows as [MediaState.NoPreview].
     */
    private fun fetchWithFallback(c: LineClient, msg: Message, preview: Boolean): Fetched? {
        if (!preview) return Fetched(c.downloadMedia(msg, preview = false), false)

        val thumbnail = runCatching { c.downloadMedia(msg, preview = true) }
            .onFailure { Log.d(TAG, "no thumbnail for ${msg.id}: ${it.message}") }
            .getOrNull()
        if (thumbnail != null && thumbnail.isNotEmpty()) return Fetched(thumbnail, false)

        // Only an image is worth falling back for.  Pulling a whole video down
        // to draw a 220dp bubble would be absurd, and the bytes could not be
        // rendered as a still anyway.
        if (msg.contentType != ContentType.IMAGE) return null

        Log.d(TAG, "falling back to the original for ${msg.id}")
        return Fetched(c.downloadMedia(msg, preview = false), true)
    }

    /** Drop a failed entry so the UI can offer a retry. */
    fun retryMedia(msg: Message, preview: Boolean) {
        _media.value = _media.value - mediaKey(msg.id, preview)
        requestMedia(msg, preview)
    }

    /**
     * A failed upload gives back an HTTP status and little else, and much the
     * likeliest cause is a letter-sealed chat — those reject a plain upload the
     * same way they reject plain text.
     */
    private fun imageFailureMessage(e: Exception): String {
        val base = e.messageForUser()
        return if (e is LineTransportError) {
            "$base — if this chat is letter-sealed, plain image upload is not supported."
        } else {
            base
        }
    }

    private fun fileNameOf(uri: Uri): String? = runCatching {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }
    }.getOrNull()

    /**
     * Drop every unread badge at once, locally only.
     *
     * Sends nothing: this is for clearing a backlog of dots you have already
     * dealt with elsewhere, not for telling anyone you have read them. The ✓ in
     * a conversation is still the only thing that emits a read receipt.
     */
    fun clearAllUnread() {
        unread.clear()
        val current = _chats.value
        if (current.none { it.unreadCount > 0 }) return
        _chats.value = current.map {
            if (it.unreadCount == 0) it else it.copy(unreadCount = 0)
        }
    }

    /** Tell LINE the chat has been read.  Only ever on an explicit request. */
    fun sendReadReceipt(chatMid: String) {
        val c = client ?: return
        val lastId = _messages.value[chatMid]?.lastOrNull()?.id ?: return
        scope.launch {
            _status.value = try {
                withContext(Dispatchers.IO) { c.sendReadReceipt(chatMid, lastId) }
                "Marked as read"
            } catch (e: Exception) {
                Log.w(TAG, "read receipt failed", e)
                e.messageForUser()
            }
        }
    }

    private fun appendMessage(chatMid: String, msg: Message) {
        val existing = _messages.value[chatMid].orEmpty()
        if (msg.id != null && existing.any { it.id == msg.id }) return
        _messages.value = _messages.value + (chatMid to (existing + msg))
        bumpSummary(chatMid, msg)
    }

    private fun bumpSummary(chatMid: String, msg: Message) {
        val selfMid = client?.mid ?: ""
        val current = _chats.value
        val existing = current.find { it.chatMid == chatMid }
        val updated = (existing ?: ChatSummary(
            chatMid = chatMid,
            chatType = if (chatMid.startsWith("u")) ChatType.PEER else ChatType.GROUP,
            name = displayNames[chatMid] ?: chatMid,
            picturePath = pictures[chatMid],
            lastMessage = null,
            senderName = null,
        )).copy(
            lastMessage = msg,
            senderName = msg.sender?.let { senderLabel(it, selfMid) },
            unreadCount = unread[chatMid] ?: 0,
        )
        _chats.value = (listOf(updated) + current.filterNot { it.chatMid == chatMid })
            .sortedByDescending { it.timestamp }
    }

    // -- incoming operations ----------------------------------------------

    /**
     * Fold one long-poll batch into the store.
     *
     * @return the messages that deserve a notification — incoming only, and not
     *   for the chat the user is currently looking at.
     */
    fun applyOps(ops: List<Operation>): List<Pair<ChatSummary, Message>> {
        val c = client ?: return emptyList()
        if (ops.isNotEmpty()) lastOpAt = System.currentTimeMillis()
        val notify = mutableListOf<Pair<ChatSummary, Message>>()

        for (op in ops) {
            when (op.opType) {
                OpType.RECEIVE_MESSAGE, OpType.SEND_MESSAGE -> {
                    val msg = c.decorate(op).message ?: continue
                    // A 1:1 message is addressed to us, so the *chat* is the
                    // other party — which is the sender, not the `to` field.
                    val chatMid = when {
                        msg.to == c.mid -> msg.sender ?: continue
                        else -> msg.to ?: continue
                    }
                    // A redelivered op is not a new message.  appendMessage
                    // folds one away silently, but the notify below would still
                    // fire — and a second notify for the same chat re-alerts
                    // rather than stacking, so it lands as one notification that
                    // made the sound twice.
                    val known = msg.id != null &&
                        _messages.value[chatMid].orEmpty().any { it.id == msg.id }
                    if (known) continue

                    val incoming = msg.sender != c.mid
                    if (incoming && chatMid != openChatMid) {
                        unread[chatMid] = (unread[chatMid] ?: 0) + 1
                    }
                    appendMessage(chatMid, msg)
                    if (incoming && chatMid != openChatMid) {
                        _chats.value.find { it.chatMid == chatMid }?.let { notify += it to msg }
                    }
                }

                OpType.NOTIFIED_READ_MESSAGE -> recordRead(op)

                // Our own receipt, echoed back.  Nobody is told we read our
                // own chat, so there is nothing to show for it.
                OpType.SEND_CHAT_CHECKED -> Unit

                else -> Log.d(TAG, "unhandled op ${op.opType}")
            }
        }
        if (ops.isNotEmpty()) rememberLastSeen()
        return notify
    }

    // -- helpers -----------------------------------------------------------

    /**
     * Someone read a chat we are in — the op behind the read label.
     *
     * The three params are `chatMid`, the reader, and the message they read up
     * to.  Observed in a 1:1, where the first two are both the other party:
     *
     *     p1=u9ac1f48…  p2=u9ac1f48…  p3=624950626019180681
     *
     * That last one is the least dependable: LINE has shipped it as a message
     * id, as empty, and as something that is not an id at all, depending on the
     * client version the receipt came from.  Only a decimal id is usable,
     * because [readCounts] positions readers by comparing ids numerically;
     * anything else is treated as "read everything we currently hold" and
     * pinned to the newest message in the chat, which is what the receipt means
     * in practice anyway.
     */
    private fun recordRead(op: Operation) {
        val c = client ?: return
        val reader = op.param2 ?: return
        // Our own other devices reading do not put a read label on our messages.
        if (reader == c.mid) return
        // A 1:1 receipt names the chat from the reader's side, so it can arrive
        // addressed to us; the chat is then the other party.  Same shape as the
        // message case above.
        val chatMid = op.param1?.takeIf { it != c.mid } ?: reader

        val reported = op.param3?.takeIf { it.toLongOrNull() != null }
        val mark = reported ?: (_messages.value[chatMid]?.lastOrNull()?.id ?: return)
        if (reported == null) {
            Log.d(TAG, "read receipt for $chatMid carried param3=${op.param3}, using $mark")
        }

        mergeReadMarks(chatMid, mapOf(reader to mark))
    }

    /**
     * Fold read marks into [_readReceipts], keeping whichever is furthest on.
     *
     * Marks reach us from two directions — the live `NOTIFIED_READ_MESSAGE`
     * operations and the [LineClient.getMessageReadRange] backfill — and the
     * two overlap and race.  A mark that moves backwards would un-read messages
     * already shown as read, so the newest always wins regardless of which
     * source it came from or which order they arrive in.
     */
    private fun mergeReadMarks(chatMid: String, marks: Map<String, String>) {
        val self = client?.mid
        // Our own reading is not a read label on our own messages.
        val incoming = marks.filterKeys { it != self }
        if (incoming.isEmpty()) return
        _readReceipts.update { all ->
            val forChat = all[chatMid].orEmpty()
            val merged = forChat.toMutableMap()
            for ((reader, mark) in incoming) {
                val current = merged[reader]?.toLongOrNull()
                val next = mark.toLongOrNull()
                if (current != null && next != null && current >= next) continue
                merged[reader] = mark
            }
            if (merged == forChat) all else all + (chatMid to merged)
        }
    }

    /**
     * Ask LINE how far everyone has read in [chatMid].
     *
     * Operations only report reads that happen while the poll is running, so on
     * their own every read mark vanishes when the app restarts and does not come back
     * until somebody reads something new.  This is what makes opening a chat
     * show the read state it already has.
     */
    fun loadReadReceipts(chatMid: String) {
        val c = client ?: return
        scope.launch {
            try {
                val ranges = withContext(Dispatchers.IO) { c.getMessageReadRange(listOf(chatMid)) }
                ranges.forEach { (mid, marks) -> mergeReadMarks(mid, marks) }
            } catch (e: Exception) {
                // Nothing to surface: the chat is readable either way, it just
                // shows no read label until an operation supplies one.
                Log.w(TAG, "read range for $chatMid failed", e)
            }
        }
    }

    private suspend fun <T> coroutineScopeIO(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.IO) { kotlinx.coroutines.coroutineScope(block) }

    private fun <K, V> MutableMap<K, V>.putIfAbsentCompat(key: K, value: V) {
        if (!containsKey(key)) put(key, value)
    }

    private val notificationSeq = AtomicInteger(1000)
    fun nextNotificationId(): Int = notificationSeq.incrementAndGet()
}

/** Thrift error codes mean nothing to a user; surface something readable. */
internal fun Exception.messageForUser(): String = when (this) {
    is UnsupportedOperationException -> message ?: "Not supported."

    is LineServiceError -> when (code) {
        1 -> "Authentication failed — check your email and password."
        6 -> "LINE rejected the request as too large. It will be retried in smaller batches."
        8 -> "Session expired. Please sign in again."
        // Refused twice, so a freshly fetched group key did not help either.
        99 -> "This group's encryption key was rejected. Reopening the group may help."
        // Keep the code and the call that failed.  LINE's own wording is terse
        // enough ("Invalid Length") to be useless on its own, and without
        // knowing which request produced it there is nothing to go on.
        else -> "LINE error $code: ${message.ifEmpty { "no detail" }}"
    }

    else -> message ?: this::class.java.simpleName
}
