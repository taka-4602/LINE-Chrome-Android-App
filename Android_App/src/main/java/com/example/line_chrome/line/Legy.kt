package com.example.line_chrome.line

import java.io.ByteArrayOutputStream

/**
 * legy — LINE's body-encryption proxy at `gf.line.naver.jp/enc`.
 *
 * Auth traffic cannot go to the plain gateway; it is wrapped in AES-CBC with a
 * per-request key that is itself RSA-OAEP encrypted into the `x-lcs` header.
 * The real request path and access token travel as *inner* headers inside the
 * encrypted body, not as HTTP headers.
 */
object Legy {

    /** Fixed IV — legy derives nothing per-request beyond the AES key. */
    private val IV = byteArrayOf(78, 9, 72, 62, 56, 245.toByte(), 255.toByte(), 114, 128.toByte(), 18, 123, 158.toByte(), 251.toByte(), 92, 45, 51)

    private const val PUBKEY =
        "-----BEGIN PUBLIC KEY-----\n" +
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA0LRokSkGDo8G5ObFfyKi" +
            "IdPAU5iOpj+UT+A3AcDxLuePyDt8IVp9HpOsJlf8uVk3Wr9fs+8y7cnF3WiY6Ro5" +
            "26hy3fbWR4HiD0FaIRCOTbgRlsoGNC2rthp2uxYad5up78krSDXNKBab8t1PteCm" +
            "Oq84TpDCRmainaZQN9QxzaSvYWUICVv27Kk97y2j3LS3H64NCqjS88XacAieivEL" +
            "fMr6rT2GutRshKeNSZOUR3YROV4THa77USBQwRI7ZZTe6GUFazpocTN58QY8jFYO" +
            "Dzfhdyoiym6rXJNNnUKatiSC/hmzdpX8/h4Y98KaGAZaatLAgPMRCe582q4JwHg7" +
            "rwIDAQAB\n" +
            "-----END PUBLIC KEY-----"

    /** RSA-OAEP wrap of the per-request AES key, for the `x-lcs` header. */
    fun makeXcs(aesKey: ByteArray): String = "0005" + Crypto.rsaOaepEncrypt(PUBKEY, aesKey).b64()

    private fun encodeHeaders(headers: Map<String, String>): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(headers.size ushr 8); body.write(headers.size and 0xFF)
        for ((k, v) in headers) {
            val kb = k.toByteArray(); val vb = v.toByteArray()
            body.write(kb.size ushr 8); body.write(kb.size and 0xFF); body.write(kb)
            body.write(vb.size ushr 8); body.write(vb.size and 0xFF); body.write(vb)
        }
        val inner = body.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(inner.size ushr 8); out.write(inner.size and 0xFF)
        out.write(inner)
        return out.toByteArray()
    }

    /** @return the inner headers plus the Thrift body that follows them. */
    private fun decodeHeaders(data: ByteArray): Pair<Map<String, String>, ByteArray> {
        fun u16(at: Int) = ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
        val total = u16(0) + 2
        val n = u16(2)
        var pos = 4
        val headers = LinkedHashMap<String, String>()
        repeat(n) {
            val kl = u16(pos); pos += 2
            val k = String(data, pos, kl); pos += kl
            val vl = u16(pos); pos += 2
            val v = String(data, pos, vl); pos += vl
            headers[k] = v
        }
        return headers to data.copyOfRange(total, data.size)
    }

    /** The 4-byte xxhash MAC that `x-le: 18` appends after the ciphertext. */
    private fun sig(aesKey: ByteArray, encrypted: ByteArray): ByteArray {
        val r = ByteArray(16) { (aesKey[it].toInt() xor 92).toByte() }
        val nInput = r.copyOf()
        for (i in 0 until 16) r[i] = (r[i].toInt() xor 106).toByte()
        val inner = XxHash32.digestBytes(r + encrypted)
        return XxHash32.digestBytes(nInput + inner)
    }

    fun encrypt(aesKey: ByteArray, path: String, body: ByteArray, authToken: String? = null): ByteArray {
        val innerHeaders = LinkedHashMap<String, String>()
        innerHeaders["x-lpqs"] = path
        if (authToken != null) innerHeaders["x-lt"] = authToken
        val payload = encodeHeaders(innerHeaders) + body
        val encrypted = Crypto.aesCbcEncryptNoPad(aesKey, IV, Crypto.pkcs7Pad(payload))
        return encrypted + sig(aesKey, encrypted)
    }

    fun decrypt(aesKey: ByteArray, data: ByteArray): Pair<Map<String, String>, ByteArray> {
        // The response carries the same trailing 4-byte MAC, so it is not a
        // whole number of blocks; pad to align, decrypt, then drop the block
        // that the MAC bled into.
        val aligned = Crypto.pkcs7Pad(data)
        val decrypted = Crypto.aesCbcDecryptNoPad(aesKey, IV, aligned)
        val plaintext = Crypto.pkcs7Unpad(decrypted.copyOfRange(0, decrypted.size - 16))
        return decodeHeaders(plaintext)
    }
}
