package com.taka4602.line_chrome

import com.taka4602.line_chrome.line.Crypto
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The login plaintext: one length byte per field, then the field.
 *
 * The reference client builds this as a string and prefixes `len(value)`, which
 * counts characters. For ASCII the two agree, so an English account never sees a
 * problem — but a Japanese password declares 6 where it sends 16 and the server
 * rejects the blob. These pin the byte framing so that cannot come back.
 */
class CredentialBlobTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** Byte-for-byte what the Python client produces, so ASCII logins are unchanged. */
    @Test
    fun `ascii matches the reference client exactly`() {
        assertEquals(
            "066162633132330f796f75406578616d706c652e636f6d0768756e74657232",
            Crypto.credentialBlob("abc123", "you@example.com", "hunter2").hex()
        )
    }

    /** 0x10 = 16 bytes, not 0x06 = 6 characters, which is what used to go out. */
    @Test
    fun `a japanese password declares its byte length`() {
        assertEquals(
            "066162633132330f796f75406578616d706c652e636f6d" +
                "10e381b1e38199e3828fe383bce381a931",
            Crypto.credentialBlob("abc123", "you@example.com", "ぱすわーど1").hex()
        )
    }

    @Test
    fun `an accented password declares its byte length`() {
        val blob = Crypto.credentialBlob("s", "a@b.com", "café")
        // "café" is 4 characters but 5 UTF-8 bytes.
        assertEquals(5, blob[blob.size - 6].toInt())
        assertEquals("café", String(blob.copyOfRange(blob.size - 5, blob.size)))
    }

    @Test
    fun `every field is length-prefixed in order`() {
        val blob = Crypto.credentialBlob("ab", "cde", "f")
        assertEquals("0261620363646501 66".replace(" ", ""), blob.hex())
    }

    /**
     * A length of 128 or more cannot be a single UTF-8 byte, which is the other
     * half of why this is assembled as bytes rather than as a string.
     */
    @Test
    fun `a long field still uses exactly one length byte`() {
        val blob = Crypto.credentialBlob("s", "a@b.com", "x".repeat(200))
        assertEquals(200, blob[blob.size - 201].toInt() and 0xFF)
        assertEquals(1 + 1 + 1 + 7 + 1 + 200, blob.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a field too long for the prefix is refused rather than truncated`() {
        Crypto.credentialBlob("s", "a@b.com", "x".repeat(256))
    }
}
