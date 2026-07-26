package com.example.line_chrome.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.line_chrome.R
import com.example.line_chrome.data.LineRepository.MediaState
import com.example.line_chrome.line.ContentType
import com.example.line_chrome.line.Message
import com.example.line_chrome.line.MessageRelationType
import com.example.line_chrome.ui.components.Avatar
import com.example.line_chrome.ui.components.MediaBubble
import com.example.line_chrome.ui.components.copyToClipboard
import com.example.line_chrome.ui.components.shareText
import com.example.line_chrome.ui.components.crossesDay
import com.example.line_chrome.ui.components.formatClock
import com.example.line_chrome.ui.components.formatDayLabel

data class ChatTarget(
    val mid: String,
    val name: String,
    val isGroup: Boolean,
    val picturePath: String?,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ChatRoomScreen(
    target: ChatTarget,
    messages: List<Message>,
    selfMid: String,
    nameOf: (String?) -> String,
    pictureOf: (String?) -> String?,
    onBack: () -> Unit,
    onSend: (text: String, replyToId: String?) -> Unit,
    onMarkRead: () -> Unit,
    onOpenProfile: (String) -> Unit,
    onSendImage: (Uri) -> Unit,
    sendingImage: Boolean,
    mediaState: (Message) -> MediaState?,
    onRequestMedia: (Message) -> Unit,
    onRetryMedia: (Message) -> Unit,
    onOpenMedia: (Message) -> Unit,
    onReload: () -> Unit,
    reloading: Boolean,
    onStatus: (String) -> Unit,
    snackbarHost: @Composable () -> Unit = {},
) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val copyMessage: (String) -> Unit = { text ->
        if (copyToClipboard(context, "Message", text)) onStatus("Copied")
    }
    // Cleared when the chat changes: a reply drafted in one conversation
    // has no meaning in another.
    var replyingTo by remember(target.mid) { mutableStateOf<Message?>(null) }
    val byId = remember(messages) { messages.associateBy { it.id } }
    // Opening a chat should land at the newest message outright; only later
    // arrivals are worth animating to.
    var landed by remember(target.mid) { mutableStateOf(false) }

    LaunchedEffect(target.mid, messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (landed) {
            listState.animateScrollToItem(messages.lastIndex)
        } else {
            listState.scrollToItem(messages.lastIndex)
            landed = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { onOpenProfile(target.mid) }
                            .padding(end = 8.dp),
                    ) {
                        Avatar(
                            name = target.name,
                            picturePath = target.picturePath,
                            mid = target.mid,
                            size = 36.dp,
                            isGroup = target.isGroup,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = target.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onReload, enabled = !reloading) {
                        if (reloading) {
                            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                        }
                    }
                    // Read receipts are visible to the other party, so they are
                    // never sent just because a chat was opened.
                    IconButton(onClick = onMarkRead, enabled = messages.isNotEmpty()) {
                        Icon(Icons.Filled.Done, contentDescription = "Mark as read")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
            )
        },
        bottomBar = {
            Composer(
                value = draft,
                onValueChange = { draft = it },
                sendingImage = sendingImage,
                onPickImage = onSendImage,
                replyingTo = replyingTo,
                replyingToName = nameOf(replyingTo?.sender),
                onCancelReply = { replyingTo = null },
                onSend = {
                    if (draft.isNotBlank()) {
                        onSend(draft, replyingTo?.id)
                        draft = ""
                        replyingTo = null
                    }
                },
            )
        },
        // Shares the host state with the tab screen, so a confirmation raised
        // in here appears in here rather than queueing up behind the back
        // navigation.
        snackbarHost = snackbarHost,
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp, vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            itemsIndexedStable(messages) { index, msg ->
                val previous = messages.getOrNull(index - 1)
                if (crossesDay(previous?.createdTime, msg.createdTime)) {
                    DaySeparator(formatDayLabel(msg.createdTime))
                }
                val mine = msg.sender == selfMid
                // In a group, only label the first message of a run — repeating
                // the name on every bubble is noise.
                val showSender = target.isGroup && !mine &&
                    previous?.sender != msg.sender
                MessageBubble(
                    message = msg,
                    mine = mine,
                    senderName = if (showSender) nameOf(msg.sender) else null,
                    senderPicture = pictureOf(msg.sender),
                    showAvatar = target.isGroup && !mine,
                    avatarVisible = showSender,
                    onOpenProfile = onOpenProfile,
                    mediaState = mediaState,
                    onRequestMedia = onRequestMedia,
                    onRetryMedia = onRetryMedia,
                    onOpenMedia = onOpenMedia,
                    onCopy = copyMessage,
                    onShare = { shareText(context, it) },
                    onReply = { replyingTo = it },
                    originalOf = { id -> byId[id] },
                    nameOf = nameOf,
                )
            }
        }
    }
}

