package com.taka4602.line_chrome.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.app.Person
import com.taka4602.line_chrome.MainActivity
import com.taka4602.line_chrome.R
import com.taka4602.line_chrome.line.ChatSummary
import com.taka4602.line_chrome.line.Message

/**
 * Incoming-message notifications, one per chat, using MessagingStyle so a busy
 * chat stacks its lines instead of replacing them.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    /** Per-chat history, so MessagingStyle can show the last few lines. */
    private val history = mutableMapOf<String, MutableList<NotificationCompat.MessagingStyle.Message>>()
    private val ids = mutableMapOf<String, Int>()

    // Guarded by hasPermission() on the next line, which lint cannot follow
    // across the call.
    @SuppressLint("MissingPermission")
    fun notifyMessage(chat: ChatSummary, msg: Message, senderName: String) {
        if (!hasPermission()) return

        val person = Person.Builder().setName(senderName).setKey(msg.sender ?: senderName).build()
        val line = NotificationCompat.MessagingStyle.Message(
            msg.preview.ifEmpty { "(empty)" },
            msg.createdTime ?: System.currentTimeMillis(),
            person,
        )

        val lines = history.getOrPut(chat.chatMid) { mutableListOf() }
        lines += line
        while (lines.size > MAX_LINES) lines.removeAt(0)

        val style = NotificationCompat.MessagingStyle(
            Person.Builder().setName("You").build()
        ).setConversationTitle(chat.name.takeIf { chat.isGroup })
            .setGroupConversation(chat.isGroup)
        lines.forEach { style.addMessage(it) }

        val open = PendingIntent.getActivity(
            context,
            chat.chatMid.hashCode(),
            Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .putExtra(MainActivity.EXTRA_CHAT_MID, chat.chatMid)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val id = ids.getOrPut(chat.chatMid) { chat.chatMid.hashCode() and 0x7FFFFFFF }
        val notification = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notification)
            .setStyle(style)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(GROUP_KEY)
            .setOnlyAlertOnce(false)
            .build()

        runCatching { manager.notify(id, notification) }
    }

    fun clearChat(chatMid: String) {
        history.remove(chatMid)
        ids.remove(chatMid)?.let { manager.cancel(it) }
    }

    /**
     * Both halves matter: from API 33 posting needs the runtime permission, and
     * on every version the user can still switch notifications off outright.
     */
    private fun hasPermission(): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        return granted && manager.areNotificationsEnabled()
    }

    companion object {
        const val CHANNEL_MESSAGES = "messages"
        const val CHANNEL_SERVICE = "service"
        private const val GROUP_KEY = "com.taka4602.line_chrome.MESSAGES"
        private const val MAX_LINES = 6

        /** The ongoing-service notification; fixed id so the service can update it. */
        const val SERVICE_NOTIFICATION_ID = 1

        fun createChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_MESSAGES, "Messages", NotificationManager.IMPORTANCE_HIGH
                ).apply { description = "Incoming LINE messages" }
            )
            manager.createNotificationChannel(
                // Low importance: this one is only present because Android
                // requires a visible notification for a foreground service.
                NotificationChannel(
                    CHANNEL_SERVICE, "Connection", NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Keeps the connection to LINE open"
                    setShowBadge(false)
                }
            )
        }

        fun serviceNotification(context: Context, text: String): Notification {
            val open = PendingIntent.getActivity(
                context, 0, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("LINE Chrome")
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
        }
    }
}
