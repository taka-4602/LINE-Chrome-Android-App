package com.example.line_chrome.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.line_chrome.data.LineRepository.Connection

/**
 * Shown when the long-poll is down and delivery has fallen back to periodic
 * refreshes.
 *
 * Tapping reveals the underlying error and long-pressing copies it, because
 * otherwise the only symptom is "messages are late" — which is not something
 * anyone can report usefully, and the detail here names the exact endpoint and
 * method the server rejected.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConnectionBanner(connection: Connection, modifier: Modifier = Modifier) {
    val degraded = connection as? Connection.Degraded

    AnimatedVisibility(visible = degraded != null, modifier = modifier) {
        var expanded by remember { mutableStateOf(false) }
        val context = LocalContext.current
        val detail = degraded?.reason.orEmpty()

        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { expanded = !expanded },
                    onLongClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("LINE poll error", detail))
                    },
                ),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text(
                    text = "Live updates unavailable — checking periodically instead",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = if (expanded) detail else "Tap for details",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (expanded) {
                    Text(
                        text = "Long-press to copy",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}
