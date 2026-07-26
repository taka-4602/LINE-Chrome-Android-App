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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
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

private enum class Tab(val label: String) { Chats("Chats"), Friends("Friends"), Settings("Settings") }

/**
 * Where the layout stops stacking and becomes list + detail.
 *
 * The two states of a foldable land either side of this on their own: a Fold5's
 * inner screen is about 690dp wide and its cover screen about 344dp, so opening
 * the device is what switches the layout, with no device check anywhere. A phone
 * held in landscape (740dp on the test handset) gets the wide layout too, which
 * is the same trade every mail client makes.
 *
 * Below this the detail pane would be narrower than a readable message column,
 * which is worse than paging between the two.
 */
private val TWO_PANE_MIN_WIDTH = 600.dp

/**
 * The screen that sits opposite the list: a conversation, or somebody's profile.
 *
 * Modelled as one value rather than two nullable MIDs because the two are
 * mutually exclusive — a profile opened from a conversation covers it, and both
 * of them cover the tabs when there is only one pane to put them in.
 */
private sealed interface Detail {
    val mid: String

    data class Conversation(override val mid: String) : Detail
    data class Person(override val mid: String) : Detail
}

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

    // Saved rather than merely remembered: folding the device is a
    // configuration change, and losing the open conversation every time the
    // screen changes size is the thing that makes an app feel foldable-hostile.
    var tab by rememberSaveable { mutableStateOf(Tab.Chats) }
    var openChatMid by rememberSaveable { mutableStateOf<String?>(null) }
    var openProfileMid by rememberSaveable { mutableStateOf<String?>(null) }
    var openMedia by remember { mutableStateOf<Message?>(null) }
    var notificationsEnabled by remember {
        mutableStateOf(LineRepository.sessionStore.notificationsEnabled)
    }
    val snackbar = remember { SnackbarHostState() }

    // A notification tap arrives as an Intent extra.
    LaunchedEffect(pendingChatMid) {
        val mid = pendingChatMid ?: return@LaunchedEffect
        openProfileMid = null
        openChatMid = mid
        onPendingChatConsumed()
    }

    // Only the MID is held, and the header is rebuilt from whatever currently
    // knows the name — the chat list, the profile just fetched, or the name
    // cache for a friend who has never been messaged.
    val openChat: ChatTarget? = openChatMid?.let { mid ->
        val summary = chats.find { it.chatMid == mid }
        val info = profiles[mid]
        ChatTarget(
            mid = mid,
            name = info?.name ?: summary?.name ?: LineRepository.nameOf(mid),
            isGroup = info?.isGroup ?: summary?.isGroup ?: !mid.startsWith("u"),
            picturePath = info?.picturePath
                ?: summary?.picturePath
                ?: LineRepository.pictureOf(mid),
        )
    }

    LaunchedEffect(openChatMid) {
        LineRepository.openChatMid = openChatMid
        openChatMid?.let {
            LineRepository.loadMessages(it)
            LineRepository.clearUnread(it)
        }
    }

    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            LineRepository.dismissError()
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoPane = maxWidth >= TWO_PANE_MIN_WIDTH
        // The list keeps a comfortable row width and the conversation takes the
        // rest. Splitting down the middle instead would leave both halves
        // slightly too narrow on a Fold held upright.
        val listWidth = (maxWidth * 0.38f).coerceIn(300.dp, 380.dp)

        val detail: Detail? = openProfileMid?.let { Detail.Person(it) }
            ?: openChatMid?.let { Detail.Conversation(it) }

        // An empty half is a waste of an unfolded screen, so the newest
        // conversation opens itself — unfolding lands in a chat rather than in
        // a blank pane.  Only ever fills a gap; it never overrides a choice.
        LaunchedEffect(twoPane, chats.firstOrNull()?.chatMid) {
            if (twoPane && detail == null) openChatMid = chats.firstOrNull()?.chatMid
        }

        // Coming back to the chat list should show current state.  With two
        // panes the list is never left behind, so this stops depending on
        // whether a conversation is open.
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        LaunchedEffect(lifecycle, tab, twoPane, openChatMid) {
            if (tab != Tab.Chats) return@LaunchedEffect
            if (!twoPane && openChatMid != null) return@LaunchedEffect
            lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                LineRepository.refreshChatsIfStale()
            }
        }

        // Back unwinds one layer at a time.  With two panes the conversation is
        // not a layer over anything — the list is still right there — so back
        // leaves the app instead of emptying half the screen.
        BackHandler(
            enabled = openMedia != null || openProfileMid != null ||
                (!twoPane && openChatMid != null)
        ) {
            when {
                openMedia != null -> openMedia = null
                openProfileMid != null -> openProfileMid = null
                else -> openChatMid = null
            }
        }

        val homePane: @Composable () -> Unit = {
            Scaffold(
                topBar = {
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
                },
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
                // With both panes up the snackbar is hosted once, across the
                // whole window; two hosts sharing one state would show it twice.
                snackbarHost = { if (!twoPane) SnackbarHost(snackbar) },
            ) { padding ->
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
                            // Which row the right-hand pane is showing.  Nothing
                            // is "selected" when the list is the whole screen.
                            selectedMid = if (twoPane) openChatMid else null,
                            onOpenChat = { summary ->
                                openProfileMid = null
                                openChatMid = summary.chatMid
                            },
                        )

                        Tab.Friends -> FriendsScreen(
                            contacts = contacts,
                            groups = groups,
                            contentPadding = body,
                            onOpenProfile = { openProfileMid = it },
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
        }

        val detailPane: @Composable (Detail) -> Unit = { screen ->
            when (screen) {
                is Detail.Person -> ProfileScreen(
                    mid = screen.mid,
                    profile = profiles[screen.mid],
                    participants = LineRepository.participantsOf(screen.mid),
                    nameOf = LineRepository::nameOf,
                    pictureOf = LineRepository::pictureOf,
                    onLoad = LineRepository::loadProfile,
                    onBack = { openProfileMid = null },
                    onOpenChat = { mid ->
                        openProfileMid = null
                        openChatMid = mid
                    },
                    onOpenProfile = { openProfileMid = it },
                )

                is Detail.Conversation -> openChat?.let { target ->
                    ChatRoomScreen(
                        target = target,
                        messages = messages[target.mid].orEmpty(),
                        selfMid = profile.mid,
                        nameOf = LineRepository::nameOf,
                        pictureOf = LineRepository::pictureOf,
                        // Alongside the list there is nothing to go back to.
                        showBack = !twoPane,
                        onBack = { openChatMid = null },
                        onSend = { LineRepository.send(target.mid, it) },
                        onMarkRead = { LineRepository.sendReadReceipt(target.mid) },
                        onOpenProfile = { openProfileMid = it },
                        onSendImage = { LineRepository.sendMedia(target.mid, it) },
                        sendingImage = sendingImage,
                        mediaState = { media[LineRepository.mediaKey(it.id, preview = true)] },
                        onRequestMedia = { LineRepository.requestMedia(it, preview = true) },
                        onRetryMedia = { LineRepository.retryMedia(it, preview = true) },
                        onOpenMedia = { openMedia = it },
                        onReload = { LineRepository.reloadConversation(target.mid) },
                        reloading = reloadingChat,
                        onStatus = LineRepository::showStatus,
                        snackbarHost = { if (!twoPane) SnackbarHost(snackbar) },
                    )
                }
            }
        }

        val viewing = openMedia
        when {
            // The viewer takes the whole window even when there are two panes:
            // a photograph is what you opened it to look at.
            viewing != null -> MediaViewerScreen(
                message = viewing,
                state = media[LineRepository.mediaKey(viewing.id, preview = false)],
                onRequest = { LineRepository.requestMedia(viewing, preview = false) },
                onClose = { openMedia = null },
            )

            twoPane -> Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(listWidth)) { homePane() }
                VerticalDivider()
                Box(Modifier.fillMaxSize()) {
                    if (detail != null) {
                        detailPane(detail)
                    } else {
                        EmptyState(
                            title = "Nothing open",
                            subtitle = "Pick a conversation on the left.",
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }

            else -> AnimatedContent(
                targetState = detail,
                transitionSpec = {
                    if (targetState != null) {
                        slideInHorizontally { it } + fadeIn() togetherWith fadeOut()
                    } else {
                        fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
                    }
                },
                label = "screen",
            ) { screen ->
                if (screen != null) detailPane(screen) else homePane()
            }
        }

        if (twoPane) {
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

private fun Tab.icon() = when (this) {
    Tab.Chats -> Icons.Filled.Home
    Tab.Friends -> Icons.Filled.Person
    Tab.Settings -> Icons.Filled.Settings
}
