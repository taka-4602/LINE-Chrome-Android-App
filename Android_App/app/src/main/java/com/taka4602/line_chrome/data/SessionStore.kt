package com.taka4602.line_chrome.data

import android.content.Context

/**
 * Access token, refresh token and the login email, in app-private storage.
 *
 * The E2EE private keys live separately under `filesDir` in the layout the
 * Python client uses — see `E2eeStore`.
 */
class SessionStore(context: Context) {

    private val prefs = context.getSharedPreferences("line_session", Context.MODE_PRIVATE)

    var accessToken: String?
        get() = prefs.getString(KEY_ACCESS, null)
        set(v) = prefs.edit().putString(KEY_ACCESS, v).apply()

    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH, null)
        set(v) = prefs.edit().putString(KEY_REFRESH, v).apply()

    var email: String?
        get() = prefs.getString(KEY_EMAIL, null)
        set(v) = prefs.edit().putString(KEY_EMAIL, v).apply()

    /** Notifications stay on unless the user turns them off in Settings. */
    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY, true)
        set(v) = prefs.edit().putBoolean(KEY_NOTIFY, v).apply()

    /**
     * Whether a dead session may be rebuilt from the stored password without
     * asking.  Separate from whether a password exists, so turning this off
     * does not throw the password away — and turning it back on does not mean
     * typing it again.
     */
    var autoReloginEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RELOGIN, true)
        set(v) = prefs.edit().putBoolean(KEY_AUTO_RELOGIN, v).apply()

    /**
     * The last message id seen in each chat, surviving process death.
     *
     * Without this a restarted process cannot tell a backlog from a first
     * sweep, and the sweep suppresses everything rather than risk notifying for
     * every conversation at once — so anything that arrived while the app was
     * dead was dropped silently.
     *
     * Stored as a string set to keep this dependency-free.  The separator is a
     * tab, which cannot appear in either a MID or a message id.
     */
    var lastSeenIds: Map<String, String>
        get() = prefs.getStringSet(KEY_LAST_SEEN, null).orEmpty()
            .mapNotNull { entry ->
                val at = entry.indexOf(SEP)
                if (at <= 0) null else entry.substring(0, at) to entry.substring(at + 1)
            }
            .toMap()
        set(v) = prefs.edit()
            .putStringSet(KEY_LAST_SEEN, v.map { "${it.key}$SEP${it.value}" }.toSet())
            .apply()

    fun saveTokens(access: String, refresh: String?) = prefs.edit()
        .putString(KEY_ACCESS, access)
        .putString(KEY_REFRESH, refresh)
        .apply()

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_EMAIL = "email"
        const val KEY_NOTIFY = "notifications"
        const val KEY_AUTO_RELOGIN = "auto_relogin"
        const val KEY_LAST_SEEN = "last_seen_ids"
        const val SEP = '\t'
    }
}
