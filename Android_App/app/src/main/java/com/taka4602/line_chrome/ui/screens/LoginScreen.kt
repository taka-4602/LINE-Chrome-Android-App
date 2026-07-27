package com.taka4602.line_chrome.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.taka4602.line_chrome.data.LineRepository.AuthState

@Composable
fun LoginScreen(
    state: AuthState,
    savedEmail: String?,
    onEmailLogin: (String, String, Boolean) -> Unit,
    onTokenLogin: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    var email by remember { mutableStateOf(savedEmail.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var showTokenField by remember { mutableStateOf(false) }
    var rememberMe by remember { mutableStateOf(true) }

    val busy = state is AuthState.Connecting || state is AuthState.PinRequired

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))

        Box(
            modifier = Modifier.size(76.dp),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                shape = CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.size(76.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        Text(
            "LINE Chrome",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Signs in as a desktop client, so your phone session stays put.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 6.dp),
        )

        Spacer(Modifier.height(32.dp))

        AnimatedVisibility(visible = state is AuthState.PinRequired) {
            val pin = (state as? AuthState.PinRequired)?.pincode.orEmpty()
            PinCard(pin)
        }

        AnimatedVisibility(visible = state is AuthState.Failed) {
            val message = (state as? AuthState.Failed)?.message.orEmpty()
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = onDismissError, modifier = Modifier.align(Alignment.End)) {
                        Text("Dismiss")
                    }
                }
            }
        }

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email, imeAction = ImeAction.Next
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Done
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy) { rememberMe = !rememberMe },
        ) {
            Checkbox(checked = rememberMe, onCheckedChange = { rememberMe = it }, enabled = !busy)
            Column(Modifier.padding(start = 4.dp)) {
                Text("Stay signed in", style = MaterialTheme.typography.bodyMedium)
                Text(
                    // LINE expires the token every few days, so without this the
                    // app cannot get back in on its own.
                    "Stores your password on this device, encrypted by the Android " +
                        "keystore, so the app can sign in again when LINE expires " +
                        "the session.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = { onEmailLogin(email.trim(), password, rememberMe) },
            enabled = !busy && email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                Text("Sign in", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = { showTokenField = !showTokenField }, enabled = !busy) {
            Text(if (showTokenField) "Hide advanced" else "Use an existing access token")
        }

        AnimatedVisibility(visible = showTokenField) {
            Column(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Access token") },
                    enabled = !busy,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "A token from the Python client works here, but it carries no " +
                        "E2EE key — sealed chats will stay unreadable until you sign " +
                        "in with a password and confirm the PIN.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = { onTokenLogin(token) },
                    enabled = !busy && token.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                ) {
                    Text("Continue with token")
                }
            }
        }

        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun PinCard(pincode: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Enter this code on your phone",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Row(
                modifier = Modifier.padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                pincode.forEach { digit ->
                    Text(
                        digit.toString(),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                "Open LINE on your primary device and tap approve. This is what " +
                    "hands over the encryption key, so it only happens once.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
            )
        }
    }
}
