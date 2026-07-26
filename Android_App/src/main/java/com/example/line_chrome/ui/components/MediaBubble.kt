package com.example.line_chrome.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.line_chrome.data.LineRepository.MediaState
import com.example.line_chrome.line.ContentType
import com.example.line_chrome.line.Message

/**
 * An image or video in a conversation.
 *
 * The thumbnail is fetched lazily when the bubble first composes — media is not
 * part of the message, it has to be pulled from object storage by message id,
 * and downloading a whole conversation's worth up front would be wasteful.
 */
@Composable
fun MediaBubble(
    message: Message,
    state: MediaState?,
    onRequest: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
) {
    LaunchedEffect(message.id) { onRequest() }

    val isVideo = message.contentType == ContentType.VIDEO

    Box(
        modifier = Modifier
            .size(width = 220.dp, height = 160.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(enabled = state != null && state !is MediaState.Loading) {
                if (state is MediaState.Failed) onRetry() else onOpen()
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is MediaState.Ready -> {
                AsyncImage(
                    model = state.file,
                    contentDescription = if (isVideo) "Video" else "Image",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                if (isVideo) PlayBadge()
            }

            // No thumbnail, but the object is still there to open — say so
            // rather than leaving an empty rectangle.
            is MediaState.NoPreview -> if (isVideo) {
                PlayBadge()
            } else {
                Text("Tap to view", style = MaterialTheme.typography.labelMedium)
            }

            is MediaState.Failed -> RetryHint(state.reason)

            else -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
}

@Composable
private fun PlayBadge() {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Composable
private fun RetryHint(reason: String) {
    Column(
        Modifier.padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(6.dp))
            Text("Tap to retry", style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
        )
    }
}
