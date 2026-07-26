package com.example.line_chrome

import com.example.line_chrome.line.mediaObjectUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mirrors the URL rules in `line_chrome.client.download_image`.
 *
 * This is worth pinning because getting it wrong fails *quietly*: asking for
 * `<sid>/<oid>/preview` on a sealed object returns an empty 200 rather than an
 * error, which showed up as a blank bubble that only filled in when tapped.
 */
class MediaUrlTest {

    private val host = "https://gwz.line.naver.jp"
    private val messageId = "624532021879767096"
    private val oid = "0hAbCdEf"
    private val sid = "th"

    private fun url(
        sealed: Boolean,
        preview: Boolean,
        size: String? = null,
        oid: String? = this.oid,
        sid: String? = this.sid,
    ) = mediaObjectUrl(host, messageId, oid, sid, sealed, preview, size)

    // -- plain media -------------------------------------------------------

    @Test
    fun `plain original hangs off the message id`() {
        assertEquals(
            "$host/oa/r/talk/m/$messageId",
            url(sealed = false, preview = false)
        )
    }

    @Test
    fun `plain thumbnail is a path segment`() {
        assertEquals(
            "$host/oa/r/talk/m/$messageId/preview",
            url(sealed = false, preview = true)
        )
    }

    @Test
    fun `plain media takes a dimension string`() {
        assertEquals(
            "$host/oa/r/talk/m/$messageId/m800x1200",
            url(sealed = false, preview = false, size = "m800x1200")
        )
    }

    /** preview wins: it is a different object, not a size of the same one. */
    @Test
    fun `preview takes precedence over size`() {
        assertEquals(
            "$host/oa/r/talk/m/$messageId/preview",
            url(sealed = false, preview = true, size = "w800")
        )
    }

    // -- sealed media ------------------------------------------------------

    @Test
    fun `sealed original uses the sender's namespace and object id`() {
        assertEquals("$host/oa/r/talk/th/0hAbCdEf", url(sealed = true, preview = false))
    }

    /** The one that was wrong: a suffixed object, not a `/preview` segment. */
    @Test
    fun `sealed thumbnail is a separate object`() {
        assertEquals(
            "$host/oa/r/talk/th/0hAbCdEf__ud-preview",
            url(sealed = true, preview = true)
        )
    }

    @Test
    fun `size is ignored for sealed media`() {
        assertEquals(
            "$host/oa/r/talk/th/0hAbCdEf",
            url(sealed = true, preview = false, size = "m800x1200")
        )
    }

    /**
     * A sealed message without OID/SID has nowhere else to point, so it falls
     * back to the message-id form — matching the reference client.
     */
    @Test
    fun `sealed without oid or sid falls back to the message id form`() {
        assertEquals(
            "$host/oa/r/talk/m/$messageId/preview",
            url(sealed = true, preview = true, oid = null, sid = null)
        )
        assertEquals(
            "$host/oa/r/talk/m/$messageId",
            url(sealed = true, preview = false, oid = "0hAbCdEf", sid = null)
        )
    }
}
