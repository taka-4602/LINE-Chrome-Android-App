package com.taka4602.line_chrome

import com.taka4602.line_chrome.line.XxHash32
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expected values produced by the Python `xxhash.xxh32(data, seed=0)` the
 * reference client uses for the legy `x-le: 18` body MAC.  A wrong MAC makes
 * legy reject the request with an empty body and no explanation, so this is
 * worth pinning.
 */
class XxHash32Test {

    private fun digest(data: ByteArray) = "%08x".format(XxHash32.digest(data))

    @Test
    fun `empty input`() = assertEquals("02cc5d05", digest(ByteArray(0)))

    @Test
    fun `single byte`() = assertEquals("550d7456", digest("a".toByteArray()))

    /** Under 16 bytes, so it skips the four-accumulator path entirely. */
    @Test
    fun `short input`() = assertEquals("32d153ff", digest("abc".toByteArray()))

    /** Exactly four 16-byte blocks with no tail. */
    @Test
    fun `block-aligned input`() =
        assertEquals("31120435", digest(ByteArray(64) { it.toByte() }))

    /** 100 bytes: six full blocks, then a 4-byte lane and a single-byte tail. */
    @Test
    fun `input with both tail paths`() =
        assertEquals("4bd30d3a", digest(ByteArray(100) { 'x'.code.toByte() }))

    @Test
    fun `digestBytes is big-endian, matching bytes fromhex of the hexdigest`() {
        assertEquals(
            "32d153ff",
            XxHash32.digestBytes("abc".toByteArray()).joinToString("") { "%02x".format(it) }
        )
    }
}
