package com.example.line_chrome

import com.example.line_chrome.line.LineServiceError
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors `LineClient.batched`.
 *
 * getChats and getContactsV2 take the whole MID list in one struct and answer
 * INVALID_LENGTH (6) when it is too long, without saying what the limit is. An
 * account with a few dozen contacts never finds out; one with a few hundred
 * gets no friend list at all.
 */
class BatchBackoffTest {

    private val invalidLength = 6
    private val batchStart = 100
    private val batchMin = 10

    /** The production implementation, kept in step by hand. */
    private fun <T> batched(
        mids: List<String>,
        call: (List<String>) -> List<T>,
    ): List<T> {
        var size = batchStart
        while (true) {
            try {
                return mids.chunked(size).flatMap(call)
            } catch (e: LineServiceError) {
                if (e.code != invalidLength || size <= batchMin) throw e
                size /= 2
            }
        }
    }

    private fun mids(n: Int) = (1..n).map { "u%032x".format(it) }

    @Test
    fun `a server that accepts the first size issues one request per chunk`() {
        val sizes = mutableListOf<Int>()
        val out = batched(mids(250)) { batch -> sizes += batch.size; batch }
        assertEquals(listOf(100, 100, 50), sizes)
        assertEquals(250, out.size)
    }

    /** The real symptom: 100 is refused, so it retries the whole sweep at 50. */
    @Test
    fun `it halves until the server accepts`() {
        val attempted = mutableListOf<Int>()
        val out = batched(mids(120)) { batch ->
            attempted += batch.size
            if (batch.size > 50) throw LineServiceError(invalidLength, "Invalid Length")
            batch
        }
        assertEquals(120, out.size)
        assertEquals(listOf(100, 50, 50, 20), attempted)
    }

    @Test
    fun `it keeps halving when one step is not enough`() {
        val out = batched(mids(60)) { batch ->
            if (batch.size > 25) throw LineServiceError(invalidLength, "Invalid Length")
            batch
        }
        assertEquals(60, out.size)
    }

    /** Anything that is not a length complaint is a real failure. */
    @Test(expected = LineServiceError::class)
    fun `other service errors are not retried`() {
        batched<String>(mids(30)) { throw LineServiceError(8, "V3_TOKEN_CLIENT_LOGGED_OUT") }
    }

    /** Below the floor, backing off further is not the answer. */
    @Test(expected = LineServiceError::class)
    fun `it gives up rather than shrinking forever`() {
        batched<String>(mids(30)) { throw LineServiceError(invalidLength, "Invalid Length") }
    }

    @Test
    fun `an empty list makes no requests at all`() {
        var calls = 0
        val out = batched(emptyList<String>()) { calls++; it }
        assertEquals(0, calls)
        assertEquals(0, out.size)
    }
}
