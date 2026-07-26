package com.example.line_chrome

import com.example.line_chrome.line.Crypto
import com.example.line_chrome.line.TField
import com.example.line_chrome.line.TVal
import com.example.line_chrome.line.TType
import com.example.line_chrome.line.fields
import com.example.line_chrome.line.obsParamsJson
import com.example.line_chrome.line.packBinaryStruct
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors from the Python client's `_decrypt_key_material` and `_talk_meta`.
 *
 * Media in a sealed chat is AES-CTR under a key derived from the message's own
 * key material — get the derivation wrong and the download decodes to noise
 * with nothing to say why.
 */
class MediaCryptoTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun String.bytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val keyMaterial = ByteArray(32) { it.toByte() }

    /** salt=None in `cryptography` means a block of zeros, not "skip extract". */
    @Test
    fun `hkdf matches the reference derivation`() {
        assertEquals(
            "54480d998f8e329e5a0a33fdc64f6aee8d9b0edb7a2792f8e52c33d59b1b72e0" +
                "682c748ebd8593d84ae2974fe79b5448cc7dbf05c419f4b554d14d3f26e25536" +
                "70679ffec9e08b4e8f569eb1",
            Crypto.hkdfSha256(keyMaterial, "FileEncryption".toByteArray(), 32 + 32 + 12).hex()
        )
    }

    /**
     * pycryptodome's CTR with a 12-byte nonce counts in the remaining 4 bytes
     * from zero, which is what a zero-padded 16-byte IV gives.
     */
    @Test
    fun `aes ctr matches pycryptodome with a 12-byte nonce`() {
        val t = Crypto.hkdfSha256(keyMaterial, "FileEncryption".toByteArray(), 76)
        val encKey = t.copyOfRange(0, 32)
        val nonce = t.copyOfRange(64, 76)

        val plain = ("hello media payload, long enough to span a block or two").repeat(2)
        assertEquals(
            "3d056abfc4c93b60729645fac6bb11666bcf935fcbf45e2ffae65a5d9dca706d" +
                "1b0b41a426be4baabfcf014f6b306d30b41a6a87b117090796a27364849a418e" +
                "751a641cd804a08ee643bf81464428d2b5bdeb5e9909fbbab902e5ebad7b9298" +
                "4cb3a7d13f841d64cd429178aaae",
            Crypto.aesCtr(encKey, nonce, plain.toByteArray()).hex()
        )
    }

    /** CTR is symmetric, which is why the client decrypts with an encrypt call. */
    @Test
    fun `aes ctr round-trips`() {
        val key = ByteArray(32) { 7 }
        val nonce = ByteArray(12) { 3 }
        val plain = "round trip".toByteArray()
        assertArrayEquals(plain, Crypto.aesCtr(key, nonce, Crypto.aesCtr(key, nonce, plain)))
    }

    @Test
    fun `hmac verifies the appended tag`() {
        val t = Crypto.hkdfSha256(keyMaterial, "FileEncryption".toByteArray(), 76)
        val macKey = t.copyOfRange(32, 64)
        val withMac = ("3d056abfc4c93b60729645fac6bb11666bcf935fcbf45e2ffae65a5d9dca706d" +
            "1b0b41a426be4baabfcf014f6b306d30b41a6a87b117090796a27364849a418e" +
            "751a641cd804a08ee643bf81464428d2b5bdeb5e9909fbbab902e5ebad7b9298" +
            "4cb3a7d13f841d64cd429178aaae" +
            "65360b4bc2b852b4b16c76d3bc4e53946c2106fc4cffcf32717763aeb54a176b").bytes()

        val body = withMac.copyOfRange(0, withMac.size - 32)
        val tag = withMac.copyOfRange(withMac.size - 32, withMac.size)
        assertTrue(Crypto.constantTimeEquals(Crypto.hmacSha256(macKey, body), tag))
        assertFalse(Crypto.constantTimeEquals(Crypto.hmacSha256(macKey, body + 1), tag))
    }

    /** The struct that authorises a sealed download: id at 4, empty list at 27. */
    @Test
    fun `talk meta struct matches pack_binary_struct`() {
        assertEquals(
            "0b0004000000123632343533323032313837393736373039360f001b0c0000000000",
            packBinaryStruct(fields {
                str(4, "624532021879767096")
                emptyStructList(27)
            }).hex()
        )
    }

    @Test
    fun `talk meta wraps the struct in json and base64`() {
        val inner = packBinaryStruct(
            listOf(
                TField(4, TVal.str("624532021879767096")),
                TField(27, TVal.Lst(TType.STRUCT, emptyList())),
            )
        )
        val innerB64 = java.util.Base64.getEncoder().encodeToString(inner)
        assertEquals("CwAEAAAAEjYyNDUzMjAyMTg3OTc2NzA5Ng8AGwwAAAAAAA==", innerB64)

        val json = obsParamsJson("message" to innerB64)
        assertEquals("""{"message": "CwAEAAAAEjYyNDUzMjAyMTg3OTc2NzA5Ng8AGwwAAAAAAA=="}""", json)
        assertEquals(
            "eyJtZXNzYWdlIjogIkN3QUVBQUFBRWpZeU5EVXpNakF5TVRnM09UYzJOekE1Tmc4QUd3d0FBQUFBQUE9PSJ9",
            java.util.Base64.getEncoder().encodeToString(json.toByteArray())
        )
    }
}
