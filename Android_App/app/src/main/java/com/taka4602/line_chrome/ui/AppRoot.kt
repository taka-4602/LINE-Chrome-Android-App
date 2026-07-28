package com.taka4602.line_chrome.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.taka4602.line_chrome.R
import com.taka4602.line_chrome.data.LineRepository
import com.taka4602.line_chrome.data.LineRepository.AuthState
import com.taka4602.line_chrome.line.Message
import com.taka4602.line_chrome.ui.components.ConnectionBanner
import com.taka4602.line_chrome.ui.components.EmptyState
import com.taka4602.line_chrome.ui.screens.ChatRoomScreen
import com.taka4602.line_chrome.ui.screens.ChatTarget
import com.taka4602.line_chrome.ui.screens.ChatsScreen
import com.taka4602.line_chrome.ui.screens.FriendsScreen
import com.taka4602.line_chrome.ui.screens.LoginScreen
import com.taka4602.line_chrome.ui.screens.MediaViewerScreen
import com.taka4602.line_chrome.ui.screens.ProfileScreen
import com.taka4602.line_chrome.ui.screens.SettingsScreen
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/**
 * Where the panes split.
 *
 * A Fold's cover screen is about 340dp wide and its inner one about 670dp, so
 * 600dp puts those two states cleanly either side.  It is a width test rather
 * than a foldable test on purpose: an ordinary phone turned landscape is around
 * 640-900dp and gets the two-pane layout as well, which is the point.
 */
private val TWO_PANE_MIN_WIDTH = 600.dp

/** The list pane tracks the window but stays legible and never hogs it. */
private const val LIST_PANE_FRACTION = 0.38f
private val LIST_PANE_MIN = 280.dp
private val LIST_PANE_MAX = 400.dp

private enum class Tab(val label: String) { Chats("Chats"), Friends("Friends"), Settings("Settings") }

/**
 * What the detail pane is showing, as identity rather than content.
 *
 * The pane rebuilds everything it draws from the mid, so this deliberately
 * carries nothing else.  A [ChatTarget] would have been the obvious state to
 * animate between, but it is a data class over a name and a picture that both
 * arrive a moment after the conversation opens — each arrival would be a new,
 * unequal value, and a seeking transition would restart mid-swipe every time.
 */
private sealed interface Pane {
    val mid: String

    data class Profile(override val mid: String) : Pane
    data class Chat(override val mid: String) : Pane
}

/**
 * How deep a pane sits, so a transition can tell going in from coming out.
 *
 * A profile is reachable both from the list and from a conversation; either way
 * it is the deepest thing on screen, which is all this has to get right.
 */
private fun Pane?.depth() = when (this) {
    null -> 0
    is Pane.Chat -> 1
    is Pane.Profile -> 2
}

/** How long an untouched push or pop runs for. */
private const val PANE_MS = 350

/**
 * How far the pane underneath trails the one sliding over it, as a divisor of
 * the width.  A quarter is enough to read as depth without the two looking like
 * they are on separate rails.
 */
private const val PANE_PARALLAX = 4

/** How dark the covered pane goes once it is fully behind the one above it. */
private const val PANE_DIM = 0.32f

/**
 * The shape of a pane transition, held deliberately straight.
 *
 * A [SeekableTransitionState] seeks by mapping its fraction onto playback time —
 * `playTime = fraction * totalDuration` — and evaluating every child animation
 * there.  So whatever curve lives in here is a curve a dragging finger has to
 * fight, and the library defaults are springs: a spring covers almost all of its
 * travel in the first third of its estimated duration and then spends the rest
 * asymptotically settling within a one-pixel threshold.  Seeked by hand that
 * reads as a pane which races ahead of the thumb, stalls for the back two thirds
 * of the swipe, and jumps on release.  Worse, each spring estimates its own
 * duration, so a fade and a slide in the same transition finish at different
 * fractions and the outgoing pane can be fully transparent a third of the way
 * across.
 *
 * Linear, and identical for every component, means fraction 0.5 is half way
 * across for all of them at once.  The motion curve is not lost, only moved: it
 * belongs to whatever drives the fraction, which is [PANE_MOTION] for a tap and
 * the finger itself for a gesture.
 */
private fun <T> paneSpec(): FiniteAnimationSpec<T> = tween(PANE_MS, easing = LinearEasing)

