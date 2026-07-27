package com.taka4602.line_chrome

import com.taka4602.line_chrome.line.obsParamsJson
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Expected strings come from running `json.dumps` on the identical dict in
 * `line_chrome.client.send_image`.
 *
 * A rejected upload comes back as a bare HTTP status with nothing to debug
 * from, so matching the reference client exactly is cheaper than finding out
 * the hard way which of these details the server cares about.
 */
class ObsParamsTest {

    private fun params(name: String) = obsParamsJson(
        "type" to "image",
        "ver" to "2.0",
        "name" to name,
        "oid" to "reqseq",
        "reqseq" to "7",
        "tomid" to "u1234567890abcdef",
    )

    /** Note the spaces: `json.dumps` separates with ", " and ": " by default. */
    @Test
    fun `matches json dumps output`() {
        assertEquals(
            """{"type": "image", "ver": "2.0", "name": "photo (1).jpg", """ +
                """"oid": "reqseq", "reqseq": "7", "tomid": "u1234567890abcdef"}""",
            params("photo (1).jpg")
        )
    }

    /**
     * ensure_ascii=True: a Japanese filename is entirely normal here and must
     * go out as \uXXXX rather than raw UTF-8 inside the base64 header.
     */
    @Test
    fun `escapes quotes backslashes control characters and non-ascii`() {
        assertEquals(
            """{"type": "image", "ver": "2.0", """ +
                """"name": "a\"b\\c\n\u65e5\u672c.jpg", """ +
                """"oid": "reqseq", "reqseq": "7", "tomid": "u1234567890abcdef"}""",
            params("a\"b\\c\n日本.jpg")
        )
    }

    @Test
    fun `key order is the order given`() {
        assertEquals("""{"b": "1", "a": "2"}""", obsParamsJson("b" to "1", "a" to "2"))
    }

    @Test
    fun `the short escapes are used where json defines them`() {
        assertEquals(
            "{\"k\": \"\\n\\r\\t\\b\\f\"}",
            obsParamsJson("k" to "\n\r\t\b\u000C")
        )
    }

    /** 0x1E — the record separator LINE uses in its own token strings. */
    @Test
    fun `other control characters use the u escape`() {
        assertEquals("{\"k\": \"\\u001e\"}", obsParamsJson("k" to "\u001E"))
    }

    @Test
    fun `plain ascii is left alone`() {
        assertEquals("""{"k": "photo-01_final.JPG"}""", obsParamsJson("k" to "photo-01_final.JPG"))
    }
}
