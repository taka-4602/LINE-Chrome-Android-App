package com.example.line_chrome.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.line_chrome.line.ChatSummary
import com.example.line_chrome.ui.components.Avatar
import com.example.line_chrome.ui.components.EmptyState
import com.example.line_chrome.ui.components.formatTimestamp

@Composable
fun ChatsScreen(
    chats: List<ChatSummary>,
    contentPadding: PaddingValues,
    /**
     * The chat the detail pane is showing, when there is one.  Null when the
     * list is the whole screen — nothing is "selected" if opening a row
     * replaces the list outright.
     */
    selectedMid: String? = null,
    onOpenChat: (ChatSummary) -> Unit,
) {
    if (chats.isEmpty()) {
        EmptyState(
            title = "No conversations yet",
            subtitle = "Chats appear here once there is a message in them. Pull the " +
                "refresh button above if you expected something.",
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        )
        return
    }

    LazyColumn(contentPadding = contentPadding) {
        items(chats, key = { it.chatMid }) { chat ->
            ChatRow(
                chat = chat,
                selected = chat.chatMid == selectedMid,
                onClick = { onOpenChat(chat) },
            )
        }
    }
}

@Composable
private fun ChatRow(chat: ChatSummary, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            // Marks the row the other pane is showing.  The click ripple is not
            // enough on its own — it is gone by the time anyone looks.
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHighest
                else Color.Transparent
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(
            name = chat.name,
            picturePath = chat.picturePath,
            mid = chat.chatMid,
            isGroup = chat.isGroup,
        )

        Spacer(Modifier.width(14.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = chat.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (chat.unreadCount > 0) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = formatTimestamp(chat.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(2.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // In a group the sender matters; in a 1:1 it is redundant
                    // with the row's own name, except when it is you.
                    text = buildPreview(chat),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (chat.unreadCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Badge { Text(chat.unreadCount.coerceAtMost(99).toString()) }
                }
            }
        }
    }
}

private fun buildPreview(chat: ChatSummary): String {
    val prefix = when {
        chat.senderName == "You" -> "You: "
        chat.isGroup && chat.senderName != null -> "${chat.senderName}: "
        else -> ""
    }
    return prefix + chat.preview
}
