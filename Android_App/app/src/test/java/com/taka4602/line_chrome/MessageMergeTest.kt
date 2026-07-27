package com.taka4602.line_chrome

import com.taka4602.line_chrome.line.ContentType
import com.taka4602.line_chrome.line.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Mirrors `LineRepository.merge`.
 *
 * The open conversation is re-fetched every few seconds, so this runs far more
 * often than anything else in the app — a merge that returns a fresh list when
 * nothing changed recomposes the whole conversation on a timer.
 */
class MessageMergeTest {

    private fun msg(id: String, at: Long, text: String?, chunks: List<Any?>? = null) = Message(
        id = id, sender = "u1", to = "u2", toType = 0, createdTime = at,
        text = text, contentType = ContentType.TEXT, contentMetadata = emptyMap(),
        hasContent = false, location = null, relatedMessageId = null,
        relationType = null, chunks = chunks,
    )

    /** The production implementation, kept in step by hand. */
    private fun merge(existing: List<Message>, fetched: List<Message>): List<Message> {
        val byKey = LinkedHashMap<String, Message>()
        for (m in existing + fetched) {
            val key = m.id ?: "${m.createdTime}/${m.sender}"
            val previous = byKey[key]
            byKey[key] = when {
                previous == null -> m
                previous.text.isNullOrEmpty() -> m
                m.text.isNullOrEmpty() -> previous
                else -> m
            }
        }
        val merged = byKey.values.sortedBy { it.createdTime ?: 0L }
        val unchanged = merged.size == existing.size && merged.indices.all { i ->
            merged[i].id == existing[i].id && merged[i].text == existing[i].text
        }
        return if (unchanged) existing else merged
    }

    @Test
    fun `re-fetching the same messages returns the very same list`() {
        val existing = listOf(msg("a", 100, "one"), msg("b", 200, "two"))
        // A re-fetch builds fresh instances; the result must still be identical
        // by reference or the StateFlow re-emits.
        val fetched = listOf(msg("a", 100, "one"), msg("b", 200, "two"))
        assertSame(existing, merge(existing, fetched))
    }

    /** ByteArray compares by identity, which is exactly the trap being avoided. */
    @Test
    fun `sealed messages with fresh chunk arrays still count as unchanged`() {
        val existing = listOf(msg("a", 100, "hi", listOf(byteArrayOf(1, 2, 3))))
        val fetched = listOf(msg("a", 100, "hi", listOf(byteArrayOf(1, 2, 3))))
        assertSame(existing, merge(existing, fetched))
    }

    @Test
    fun `a new message produces a new list`() {
        val existing = listOf(msg("a", 100, "one"))
        val merged = merge(existing, listOf(msg("b", 200, "two")))
        assertNotSame(existing, merged)
        assertEquals(listOf("a", "b"), merged.map { it.id })
    }

    /** Our own sealed sends carry plaintext the server copy does not have. */
    @Test
    fun `a decrypted copy is not lost to an opaque one`() {
        val existing = listOf(msg("a", 100, "hello", listOf(byteArrayOf(9))))
        val fetched = listOf(msg("a", 100, null, listOf(byteArrayOf(9))))
        assertEquals("hello", merge(existing, fetched).single().text)
    }

    @Test
    fun `an opaque local copy is upgraded when the server sends plaintext`() {
        val existing = listOf(msg("a", 100, null, listOf(byteArrayOf(9))))
        val fetched = listOf(msg("a", 100, "decrypted"))
        assertEquals("decrypted", merge(existing, fetched).single().text)
    }

    /** A poll landing mid-fetch must not lose the message it delivered. */
    @Test
    fun `locally appended messages survive a refetch that predates them`() {
        val existing = listOf(msg("a", 100, "one"), msg("live", 300, "just arrived"))
        val merged = merge(existing, listOf(msg("a", 100, "one"), msg("b", 200, "two")))
        assertEquals(listOf("a", "b", "live"), merged.map { it.id })
    }

    @Test
    fun `result is ordered oldest first regardless of input order`() {
        val merged = merge(emptyList(), listOf(msg("c", 300, "c"), msg("a", 100, "a")))
        assertEquals(listOf("a", "c"), merged.map { it.id })
    }
}
