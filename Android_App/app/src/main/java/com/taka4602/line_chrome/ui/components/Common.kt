package com.taka4602.line_chrome.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.taka4602.line_chrome.ui.theme.AvatarTints
import java.util.Calendar
import kotlin.math.absoluteValue

/**
 * Put text on the clipboard.
 *
 * @return whether the caller should confirm it.  Android 13 and up shows its
 *   own copy confirmation, so a second one of ours would just be noise.
 */
fun copyToClipboard(context: Context, label: String, text: String): Boolean {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return false
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
}

/** Hand text to the share sheet. */
fun shareText(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(intent, null)) }
}

/** LINE serves profile pictures from its own CDN, keyed by the path in a Contact. */
fun lineProfileUrl(picturePath: String?): String? {
    val path = picturePath?.takeIf { it.isNotBlank() } ?: return null
    return if (path.startsWith("http")) path else "https://profile.line-scdn.net$path"
}

/**
 * Profile picture, or the first character on a tint derived from the MID, so
 * the same person keeps the same colour between launches.
 */
@Composable
fun Avatar(
    name: String,
    picturePath: String?,
    mid: String,
    size: Dp = 52.dp,
    isGroup: Boolean = false,
) {
    // Groups get a squircle and people get a circle, so the shape alone says
    // which kind of row you are looking at.
    val shape = if (isGroup) RoundedCornerShape(size / 3) else CircleShape
    val url = lineProfileUrl(picturePath)
    val tint = AvatarTints[mid.hashCode().absoluteValue % AvatarTints.size]

    Box(
        modifier = Modifier.size(size).clip(shape).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        } else {
            Text(
                text = name.firstOrNull()?.uppercase() ?: "?",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = (size.value / 2.4f).sp,
            )
        }
    }
}

/** Today shows a clock time, this year a date, older the full date. */
fun formatTimestamp(ms: Long?): String {
    if (ms == null || ms == 0L) return ""
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()

    return when {
        then.sameDayAs(now) -> "%02d:%02d".format(
            then.get(Calendar.HOUR_OF_DAY), then.get(Calendar.MINUTE)
        )

        then.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "%d/%d".format(
            then.get(Calendar.MONTH) + 1, then.get(Calendar.DAY_OF_MONTH)
        )

        else -> "%d/%d/%d".format(
            then.get(Calendar.YEAR), then.get(Calendar.MONTH) + 1, then.get(Calendar.DAY_OF_MONTH)
        )
    }
}

/** Clock time only — used on message bubbles, where the day is a separator. */
fun formatClock(ms: Long?): String {
    if (ms == null || ms == 0L) return ""
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
}

/** Day separator label inside a conversation. */
fun formatDayLabel(ms: Long?): String {
    if (ms == null || ms == 0L) return ""
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    return when {
        then.sameDayAs(now) -> "Today"
        then.sameDayAs(yesterday) -> "Yesterday"
        else -> "%d/%d/%d".format(
            then.get(Calendar.YEAR), then.get(Calendar.MONTH) + 1, then.get(Calendar.DAY_OF_MONTH)
        )
    }
}

/** True when two timestamps fall on different calendar days. */
fun crossesDay(previous: Long?, current: Long?): Boolean {
    if (current == null) return false
    if (previous == null) return true
    val a = Calendar.getInstance().apply { timeInMillis = previous }
    val b = Calendar.getInstance().apply { timeInMillis = current }
    return !a.sameDayAs(b)
}

private fun Calendar.sameDayAs(other: Calendar): Boolean =
    get(Calendar.YEAR) == other.get(Calendar.YEAR) &&
        get(Calendar.DAY_OF_YEAR) == other.get(Calendar.DAY_OF_YEAR)

@Composable
fun EmptyState(title: String, subtitle: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
            )
        }
    }
}
