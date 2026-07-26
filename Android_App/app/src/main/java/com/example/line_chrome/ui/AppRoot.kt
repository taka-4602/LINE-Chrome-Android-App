package com.example.line_chrome.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.line_chrome.R
import com.example.line_chrome.data.LineRepository
import com.example.line_chrome.data.LineRepository.AuthState
import com.example.line_chrome.line.Message
import com.example.line_chrome.ui.components.ConnectionBanner
import com.example.line_chrome.ui.components.EmptyState
import com.example.line_chrome.ui.screens.ChatRoomScreen
import com.example.line_chrome.ui.screens.ChatTarget
import com.example.line_chrome.ui.screens.ChatsScreen
import com.example.line_chrome.ui.screens.FriendsScreen
import com.example.line_chrome.ui.screens.LoginScreen
import com.example.line_chrome.ui.screens.MediaViewerScreen
import com.example.line_chrome.ui.screens.ProfileScreen
import com.example.line_chrome.ui.screens.SettingsScreen

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
    profile: com.example.line_chrome.line.Profile,
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

    val openChat: ChatTarget? = openChatMid?.let { mid ->
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
    var notificationsEnabled by remember {
        mutableStateOf(LineRepository.sessionStore.notificationsEnabled)
    }
    val snackbar = remember { SnackbarHostState() }

    // A notification tap arrives as an Intent extra.  Naming is no longer this
    // effect's problem — openChat derives that from the mid.
    LaunchedEffect(pendingChatMid) {
        val mid = pendingChatMid ?: return@LaunchedEffect
        openChatMid = mid
        onPendingChatConsumed()
    }

    LaunchedEffect(openChatMid) {
        val mid = openChatMid
        LineRepository.openChatMid = mid
        if (mid != null) {
            LineRepository.loadMessages(mid)
            LineRepository.clearUnread(mid)
        }
    }

    // Coming back to the chat list should show current state.  Without this the
    // list only ever moves when the refresh button is pressed, which makes a
    // stalled long-poll indistinguishable from a broken app.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
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

    // Viewer over profile over conversation over tabs, so back unwinds one
    // layer at a time.
    BackHandler(enabled = openMedia != null || openProfile != null || openChat != null) {
        when {
            openMedia != null -> openMedia = null
            openProfile != null -> openProfile = null
            else -> openChatMid = null
        }
    }

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
    // `screen` is passed in rather than read from state because the stacked
    // layout animates between panes — the outgoing one has to keep drawing what
    // it had, not what has just been selected.
    val detailPane: @Composable (screen: Any?, sideBySide: Boolean) -> Unit = { screen, sideBySide ->
        if (screen is String) {
            ProfileScreen(
                mid = screen,
                profile = profiles[screen],
                participants = LineRepository.participantsOf(screen),
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
        } else if (screen is ChatTarget) {
            ChatRoomScreen(
                target = screen,
                messages = messages[screen.mid].orEmpty(),
                selfMid = profile.mid,
                nameOf = LineRepository::nameOf,
                pictureOf = LineRepository::pictureOf,
                onBack = { openChatMid = null },
                onSend = { text, replyTo -> LineRepository.send(screen.mid, text, replyTo) },
                onMarkRead = { LineRepository.sendReadReceipt(screen.mid) },
                onOpenProfile = { openProfile = it },
                onSendImage = { LineRepository.sendMedia(screen.mid, it) },
                sendingImage = sendingImage,
                mediaState = { media[LineRepository.mediaKey(it.id, preview = true)] },
                onRequestMedia = { LineRepository.requestMedia(it, preview = true) },
                onRetryMedia = { LineRepository.retryMedia(it, preview = true) },
                onOpenMedia = { openMedia = it },
                onReload = { LineRepository.reloadConversation(screen.mid) },
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
            Row(Modifier.fillMaxSize()) {
                listPane(
                    Modifier.width(
                        (windowWidth * LIST_PANE_FRACTION).coerceIn(LIST_PANE_MIN, LIST_PANE_MAX)
                    )
                )
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(1f)) {
                    detailPane(openProfile ?: openChat, true)
                }
            }
        } else {
            AnimatedContent(
                targetState = openProfile ?: openChat,
                transitionSpec = {
                    if (targetState != null) {
                        slideInHorizontally { it } + fadeIn() togetherWith fadeOut()
                    } else {
                        fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "screen",
            ) { screen ->
                if (screen != null) detailPane(screen, false) else listPane(Modifier)
            }
        }
    }
}

private fun Tab.icon() = when (this) {
    Tab.Chats -> Icons.Filled.Home
    Tab.Friends -> Icons.Filled.Person
    Tab.Settings -> Icons.Filled.Settings
}
