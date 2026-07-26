package com.example.line_chrome

import com.example.line_chrome.line.ContentType
import com.example.line_chrome.line.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StickerTest {

    private fun sticker(metadata: Map<String, String>, type: Int = ContentType.STICKER) = Message(
        id = "m1", sender = "u1", to = "u2", toType = 0, createdTime = 1L,
        text = null, contentType = type, contentMetadata = metadata,
        hasContent = false, location = null, relatedMessageId = null,
        relationType = null, chunks = null,
    )

    @Test
    fun `sticker url is built from STKID`() {
        val msg = sticker(mapOf("STKID" to "866986994", "STKPKGID" to "12287136"))
        assertEquals(
            "https://stickershop.line-scdn.net/stickershop/v1/sticker/866986994/android/sticker.png",
            msg.stickerUrl
        )
    }

    /** Only contentType 7 is a sticker; STKID on anything else is not ours to render. */
    @Test
    fun `non-sticker content types have no sticker url`() {
        assertNull(sticker(mapOf("STKID" to "1"), type = ContentType.TEXT).stickerUrl)
        assertNull(sticker(mapOf("STKID" to "1"), type = ContentType.IMAGE).stickerUrl)
    }

    @Test
    fun `a sticker without STKID falls back rather than building a broken url`() {
        assertNull(sticker(emptyMap()).stickerUrl)
        assertNull(sticker(mapOf("STKID" to "")).stickerUrl)
    }

    /** The chat list has no room for an image, so the preview stays a label. */
    @Test
    fun `chat list preview still labels stickers`() {
        assertEquals("[Sticker]", sticker(mapOf("STKID" to "866986994")).preview)
    }
}