/** LazyListScope helper: itemsIndexed keyed on message id where one exists. */
private inline fun androidx.compose.foundation.lazy.LazyListScope.itemsIndexedStable(
    messages: List<Message>,
    crossinline content: @Composable (Int, Message) -> Unit,
) = items(
    count = messages.size,
    key = { index -> messages[index].id ?: "idx-$index" },
) { index -> content(index, messages[index]) }

@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(50),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message,
    mine: Boolean,
    senderName: String?,
    senderPicture: String?,
    showAvatar: Boolean,
    avatarVisible: Boolean,
    onOpenProfile: (String) -> Unit,
    mediaState: (Message) -> MediaState?,
    onRequestMedia: (Message) -> Unit,
    onRetryMedia: (Message) -> Unit,
    onOpenMedia: (Message) -> Unit,
    onCopy: (String) -> Unit,
    onShare: (String) -> Unit,
    onReply: (Message) -> Unit,
    originalOf: (String) -> Message?,
    nameOf: (String?) -> String,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (showAvatar) {
            if (avatarVisible) {
                // In a group the avatar is the natural way in to who someone is.
                Box(Modifier.clickable { message.sender?.let(onOpenProfile) }) {
                    Avatar(
                        name = senderName ?: "?",
                        picturePath = senderPicture,
                        mid = message.sender ?: "",
                        size = 32.dp,
                    )
                }
            } else {
                // Keeps the run of bubbles aligned under the one avatar.
                Spacer(Modifier.size(32.dp))
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
            if (senderName != null) {
                Text(
                    text = senderName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 2.dp),
                )
            }

            Row(verticalAlignment = Alignment.Bottom) {
                if (mine) {
                    TimeLabel(message.createdTime)
                    Spacer(Modifier.width(6.dp))
                }

                // Only real text is worth copying — "[Sticker]" and "[Encrypted]"
                // are labels this app made up, not content.  Replying works on
                // anything, so the gestures sit on every kind of bubble.
                val body = message.text
                    ?.takeIf { message.contentType == ContentType.TEXT && it.isNotEmpty() }
                var menuOpen by remember(message.id) { mutableStateOf(false) }
                val sticker = message.stickerUrl

                Box {
                    when {
                        message.isDownloadableMedia -> MediaBubble(
                            message = message,
                            state = mediaState(message),
                            onRequest = { onRequestMedia(message) },
                            onRetry = { onRetryMedia(message) },
                            onOpen = { onOpenMedia(message) },
                            onDoubleTap = { onReply(message) },
                            onLongPress = { menuOpen = true },
                        )

                        sticker != null -> Box(
                            Modifier.combinedClickable(
                                onClick = {},
                                onDoubleClick = { onReply(message) },
                                onLongClick = { menuOpen = true },
                            )
                        ) {
                            // Stickers carry their own shape and transparency,
                            // so a bubble behind one just boxes it in.
                            AsyncImage(
                                model = sticker,
                                contentDescription = "Sticker",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.size(140.dp),
                            )
                        }

                        else -> Surface(
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = bubbleShape(mine),
                            modifier = Modifier
                                .widthIn(max = 280.dp)
                                .combinedClickable(
                                    onClick = {},
                                    onDoubleClick = { onReply(message) },
                                    onLongClick = { menuOpen = true },
                                ),
                        ) {
                            Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                if (message.relationType == MessageRelationType.REPLY) {
                                    QuotedMessage(
                                        original = message.relatedMessageId?.let(originalOf),
                                        nameOf = nameOf,
                                        mine = mine,
                                    )
                                }
                                Text(
                                    text = message.preview,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (mine) MaterialTheme.colorScheme.onPrimaryContainer
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }

                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Reply") },
                            onClick = { menuOpen = false; onReply(message) },
                        )
                        if (body != null) {
                            DropdownMenuItem(
                                text = { Text("Copy") },
                                onClick = { menuOpen = false; onCopy(body) },
                            )
                            DropdownMenuItem(
                                text = { Text("Share") },
                                onClick = { menuOpen = false; onShare(body) },
                            )
                        }
                    }
                }

                if (!mine) {
                    Spacer(Modifier.width(6.dp))
                    TimeLabel(message.createdTime)
                }
            }
        }
    }
}

