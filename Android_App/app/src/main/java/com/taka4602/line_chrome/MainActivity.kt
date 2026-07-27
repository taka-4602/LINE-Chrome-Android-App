package com.taka4602.line_chrome

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.taka4602.line_chrome.data.LineRepository
import com.taka4602.line_chrome.service.PollingService
import com.taka4602.line_chrome.ui.AppRoot
import com.taka4602.line_chrome.ui.theme.LineChromeTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingChatMid by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark only, so both system bars get the dark treatment regardless of
        // the device setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        LineRepository.init(this)
        pendingChatMid = intent?.getStringExtra(EXTRA_CHAT_MID)
        askForNotifications()

        // The poller only makes sense once there is a session, and it has to
        // outlive this Activity — hence a service rather than a coroutine here.
        lifecycleScope.launch {
            LineRepository.auth.collectLatest { state ->
                when (state) {
                    is LineRepository.AuthState.LoggedIn -> PollingService.start(this@MainActivity)
                    // Failed means recovery gave up and the user has to sign in
                    // again; leaving the service running would just spin.
                    is LineRepository.AuthState.LoggedOut,
                    is LineRepository.AuthState.Failed,
                    -> PollingService.stop(this@MainActivity)
                    else -> Unit
                }
            }
        }

        setContent {
            LineChromeTheme {
                AppRoot(
                    pendingChatMid = pendingChatMid,
                    onPendingChatConsumed = { pendingChatMid = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingChatMid = intent.getStringExtra(EXTRA_CHAT_MID)
    }

    override fun onPause() {
        super.onPause()
        // While the app is backgrounded every chat counts as closed, so an
        // incoming message notifies instead of being silently folded in.
        // AppRoot claims it back on RESUMED — this is only half the handshake.
        LineRepository.openChatMid = null
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val EXTRA_CHAT_MID = "chat_mid"
    }
}