/** The curve a pane travels on when nothing is dragging it. */
private val PANE_MOTION: FiniteAnimationSpec<Float> = tween(PANE_MS, easing = FastOutSlowInEasing)

/**
 * The curve a released gesture finishes on.  Shorter than [PANE_MOTION] and
 * purely decelerating: the finger has already covered most of the distance, so
 * this only has to carry the last of it and come to rest.
 */
private val PANE_SETTLE: FiniteAnimationSpec<Float> = tween(200, easing = LinearOutSlowInEasing)

/**
 * Raw back progress is linear in finger travel, which glues the pane to the
 * thumb and runs it off screen well before the gesture has committed to
 * anything.  This is the cubic Material curves its own predictive back with.
 */
private val PredictiveBackEasing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)

@Composable
fun AppRoot(pendingChatMid: String?, onPendingChatConsumed: () -> Unit) {
    val auth by LineRepository.auth.collectAsState()

    when (val state = auth) {
        is AuthState.Starting -> Splash()

        is AuthState.LoggedIn -> SignedIn(
            profile = state.profile,
            pendingChatMid = pendingChatMid,
            onPendingChatConsumed = onPendingChatConsumed,
        )

        else -> LoginScreen(
            state = state,
            savedEmail = LineRepository.savedEmail,
            onEmailLogin = LineRepository::loginWithEmail,
            onTokenLogin = LineRepository::loginWithToken,
            onDismissError = LineRepository::dismissError,
        )
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(48.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignedIn(
    profile: com.taka4602.line_chrome.line.Profile,
    pendingChatMid: String?,
    onPendingChatConsumed: () -> Unit,
) {
    val chats by LineRepository.chats.collectAsState()
    val contacts by LineRepository.contacts.collectAsState()
    val groups by LineRepository.groups.collectAsState()
    val messages by LineRepository.messages.collectAsState()
    val syncing by LineRepository.syncing.collectAsState()
    val status by LineRepository.status.collectAsState()
    val connection by LineRepository.connection.collectAsState()
    val profiles by LineRepository.profiles.collectAsState()
    val sendingImage by LineRepository.sendingImage.collectAsState()
    val media by LineRepository.media.collectAsState()
    val reloadingChat by LineRepository.loadingMessages.collectAsState()

    // Saveable, not just remembered: folding and unfolding a Fold recreates the
    // Activity, and losing the open conversation every time would be miserable.
    // Only the mid is kept — the name and picture are derived below, so they
    // also stay current if the contact is renamed.
    var tab by rememberSaveable { mutableStateOf(Tab.Chats) }
    var openChatMid by rememberSaveable { mutableStateOf<String?>(null) }
    var openProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var openMedia by remember { mutableStateOf<Message?>(null) }

    // Built from the mid on demand rather than held in state, so the pane that
    // is sliding out can still be described after the selection has been
    // cleared — which is exactly the window a back gesture spends animating.
    val chatTargetOf: (String) -> ChatTarget = { mid ->
        val info = profiles[mid]
        val summary = chats.find { it.chatMid == mid }
        ChatTarget(
            mid = mid,
            name = info?.name ?: summary?.name ?: LineRepository.nameOf(mid),
            isGroup = info?.isGroup ?: summary?.isGroup ?: !mid.startsWith("u"),
            picturePath = info?.picturePath
                ?: summary?.picturePath
                ?: LineRepository.pictureOf(mid),
        )
    }

    // Profile over conversation: opening someone's profile from a chat leaves
    // the chat underneath it, and closing it comes back to the chat.
    val pane: Pane? = openProfile?.let(Pane::Profile) ?: openChatMid?.let(Pane::Chat)
    var notificationsEnabled by remember {
        mutableStateOf(LineRepository.sessionStore.notificationsEnabled)
    }
    val snackbar = remember { SnackbarHostState() }

    // A notification tap arrives as an Intent extra.  Naming is no longer this
    // effect's problem — chatTargetOf derives that from the mid.
    LaunchedEffect(pendingChatMid) {
        val mid = pendingChatMid ?: return@LaunchedEffect
        openChatMid = mid
        onPendingChatConsumed()
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // Claiming the open chat has to happen on every resume, not just when the
    // selection changes.  Backgrounding gives the chat up so an incoming message
    // notifies instead of being folded in silently, and coming back to the same
    // mid is not a state change — so keyed on the selection alone this never ran
    // again, and watchOpenChat's refresh loop stayed dead until the reload
    // button was pressed.
    LaunchedEffect(lifecycle, openChatMid, pendingChatMid) {
        // A notification tap resumes us still pointing at the previously open
        // chat and redirects a beat later.  Claiming during that beat fetches
        // the wrong conversation and, worse, cancels its notification out of the
        // tray — so sit the resume out until the pending target has landed.
        if (pendingChatMid != null) return@LaunchedEffect
        val mid = openChatMid ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            LineRepository.openChatMid = mid
            // The long-poll dies quietly while backgrounded, so nothing that
            // arrived in the meantime can be trusted to have landed.
            LineRepository.loadMessages(mid)
            LineRepository.clearUnread(mid)
            try {
                awaitCancellation()
            } finally {
                // Guarded: switching straight from one chat to another cancels
                // this after the incoming effect has already staked its claim,
                // and an unconditional clear would wipe the new mid.
                if (LineRepository.openChatMid == mid) LineRepository.openChatMid = null
            }
        }
    }

    // Coming back to the chat list should show current state.  Without this the
    // list only ever moves when the refresh button is pressed, which makes a
    // stalled long-poll indistinguishable from a broken app.
    LaunchedEffect(lifecycle, tab, openChatMid) {
        if (tab != Tab.Chats || openChatMid != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            LineRepository.refreshChatsIfStale()
        }
    }

    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            LineRepository.dismissError()
        }
    }

    // The viewer is the top layer and the only one that early-returns, so it
    // takes back on its own.  Nothing is composed behind it to preview, so this
    // stays an ordinary handler; the panes below get the predictive treatment.
    BackHandler(enabled = openMedia != null) { openMedia = null }

    // The viewer is an overlay rather than another AnimatedContent branch, so
    // the conversation stays composed and keeps its scroll position underneath.
    openMedia?.let { message ->
        MediaViewerScreen(
            message = message,
            state = media[LineRepository.mediaKey(message.id, preview = false)],
            onRequest = { LineRepository.requestMedia(message, preview = false) },
            onClose = { openMedia = null },
        )
        return@SignedIn
    }

    // Both layouts render the same two panes; only their arrangement differs,
    // so the panes are defined once here and placed twice below.
    //
    // `shown` is passed in rather than read from state because the stacked
    // layout animates between panes — the outgoing one has to keep drawing what
    // it had, not what has just been selected.
    val detailPane: @Composable (shown: Pane?, sideBySide: Boolean) -> Unit = { shown, sideBySide ->
        if (shown is Pane.Profile) {
            val mid = shown.mid
            ProfileScreen(
                mid = mid,
                profile = profiles[mid],
                participants = LineRepository.participantsOf(mid),
                nameOf = LineRepository::nameOf,
                pictureOf = LineRepository::pictureOf,
                onLoad = LineRepository::loadProfile,
                onBack = { openProfile = null },
                onOpenChat = { mid ->
                    openProfile = null
                    openChatMid = mid
                },
                onOpenProfile = { openProfile = it },
            )
        } else if (shown is Pane.Chat) {
            val mid = shown.mid
            ChatRoomScreen(
                target = chatTargetOf(mid),
                messages = messages[mid].orEmpty(),
                selfMid = profile.mid,
                nameOf = LineRepository::nameOf,
                pictureOf = LineRepository::pictureOf,
                onBack = { openChatMid = null },
                onSend = { text, replyTo -> LineRepository.send(mid, text, replyTo) },
                onMarkRead = { LineRepository.sendReadReceipt(mid) },
                onOpenProfile = { openProfile = it },
                onSendImage = { LineRepository.sendMedia(mid, it) },
                sendingImage = sendingImage,
                mediaState = { media[LineRepository.mediaKey(it.id, preview = true)] },
                onRequestMedia = { LineRepository.requestMedia(it, preview = true) },
                onRetryMedia = { LineRepository.retryMedia(it, preview = true) },
                onOpenMedia = { openMedia = it },
                onReload = { LineRepository.reloadConversation(mid) },
                reloading = reloadingChat,
                onStatus = LineRepository::showStatus,
                // Side by side both panes are on screen at once, and two hosts
                // sharing one state would show the same message twice.
                snackbarHost = if (sideBySide) ({ }) else ({ SnackbarHost(snackbar) }),
            )
        } else {
            EmptyState(
                title = "Nothing open",
                subtitle = "Pick a conversation from the list.",
                modifier = Modifier.fillMaxSize(),
            )
        }
    }

    val tabBar: @Composable () -> Unit = {
                    CenterAlignedTopAppBar(
                        title = { Text(tab.label) },
                        actions = {
                            if (tab == Tab.Chats) {
                                // Clears the dots and nothing else — no read
                                // receipt goes out, so nobody is told anything.
                                IconButton(
                                    onClick = { LineRepository.clearAllUnread() },
                                    enabled = chats.any { it.unreadCount > 0 },
                                ) {
                                    Icon(
                                        painterResource(R.drawable.ic_done_all),
                                        contentDescription = "Clear all unread badges",
                                    )
                                }
                            }
                            IconButton(
                                onClick = { LineRepository.refreshChats() },
                                enabled = !syncing,
                            ) {
                                if (syncing) {
                                    CircularProgressIndicator(Modifier.size(24.dp))
                                } else {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                                }
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                    )
    }

    val tabBody: @Composable (PaddingValues) -> Unit = { padding ->
                // The banner sits below the app bar and above the tab content,
                // so the scaffold's top inset goes on the Column and only the
                // bottom inset is handed to the list.
                Column(Modifier.padding(top = padding.calculateTopPadding())) {
                    ConnectionBanner(connection)
                    val body = PaddingValues(bottom = padding.calculateBottomPadding())

                    when (tab) {
                        Tab.Chats -> ChatsScreen(
                            chats = chats,
                            contentPadding = body,
                            onOpenChat = { summary ->
                                openChatMid = summary.chatMid
                                // Side by side the detail pane shows whichever
                                // of the two is set, so a stale profile would
                                // hide the chat just picked.
                                openProfile = null
                            },
                        )

                        Tab.Friends -> FriendsScreen(
                            contacts = contacts,
                            groups = groups,
                            contentPadding = body,
                            onOpenProfile = { openProfile = it },
                        )

                        Tab.Settings -> SettingsScreen(
                            profile = profile,
                            contentPadding = body,
                            notificationsEnabled = notificationsEnabled,
                            connection = connection,
                            onNotificationsChanged = {
                                notificationsEnabled = it
                                LineRepository.sessionStore.notificationsEnabled = it
                            },
                            onLogout = LineRepository::logout,
                        )
                    }
                }
    }

    // The tab bar belongs to the list, not to the window, so this is the whole
    // left-hand side in both layouts — same bar, same place, just narrower when
    // there is a conversation beside it.
    val listPane: @Composable (Modifier) -> Unit = { modifier ->
        Scaffold(
            modifier = modifier,
            topBar = tabBar,
            bottomBar = {
                ShortNavigationBar {
                    Tab.entries.forEach { entry ->
                        ShortNavigationBarItem(
                            selected = tab == entry,
                            onClick = { tab = entry },
                            icon = { Icon(entry.icon(), contentDescription = entry.label) },
                            label = { Text(entry.label) },
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
            content = tabBody,
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Captured here because Row's scope shadows BoxWithConstraintsScope.
        val windowWidth = maxWidth

        if (windowWidth >= TWO_PANE_MIN_WIDTH) {
            // Both panes are already on screen, so there is no reveal to preview
            // and back is a plain state change.
            BackHandler(enabled = pane != null) {
                if (openProfile != null) openProfile = null else openChatMid = null
            }

            Row(Modifier.fillMaxSize()) {
                listPane(
                    Modifier.width(
                        (windowWidth * LIST_PANE_FRACTION).coerceIn(LIST_PANE_MIN, LIST_PANE_MAX)
                    )
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f)) {
                    detailPane(pane, true)
                }
            }
        } else {
            // Seekable rather than fire-and-forget: the same transition a tap
            // plays through is the one a back gesture scrubs by hand, so letting
            // go part-way can rewind it instead of having to jump.
            // Seeded with whatever is already open rather than with the list,
            // so this settles where it starts.  Closing the viewer recomposes
            // this branch from scratch (it early-returns above, disposing the
            // whole thing), and from a null seed that arrives as a conversation
            // sliding in over a list nobody navigated to.  Same for a restore
            // after process death, where the open mid outlives the state here.
            val paneState = remember { SeekableTransitionState(pane) }

            LaunchedEffect(pane) {
                if (paneState.targetState != pane) paneState.animateTo(pane, PANE_MOTION)
            }

            // Where back lands: out of a profile opened from a conversation is
            // that conversation, and out of anything else is the list.
            val under: Pane? =
                if (pane is Pane.Profile) openChatMid?.let(Pane::Chat) else null

            // Every animation a gesture leaves behind runs here rather than in
            // the gesture's own coroutine.  PredictiveBackHandler cancels that
            // coroutine outright — its cleanup does `channel.cancel()` *and*
            // `job.cancel()` — both when the swipe is abandoned and when a
            // second swipe starts on top of the first.  A suspending animateTo
            // in a cancelled job throws at its first suspension point and never
            // draws a frame, which is what leaves a pane stranded half way
            // across the screen with nothing left running to finish it.
            val paneScope = rememberCoroutineScope()

            PredictiveBackHandler(enabled = pane != null) { progress ->
                try {
                    progress.collect {
                        paneState.seekTo(PredictiveBackEasing.transform(it.progress), under)
                    }
                    // Committed.  The animation is finished off the state it is
                    // already seeking to, and only then is the selection cleared
                    // — clearing first would restart this composable's effect
                    // against a transition that has already arrived, which reads
                    // as "no work to do" and leaves the pane frozen mid-swipe.
                    // Both halves go to paneScope together so that a swipe
                    // interrupting this one cannot land between them and strand
                    // the selection open under a finished animation.
                    paneScope.launch {
                        paneState.animateTo(under, PANE_SETTLE)
                        if (pane is Pane.Profile) openProfile = null else openChatMid = null
                    }
                } catch (cancelled: CancellationException) {
                    // Abandoned, so wind back to where it began.  Nothing to
                    // rethrow: this coroutine is already cancelled, which is
                    // exactly why the rewind is handed to paneScope.
                    paneScope.launch {
                        paneState.animateTo(paneState.currentState, PANE_SETTLE)
                    }
                }
            }

            rememberTransition(paneState, label = "pane").AnimatedContent(
                transitionSpec = {
                    // Going deeper slides the new pane over what was there;
                    // coming back slides it off and eases the pane underneath
                    // in from a slight parallax, so the two directions do not
                    // look like the same push twice.
                    val deeper = targetState.depth() > initialState.depth()
                    ContentTransform(
                        targetContentEnter = slideInHorizontally(paneSpec()) {
                            if (deeper) it else -it / PANE_PARALLAX
                        },
                        initialContentExit = slideOutHorizontally(paneSpec()) {
                            if (deeper) -it / PANE_PARALLAX else it
                        },
                        // Depth alone decides what draws on top, which holds in
                        // both directions because each pane keeps the z-index it
                        // entered under.  Left at the default every pane sits at
                        // zero and ties break by arrival order, so a pop paints
                        // the arriving list over the conversation that is still
                        // sliding off it.
                        targetContentZIndex = targetState.depth().toFloat(),
                        // Both panes fill the window, so there is no size to
                        // animate — and the default would spring the container
                        // and clip whichever pane is mid-slide.
                        sizeTransform = null,
                    )
                },
            ) { shown ->
                // Nothing fades: a pane that thins out on its way past shows the
                // window behind it. Depth is carried by the one underneath going
                // dark instead, which is also what stops the parallax from
                // looking like two unrelated things moving at once.
                //
                // Whichever of the two panes is shallower is the covered one, in
                // both directions — a push and a pop differ only in which of
                // them is the one arriving.
                val covered = shown.depth() <
                    maxOf(paneState.currentState.depth(), paneState.targetState.depth())
                val onScreen by transition.animateFloat(
                    transitionSpec = { paneSpec() },
                    label = "onScreen",
                ) { if (it == EnterExitState.Visible) 1f else 0f }

                Box(
                    Modifier
                        .fillMaxSize()
                        // Read in the draw lambda, so scrubbing the dim costs a
                        // redraw rather than a recomposition of the whole pane.
                        .drawWithContent {
                            drawContent()
                            if (covered) {
                                drawRect(Color.Black, alpha = (1f - onScreen) * PANE_DIM)
                            }
                        }
                ) {
                    if (shown != null) detailPane(shown, false) else listPane(Modifier)
                }
            }
        }
    }
}

private fun Tab.icon() = when (this) {
    Tab.Chats -> Icons.Filled.Home
    Tab.Friends -> Icons.Filled.Person
    Tab.Settings -> Icons.Filled.Settings
}
