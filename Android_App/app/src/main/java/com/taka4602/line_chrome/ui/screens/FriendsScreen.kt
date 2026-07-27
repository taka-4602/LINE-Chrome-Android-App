package com.taka4602.line_chrome.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.taka4602.line_chrome.line.Chat
import com.taka4602.line_chrome.line.ChatType
import com.taka4602.line_chrome.line.Contact
import com.taka4602.line_chrome.ui.components.Avatar
import com.taka4602.line_chrome.ui.components.EmptyState

/**
 * Friends and groups in one list.
 *
 * Tapping opens the profile rather than the conversation: this is the directory,
 * and someone looking through it is at least as likely to want to know who a
 * contact is as to start typing at them.  The profile carries a send button for
 * when they do.
 */
@Composable
fun FriendsScreen(
    contacts: List<Contact>,
    groups: List<Chat>,
    contentPadding: PaddingValues,
    onOpenProfile: (mid: String) -> Unit,
) {
    var query by remember { mutableStateOf("") }

    val filteredContacts = remember(contacts, query) {
        if (query.isBlank()) contacts
        else contacts.filter { it.name.contains(query, ignoreCase = true) }
    }
    val filteredGroups = remember(groups, query) {
        if (query.isBlank()) groups
        else groups.filter { (it.chatName ?: "").contains(query, ignoreCase = true) }
    }

    if (contacts.isEmpty() && groups.isEmpty()) {
        EmptyState(
            title = "No friends loaded",
            subtitle = "Tap refresh to fetch your contact list from LINE.",
            modifier = Modifier.fillMaxSize().padding(contentPadding),
        )
        return
    }

    LazyColumn(contentPadding = contentPadding) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (filteredGroups.isNotEmpty()) {
            item { SectionHeader("Groups", filteredGroups.size) }
            items(filteredGroups, key = { it.chatMid }) { group ->
                PersonRow(
                    name = group.chatName ?: "(no name)",
                    subtitle = if (group.chatType == ChatType.ROOM) "Room" else "Group",
                    mid = group.chatMid,
                    picturePath = group.picturePath,
                    isGroup = true,
                    onClick = { onOpenProfile(group.chatMid) },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
        }

        if (filteredContacts.isNotEmpty()) {
            item { SectionHeader("Friends", filteredContacts.size) }
            items(filteredContacts, key = { it.mid }) { contact ->
                PersonRow(
                    name = contact.name,
                    subtitle = contact.statusMessage.orEmpty(),
                    mid = contact.mid,
                    picturePath = contact.picturePath,
                    isGroup = false,
                    onClick = { onOpenProfile(contact.mid) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Text(
        text = "$title  $count",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PersonRow(
    name: String,
    subtitle: String,
    mid: String,
    picturePath: String?,
    isGroup: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(name = name, picturePath = picturePath, mid = mid, size = 44.dp, isGroup = isGroup)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
