package com.example.line_chrome.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Email and password kept for unattended re-login.
 *
 * LINE's v3 access token expires after a few days
 * (`[code=8] V3_TOKEN_CLIENT_LOGGED_OUT`) and the refresh token eventually goes
 * with it, so staying signed in means being able to log in again on our own.
 * That needs the password, which is worth handling properly: it is encrypted
 * with an AES key generated inside the Android keystore, so what lands in
 * SharedPreferences is useless without a key that cannot be extracted from the
 * device — not even with root.
 *
 * The email is stored in the clear, since it is far less sensitive and is
 * useful for pre-filling the login form.
 */
class CredentialStore(context: Context) {

    private val prefs = context.getSharedPreferences("line_credentials", Context.MODE_PRIVATE)

    val email: String? get() = prefs.getString(KEY_EMAIL, null)

    val hasCredentials: Boolean get() = email != null && prefs.contains(KEY_PASSWORD)

    fun save(email: String, password: String) {
        try {
            prefs.edit()
                .putString(KEY_EMAIL, email)
                .putString(KEY_PASSWORD, encrypt(password))
                .apply()
        } catch (e: Exception) {
            // Losing the ability to re-login unattended is not worth failing a
            // successful login over.
            Log.w(TAG, "could not store credentials: $e")
            clear()
        }
    }

    fun password(): String? {
        val stored = prefs.getString(KEY_PASSWORD, null) ?: return null
        return try {
            decrypt(stored)
        } catch (e: Exception) {
            // The keystore key is gone — reinstall, restore to a new device, or
            // a lock-screen change on some vendors.  The ciphertext is dead
            // weight now.
            Log.w(TAG, "stored password no longer decryptable: $e")
            clear()
            null
        }
    }

    fun clear() = prefs.edit().clear().apply()

    // -- keystore ----------------------------------------------------------

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build()
            )
        }.generateKey()
    }

    private fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        // The keystore picks the IV; it has to travel with the ciphertext.
        return b64(cipher.iv) + ":" + b64(body)
    }

    private fun decrypt(stored: String): String {
        val (ivPart, bodyPart) = stored.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, unb64(ivPart)))
        return String(cipher.doFinal(unb64(bodyPart)), Charsets.UTF_8)
    }

    private fun b64(data: ByteArray) = Base64.encodeToString(data, Base64.NO_WRAP)
    private fun unb64(data: String) = Base64.decode(data, Base64.NO_WRAP)

    private companion object {
        const val TAG = "CredentialStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "line_chrome_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_EMAIL = "email"
        const val KEY_PASSWORD = "password"
    }
}