/**
 * The message being replied to, shown inside the reply.
 *
 * A quoted original may not be loaded — replies reach far back, and only the
 * recent window is held — so say that rather than drawing an empty block.
 */
@Composable
private fun QuotedMessage(original: Message?, nameOf: (String?) -> String, mine: Boolean) {
    val onBubble = if (mine) MaterialTheme.colorScheme.onPrimaryContainer
    else MaterialTheme.colorScheme.onSurface

    Row(
        Modifier
            .padding(bottom = 6.dp)
            .heightIn(max = 56.dp)
            .widthIn(max = 260.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(onBubble.copy(alpha = 0.5f))
        )
        Column(Modifier.padding(start = 8.dp)) {
            Text(
                text = original?.sender?.let(nameOf) ?: "Reply",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = onBubble.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = original?.preview ?: "Original message not loaded",
                style = MaterialTheme.typography.bodySmall,
                color = onBubble.copy(alpha = 0.7f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The tail corner points at the sender, the rest stay generously rounded. */
private fun bubbleShape(mine: Boolean) = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = if (mine) 20.dp else 6.dp,
    bottomEnd = if (mine) 6.dp else 20.dp,
)

@Composable
private fun TimeLabel(ms: Long?) {
    Text(
        text = formatClock(ms),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

@Composable
private fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    sendingImage: Boolean,
    onPickImage: (Uri) -> Unit,
    replyingTo: Message?,
    replyingToName: String,
    onCancelReply: () -> Unit,
    onSend: () -> Unit,
) {
    // The system photo picker needs no storage permission and gives access to
    // exactly the one item chosen, so there is nothing to request here.  Two
    // launchers rather than one: the picker filters by what it is asked for,
    // and separate buttons say plainly which is which.
    // Choosing Reply should leave you able to type straight away.
    val composerFocus = remember { FocusRequester() }
    LaunchedEffect(replyingTo) {
        if (replyingTo != null) runCatching { composerFocus.requestFocus() }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(onPickImage) }
    val videoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> uri?.let(onPickImage) }

    Column(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .navigationBarsPadding()
            .imePadding()
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        if (replyingTo != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 8.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(3.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.primary)
                )
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(
                        text = "Replying to $replyingToName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = replyingTo.preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onCancelReply) {
                    Icon(Icons.Filled.Clear, contentDescription = "Cancel reply")
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            // The spinner replaces whichever button is not in use, so an upload
            // in progress is obvious without the row jumping about.
            IconButton(
                onClick = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !sendingImage,
                modifier = Modifier.size(48.dp),
            ) {
                if (sendingImage) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Icon(painterResource(R.drawable.ic_photo), contentDescription = "Send a photo")
                }
            }
            IconButton(
                onClick = {
                    videoPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                    )
                },
                enabled = !sendingImage,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_videocam), contentDescription = "Send a video")
            }
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Message") },
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(24.dp))
                    .focusRequester(composerFocus),
            )
            Spacer(Modifier.width(8.dp))
            FilledIconButton(
                onClick = onSend,
                enabled = value.isNotBlank(),
                modifier = Modifier.size(52.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
