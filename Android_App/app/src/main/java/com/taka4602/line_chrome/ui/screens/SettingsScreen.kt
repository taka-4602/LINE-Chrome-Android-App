package com.taka4602.line_chrome.ui.screens

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.taka4602.line_chrome.data.LineRepository
import com.taka4602.line_chrome.data.LineRepository.Connection
import com.taka4602.line_chrome.data.PollingInterval
import com.taka4602.line_chrome.line.Profile
import com.taka4602.line_chrome.service.PollingService
import com.taka4602.line_chrome.ui.components.Avatar
import com.taka4602.line_chrome.ui.components.formatTimestamp
import kotlin.system.exitProcess

@Composable
fun SettingsScreen(
    profile: Profile?,
    contentPadding: PaddingValues,
    notificationsEnabled: Boolean,
    connection: Connection,
    onNotificationsChanged: (Boolean) -> Unit,
    onLogout: () -> Unit,
) {
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmQuit by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.padding(top = 8.dp))

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(
                    name = profile?.displayName ?: "?",
                    picturePath = profile?.picturePath,
                    mid = profile?.mid ?: "",
                    size = 60.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = profile?.displayName ?: "Not signed in",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    profile?.statusMessage?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    profile?.mid?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.padding(top = 20.dp))

        Text(
            "Notifications",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Notify on new messages", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "The connection stays open either way — this only controls " +
                        "whether messages raise a notification.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = notificationsEnabled, onCheckedChange = onNotificationsChanged)
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(
            "Connection",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        ConnectionDetail(connection)

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        PollingSection()

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(
            "Sign-in",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        var hasPassword by remember { mutableStateOf(LineRepository.canReloginUnattended) }
        var autoRelogin by remember { mutableStateOf(LineRepository.autoReloginEnabled) }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Sign in again automatically", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = if (hasPassword) {
                        "LINE expires the session every few days (hours?). With this on the " +
                            "app signs itself back in — including while it is in the " +
                            "background, so message notifications keep arriving."
                    } else {
                        "No password saved. Sign out and sign in again with " +
                            "\"Stay signed in\" ticked to use this."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = autoRelogin && hasPassword,
                enabled = hasPassword,
                onCheckedChange = {
                    autoRelogin = it
                    LineRepository.autoReloginEnabled = it
                },
            )
        }

        if (hasPassword) {
            LineRepository.savedEmail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Turning the switch off only stops the password being used;
            // this is how you actually get rid of it.
            TextButton(onClick = {
                LineRepository.forgetCredentials()
                hasPassword = false
            }) {
                Text("Forget saved password")
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 16.dp))

        Text(
            "About",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            "Unofficial client built by reverse-engineering LINE's desktop " +
                "protocol. It violates LINE's terms of service and can get the " +
                "account restricted or banned. Sealed group chats can be read " +
                "and written, but sealed media is receive-only.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.padding(top = 24.dp))

        OutlinedButton(
            onClick = { confirmLogout = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Sign out")
        }

        Spacer(Modifier.padding(top = 12.dp))

        OutlinedButton(
            onClick = { confirmQuit = true },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error
            ),
        ) {
            Text("Quit app")
        }
        Text(
            "Stops the connection and closes the app. You stay signed in, but " +
                "nothing arrives until you open it again.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        Spacer(Modifier.padding(top = 32.dp))
    }

    ConfirmLogoutDialog(confirmLogout, { confirmLogout = false }, onLogout)

    if (confirmQuit) {
        AlertDialog(
            onDismissRequest = { confirmQuit = false },
            title = { Text("Quit the app?") },
            text = {
                Text(
                    "The connection closes and no messages or notifications " +
                        "arrive until you open the app again. You stay signed in."
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmQuit = false; quitApp(context) }) {
                    Text("Quit")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmQuit = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Every cadence the poller runs on, editable, defaulting to the shipped values.
 *
 * There is no "apply" and no restart: the loops read each value once per tick,
 * so a change lands on the next one.  The bounds live on [PollingInterval]
 * rather than here — this only has to refuse to save what they reject.
 */
@Composable
private fun PollingSection() {
    val polling = LineRepository.sessionStore.polling
    val context = LocalContext.current
    // The store is not observable, so every row would keep showing the value it
    // was composed with.  Bumping this after a write is what re-reads them.
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<PollingInterval?>(null) }

    val seconds = remember(revision) { PollingInterval.entries.map { polling.seconds(it) } }
    val overridden = remember(revision) { polling.anyOverridden }
    val delivering = remember(revision) { polling.deliveryEnabled }

    // The service is what runs these loops, so it has to be told.  Turning the
    // last delivery path off leaves it nothing to do, and it should not sit in
    // the notification shade claiming otherwise.
    val applied: () -> Unit = {
        revision++
        if (LineRepository.auth.value is LineRepository.AuthState.LoggedIn) {
            if (polling.deliveryEnabled) {
                PollingService.start(context)
            } else {
                PollingService.stop(context)
                // stop() alone would leave the Connection section showing
                // whatever was true before this edit.
                LineRepository.setConnection(
                    LineRepository.Connection.Degraded(PollingService.OFF_REASON)
                )
            }
        }
    }

    Text(
        "Polling",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    Text(
        "How often the app asks LINE for anything new. Shorter waits mean " +
            "messages land sooner and more requests go out — LINE restricts " +
            "accounts that poll hard, so the defaults stay well clear of it. " +
            "Set one to 0 to turn it off.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    if (!delivering) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Text(
                "Every way of receiving messages is off, so nothing arrives at " +
                    "all and the connection is stopped. Set the long-poll floor, " +
                    "the fallback check or the safety-net check back above 0.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(12.dp),
            )
        }
    }

    PollingInterval.entries.forEachIndexed { index, interval ->
        PollingIntervalRow(interval, seconds[index]) { editing = interval }
    }

    if (overridden) {
        TextButton(onClick = { polling.resetAll(); applied() }) {
            Text("Restore all defaults")
        }
    }

    editing?.let { interval ->
        PollingIntervalDialog(
            interval = interval,
            current = seconds[interval.ordinal],
            onDismiss = { editing = null },
            onSave = { polling.set(interval, it); applied(); editing = null },
            onReset = { polling.reset(interval); applied(); editing = null },
        )
    }
}

@Composable
private fun PollingIntervalRow(
    interval: PollingInterval,
    seconds: Int,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(interval.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                // At 0 the normal description describes something that is not
                // happening, so say what 0 does instead.
                if (seconds == 0) interval.zeroDescription else interval.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (seconds == 0) interval.zeroLabel else formatInterval(seconds),
                style = MaterialTheme.typography.titleMedium,
                color = if (seconds == 0) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
            )
            if (seconds != interval.defaultSeconds) {
                Text(
                    "default ${formatInterval(interval.defaultSeconds)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PollingIntervalDialog(
    interval: PollingInterval,
    current: Int,
    onDismiss: () -> Unit,
    onSave: (Int) -> Unit,
    onReset: () -> Unit,
) {
    var text by remember { mutableStateOf(current.toString()) }
    val parsed = text.toIntOrNull()
    // 0 is always legal and sits below minSeconds on purpose — it is the off
    // switch, not a cadence.
    val valid = parsed != null &&
        (parsed == 0 || (parsed >= interval.minSeconds && parsed <= interval.maxSeconds))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(interval.label) },
        text = {
            Column {
                Text(
                    interval.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 8.dp))
                Text(
                    "0 — ${interval.zeroLabel.lowercase()}: ${interval.zeroDescription}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 12.dp))
                OutlinedTextField(
                    // Digits only, so the value can never be out of range for a
                    // reason the supporting text does not explain.
                    value = text,
                    onValueChange = { text = it.filter(Char::isDigit).take(5) },
                    singleLine = true,
                    isError = text.isNotEmpty() && !valid,
                    label = { Text("Seconds") },
                    supportingText = {
                        Text(
                            "0, or ${interval.minSeconds}–${interval.maxSeconds}s · " +
                                "default ${interval.defaultSeconds}s"
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextButton(onClick = onReset) { Text("Restore default") }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { parsed?.let(onSave) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Minutes once seconds stop reading as a duration anyone can picture. */
private fun formatInterval(seconds: Int): String = when {
    seconds < 60 -> "${seconds}s"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60}s"
}

/**
 * Stop everything and leave.
 *
 * The service is stopped first and through the framework, so its started state
 * is cleared before the process goes: a foreground service that is merely killed
 * is restarted by Android, which would put the notification straight back.
 */
private fun quitApp(context: Context) {
    PollingService.stop(context)
    context.findActivity()?.finishAndRemoveTask()
    exitProcess(0)
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * The live state of message delivery, with the raw probe output.
 *
 * This exists to be copied into a bug report: when the long-poll route is
 * wrong, the per-candidate errors name the endpoint and method the server
 * rejected, which is the only thing that identifies the right one.
 */
@Composable
private fun ConnectionDetail(connection: Connection) {
    val context = LocalContext.current

    val (label, detail) = when (connection) {
        is Connection.Live -> {
            val mode = if (connection.shortPolling) "Connected, checking every few seconds"
            else "Connected, live"
            mode + connection.route?.let { " — $it" }.orEmpty() to null
        }

        is Connection.Degraded -> "Poll unavailable — checking on a timer" to connection.reason
        is Connection.Idle -> "Not connected" to null
    }

    val lastCheck by LineRepository.lastCheck.collectAsState()

    Text(label, style = MaterialTheme.typography.bodyLarge)
    Text(
        text = if (lastCheck == 0L) {
            "Background check has not run yet"
        } else {
            "Last background check: ${formatTimestamp(lastCheck)}"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (detail != null) {
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            ?.setPrimaryClip(ClipData.newPlainText("LINE poll diagnostics", detail))
                    },
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("Copy")
                }
            }
        }
    }
}

@Composable
private fun ConfirmLogoutDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    onLogout: () -> Unit,
) {
    if (visible) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Sign out?") },
            text = {
                Text(
                    "This also deletes the stored E2EE key. Signing back in will " +
                        "need another PIN confirmation on your phone, and messages " +
                        "sealed with the old key become unreadable."
                )
            },
            confirmButton = {
                TextButton(onClick = { onDismiss(); onLogout() }) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            },
        )
    }
}
