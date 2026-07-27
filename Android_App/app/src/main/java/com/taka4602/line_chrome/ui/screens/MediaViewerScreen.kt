package com.taka4602.line_chrome.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import coil3.compose.AsyncImage
import com.taka4602.line_chrome.data.LineRepository.MediaState
import com.taka4602.line_chrome.line.ContentType
import com.taka4602.line_chrome.line.Message
import java.io.File

/**
 * Full-screen view of an attachment.
 *
 * Images are shown here directly. Video is handed to whatever the device
 * already uses to play video — bundling a player for a client this size would
 * be a lot of weight for one screen.
 */
@Composable
fun MediaViewerScreen(
    message: Message,
    state: MediaState?,
    onRequest: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val isVideo = message.contentType == ContentType.VIDEO

    LaunchedEffect(message.id) { onRequest() }

    fun openExternally(file: File) {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.media", file
        )
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, if (isVideo) "video/*" else "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { context.startActivity(intent) }
    }

    // Video is downloaded whole and handed straight to a player rather than
    // being displayed here.
    LaunchedEffect(state) {
        if (isVideo && state is MediaState.Ready) openExternally(state.file)
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black).clickable(enabled = !isVideo) { onClose() },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is MediaState.Ready -> if (isVideo) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(56.dp),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Opening in your video player…", color = Color.White)
                    Spacer(Modifier.height(20.dp))
                    Button(onClick = { openExternally(state.file) }) { Text("Play again") }
                }
            } else {
                AsyncImage(
                    model = state.file,
                    contentDescription = "Image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            is MediaState.Failed -> Column(
                Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Could not download this attachment",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.reason,
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onClose) { Text("Close") }
            }

            else -> CircularProgressIndicator(color = Color.White)
        }

        Row(
            // Without an explicit alignment this inherits the Box's centre and
            // lands in the middle of the image.  The gradient goes on before
            // the insets so it reaches up behind the status bar, and keeps the
            // icons legible over a light photo.
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Clear, contentDescription = "Close", tint = Color.White)
            }
            val ready = state as? MediaState.Ready
            if (ready != null) {
                IconButton(onClick = { openExternally(ready.file) }) {
                    Icon(Icons.Filled.Share, contentDescription = "Open", tint = Color.White)
                }
            } else {
                Spacer(Modifier.width(48.dp))
            }
        }
    }
}
