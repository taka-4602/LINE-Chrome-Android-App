package com.taka4602.line_chrome

import com.taka4602.line_chrome.line.ContentType
import com.taka4602.line_chrome.line.Message
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The chat room renders oldest-first and scrolls to the last index, so message
 * ordering is not cosmetic — get it backwards and opening a chat shows the
 * oldest message with the newest scrolled off the top.
 *
 * `getRecentMessagesV2` returns newest first. The Python client's docstring
 * claims otherwise, but its only caller passes `count=1`, where first and last
 * are the same element, so the claim was never exercised.
 */
class MessageOrderTest {

    private fun msg(id: String, at: Long, text: String? = "t", chunks: List<Any?>? = null) = Message(
        id = id, sender = "u1", to = "u2", toType = 0, createdTime = at,
        text = text, contentType = ContentType.TEXT, contentMetadata = emptyMap(),
        hasContent = false, location = null, relatedMessageId = null,
        relationType = null, chunks = chunks,
    )

    private fun sortLikeClient(messages: List<Message>) =
        messages.sortedBy { it.createdTime ?: 0L }

    @Test
    fun `newest-first input comes out oldest-first`() {
        val serverOrder = listOf(msg("c", 300), msg("b", 200), msg("a", 100))
        assertEquals(listOf("a", "b", "c"), sortLikeClient(serverOrder).map { it.id })
    }

    @Test
    fun `already oldest-first input is left alone`() {
        val serverOrder = listOf(msg("a", 100), msg("b", 200), msg("c", 300))
        assertEquals(listOf("a", "b", "c"), sortLikeClient(serverOrder).map { it.id })
    }

    @Test
    fun `the last element is the newest, which is what the chat list previews`() {
        val sorted = sortLikeClient(listOf(msg("c", 300), msg("a", 100), msg("b", 200)))
        assertEquals("c", sorted.last().id)
    }

    // -- preview ----------------------------------------------------------

    @Test
    fun `a sealed message with recovered plaintext previews as text, not Encrypted`() {
        // What a sealed send looks like after the plaintext is put back: the
        // ciphertext chunks are still attached, but the text wins.
        val sent = msg("m1", 100, text = "hello", chunks = listOf(ByteArray(4)))
        assertEquals("hello", sent.preview)
    }

    @Test
    fun `a sealed message we could not decrypt still says Encrypted`() {
        val opaque = msg("m2", 100, text = null, chunks = listOf(ByteArray(4)))
        assertEquals("[Encrypted]", opaque.preview)
    }

    @Test
    fun `non-text content is labelled by type`() {
        val sticker = msg("m3", 100).copy(contentType = ContentType.STICKER)
        assertEquals("[Sticker]", sticker.preview)
    }
}
