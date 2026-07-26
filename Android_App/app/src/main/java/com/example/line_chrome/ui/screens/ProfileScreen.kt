package com.example.line_chrome.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.line_chrome.data.LineRepository.ProfileInfo
import com.example.line_chrome.ui.components.Avatar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    mid: String,
    profile: ProfileInfo?,
    participants: List<String>,
    nameOf: (String?) -> String,
    pictureOf: (String?) -> String?,
    onLoad: (String) -> Unit,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
) {
    LaunchedEffect(mid) { onLoad(mid) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ),
            )
        },
    ) { padding ->
        if (profile == null) {
            Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))

            Avatar(
                name = profile.name,
                picturePath = profile.picturePath,
                mid = profile.mid,
                size = 128.dp,
                isGroup = profile.isGroup,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = profile.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            // LINE shows a nickname you set over the name they chose, which is
            // confusing when the two differ — so say both.
            val realName = profile.realName
            if (profile.nickname != null && realName != null && realName != profile.name) {
                Text(
                    text = "Their own name: $realName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            if (profile.isSelf) {
                Text(
                    text = "This is you",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            profile.statusMessage?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            Spacer(Modifier.height(24.dp))

            if (!profile.isSelf) {
                Button(
                    onClick = { onOpenChat(profile.mid) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Email, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (profile.isGroup) "Open chat" else "Send message")
                }
            }

            Spacer(Modifier.height(24.dp))
            MidRow(profile.mid)

            if (profile.isGroup) {
                Spacer(Modifier.height(8.dp))
                Participants(
                    participants = participants,
                    nameOf = nameOf,
                    pictureOf = pictureOf,
                    onOpenProfile = onOpenProfile,
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun MidRow(mid: String) {
    val context = LocalContext.current

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "MID",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = mid,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TextButton(onClick = {
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("MID", mid))
            }) {
                Text("Copy")
            }
        }
    }
}

@Composable
private fun Participants(
    participants: List<String>,
    nameOf: (String?) -> String,
    pictureOf: (String?) -> String?,
    onOpenProfile: (String) -> Unit,
) {
    if (participants.isEmpty()) return

    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(
            text = "People in this conversation",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        // Deliberately not called a member list: LINE exposes no roster call,
        // so this is only who has actually spoken in the messages loaded.
        Text(
            text = "Everyone who has sent a message here — not the full member list.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        )

        participants.forEach { mid ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenProfile(mid) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start,
            ) {
                Avatar(
                    name = nameOf(mid),
                    picturePath = pictureOf(mid),
                    mid = mid,
                    size = 40.dp,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = nameOf(mid),
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
