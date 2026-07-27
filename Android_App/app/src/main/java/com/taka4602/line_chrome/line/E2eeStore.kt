package com.taka4602.line_chrome.line

import android.util.Log
import org.json.JSONObject
import java.io.File

data class E2eeKey(
    val keyId: Int,
    val privKey: ByteArray,
    val pubKey: ByteArray,
    val e2eeVersion: Int,
) {
    fun toJson(): String = JSONObject().apply {
        put("keyId", keyId)
        put("privKey", privKey.b64())
        put("pubKey", pubKey.b64())
        put("e2eeVersion", e2eeVersion)
    }.toString()

    // ByteArray fields make the generated equals/hashCode identity-based, which
    // is wrong for a value type that gets compared across reloads.
    override fun equals(other: Any?): Boolean =
        other is E2eeKey && keyId == other.keyId && privKey.contentEquals(other.privKey)

    override fun hashCode(): Int = keyId * 31 + privKey.contentHashCode()

    companion object {
        fun fromJson(s: String): E2eeKey = JSONObject(s).let {
            E2eeKey(it.getInt("keyId"), it.getString("privKey").unb64(),
                it.getString("pubKey").unb64(), it.optInt("e2eeVersion", 1))
        }
    }
}

/**
 * On-disk home for E2EE key material, laid out the same as the Python client's
 * cache directories so a session can be moved between the two.
 *
 * Treat [keyDir] as a secret: it can decrypt your messages, and losing it costs
 * another PIN confirmation because LINE only ever hands the private half out
 * during a device registration.
 */
class E2eeStore(private val savePath: File) {

    private val keyDir get() = dir(".e2eeKeys")
    private val pubDir get() = dir(".e2eePublicKeys")
    private val groupDir get() = dir(".e2eeGroupKeys")

    private fun dir(name: String) = File(savePath, name).apply { mkdirs() }

    private fun read(f: File): String? = if (f.exists()) f.readText() else null

    private fun write(f: File, data: String) = try {
        f.writeText(data)
    } catch (e: Exception) {
        Log.w(TAG, "could not write ${f.name}: $e")
    }

    // -- our own identity key ---------------------------------------------

    /** Written under both the mid and the key id, matching the Python layout. */
    fun saveSelfKey(key: E2eeKey, mid: String? = null) {
        val data = key.toJson()
        write(File(keyDir, "key_${key.keyId}.json"), data)
        if (mid != null) write(File(keyDir, "$mid.json"), data)
    }

    fun loadSelfKeyByMid(mid: String): E2eeKey? =
        read(File(keyDir, "$mid.json"))?.let { runCatching { E2eeKey.fromJson(it) }.getOrNull() }

    fun loadSelfKeyById(keyId: Int): E2eeKey? =
        read(File(keyDir, "key_$keyId.json"))?.let { runCatching { E2eeKey.fromJson(it) }.getOrNull() }

    fun backfillMid(mid: String, key: E2eeKey) = write(File(keyDir, "$mid.json"), key.toJson())

    // -- peer public keys --------------------------------------------------

    /**
     * Cached by key id, because negotiateE2EEPublicKey only ever returns a
     * peer's *current* key — once they rotate, older messages become
     * undecryptable unless the old key was captured earlier.
     */
    fun loadPeerKey(keyId: Int): ByteArray? =
        read(File(pubDir, "key_id_$keyId.json"))
            ?.let { runCatching { JSONObject(it).getString("keyData").unb64() }.getOrNull() }

    fun savePeerKey(mid: String, keyId: Int, keyData: ByteArray) = write(
        File(pubDir, "key_id_$keyId.json"),
        JSONObject().put("mid", mid).put("keyData", keyData.b64()).toString()
    )

    // -- group shared keys -------------------------------------------------

    fun loadGroupKey(groupMid: String): Pair<Int, ByteArray>? =
        read(File(groupDir, "$groupMid.json"))?.let {
            runCatching {
                val o = JSONObject(it)
                o.getInt("keyId") to o.getString("privKey").unb64()
            }.getOrNull()
        }

    fun saveGroupKey(groupMid: String, keyId: Int, privKey: ByteArray) = write(
        File(groupDir, "$groupMid.json"),
        JSONObject().put("keyId", keyId).put("privKey", privKey.b64()).toString()
    )

    // -- device certificate ------------------------------------------------

    fun loadCert(email: String): String? = read(File(dir(".data"), "$email.crt"))

    fun saveCert(email: String, cert: String) = write(File(dir(".data"), "$email.crt"), cert)

    fun clearAll() {
        listOf(keyDir, pubDir, groupDir, dir(".data")).forEach { it.deleteRecursively() }
    }

    private companion object {
        const val TAG = "E2eeStore"
    }
}
