package com.example.line_chrome

import com.example.line_chrome.line.TType
import com.example.line_chrome.line.TVal
import com.example.line_chrome.line.fields
import com.example.line_chrome.line.packBinary
import com.example.line_chrome.line.packCompact
import com.example.line_chrome.line.tGet
import com.example.line_chrome.line.tInt
import com.example.line_chrome.line.tStr
import com.example.line_chrome.line.unpackCompactStruct
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The expected byte strings come from running the Python `line_chrome.thrift`
 * encoder on the identical structures.  LINE ships no IDL, so a byte-for-byte
 * match against the working reference implementation is the only way to know
 * this port is right.
 */
class ThriftTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `getProfile takes no parameters`() {
        assertEquals(
            "8221000a67657450726f66696c6500",
            packCompact("getProfile", emptyList()).hex()
        )
    }

    @Test
    fun `string and i32 fields`() {
        val data = packCompact("getRecentMessagesV2", fields {
            str(2, "u1234567890abcdef")
            i32(3, 20)
        })
        assertEquals(
            "82210013676574526563656e744d65737361676573563228117531323334353637383930616263646566152800",
            data.hex()
        )
    }

    @Test
    fun `bool fields encode into the type nibble`() {
        val data = packCompact("getAllChatMids", fields {
            struct(1) { bool(1, true); bool(2, true) }
        })
        assertEquals("8221000e676574416c6c436861744d6964731c11110000", data.hex())
    }

    @Test
    fun `list of strings inside a struct`() {
        val data = packCompact("getChats", fields {
            struct(1) {
                strList(1, listOf("cAAA", "cBBB"))
                bool(2, true)
            }
        })
        assertEquals("8221000867657443686174731c192804634141410463424242110000", data.hex())
    }

    @Test
    fun `i64 fields use zigzag varints`() {
        val data = packCompact("fetchOps", fields {
            i64(2, 123456789L); i32(3, 100); i64(4, 7L); i64(5, 9L)
        })
        assertEquals("8221000866657463684f707326aab4de7515c801160e161200", data.hex())
    }

    /**
     * The field ids here are deliberately out of order — 19 then 10 then 18 —
     * because that is the order the real sendMessage builds them in, and a
     * backwards delta has to fall back to an explicit field id.
     */
    @Test
    fun `sendMessage with plain text`() {
        val data = packCompact("sendMessage", fields {
            i32(1, 1)
            struct(2) {
                str(2, "u1234567890abcdef")
                i64(5, 0L)
                i64(6, 0L)
                bool(14, false)
                i32(15, 0)
                byte(19, 0)
                str(10, "hello world")
                strMap(18, linkedMapOf(
                    "e2eeVersion" to "2", "contentType" to "0", "e2eeMark" to "2"
                ))
                str(21, "m123")
                i32(22, 3)
                i32(24, 1)
            }
        })
        assertEquals(
            "8221000b73656e644d65737361676515021c281175313233343536373839306162636465663600160082150043" +
                "0008140b68656c6c6f20776f726c648b03880b6532656556657273696f6e01320b636f6e74656e74547970650130" +
                "08653265654d61726b013238046d313233150625020000",
            data.hex()
        )
    }

    @Test
    fun `sendMessage with E2EE chunks`() {
        val data = packCompact("sendMessage", fields {
            i32(1, 7)
            struct(2) {
                str(2, "u1234567890abcdef")
                i64(5, 0L)
                i64(6, 0L)
                bool(14, false)
                i32(15, 0)
                byte(19, 0)
                binList(20, listOf(
                    byteArrayOf(1, 2),
                    byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()),
                    byteArrayOf(0),
                ))
                strMap(18, linkedMapOf("e2eeVersion" to "2"))
            }
        })
        assertEquals(
            "8221000b73656e644d657373616765150e1c2811753132333435363738393061626364656636001600821500" +
                "4300193802010203fffefd01000b2401880b6532656556657273696f6e01320000",
            data.hex()
        )
    }

    @Test
    fun `TBinary encodes fixed-width fields`() {
        assertEquals(
            "800100010000000d6765745253414b6579496e666f000000000800020000000100",
            packBinary("getRSAKeyInfo", fields { i32(2, 1) }).hex()
        )
    }

    /** Null optional fields (cert, verifier) must vanish, not encode as empty. */
    @Test
    fun `TBinary loginV2 skips null fields and keeps raw binary`() {
        val data = packBinary("loginV2", fields {
            struct(2) {
                i32(1, 2); i32(2, 1)
                str(3, "key"); str(4, "encdata")
                bool(5, false); str(6, "")
                str(7, "WindowsPC")
                str(8, null); str(9, null)
                bin(10, byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()))
                i32(11, 1); str(12, "WindowsPC")
            }
        })
        assertEquals(
            "80010001000000076c6f67696e5632000000000c000208000100000002080002000000010b0003000000036b" +
                "65790b000400000007656e6364617461020005000b0006000000000b00070000000957696e646f77735043" +
                "0b000a00000004deadbeef08000b000000010b000c0000000957696e646f777350430000",
            data.hex()
        )
    }

    @Test
    fun `negative and large integers round-trip through zigzag`() {
        val data = packCompact("t", fields {
            i32(1, -1)
            i32(2, -1000000)
            i64(3, -9007199254740993L)
            i32(20, 5)          // delta of 17 forces an explicit field id
        })
        assertEquals("8221000174150115ff887a16818080808080802005280a00", data.hex())
    }

    @Test
    fun `decoder reads back a struct the encoder produced`() {
        // Strip the 4-byte message envelope to get at the bare struct.
        val full = packCompact("x", fields {
            str(1, "hello")
            i32(2, -42)
            i64(3, 1234567890123L)
            bool(4, true)
            struct(5) { str(1, "nested") }
            strList(6, listOf("a", "b", "c"))
            strMap(7, linkedMapOf("k" to "v"))
        })
        val struct = unpackCompactStruct(full.copyOfRange(5, full.size))

        assertEquals("hello", tStr(struct, 1))
        assertEquals(-42, tInt(struct, 2))
        assertEquals(1234567890123L, tGet(struct, 3))
        assertEquals(true, tGet(struct, 4))
        assertEquals("nested", tStr(struct, 5, 1))
        assertEquals(listOf("a", "b", "c"), tGet(struct, 6))
        assertEquals(mapOf("k" to "v"), tGet(struct, 7))
    }

    @Test
    fun `binary that is not valid UTF-8 decodes to bytes`() {
        val raw = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x00, 0x01)
        val full = packCompact("x", listOf(
            com.example.line_chrome.line.TField(1, TVal.Bin(raw))
        ))
        val struct = unpackCompactStruct(full.copyOfRange(5, full.size))
        assertEquals(raw.hex(), (struct[1] as ByteArray).hex())
    }

    @Test
    fun `empty map encodes as a single zero byte`() {
        val data = packCompact("x", fields { strMap(1, emptyMap()) })
        // 0x1b = field 1, type MAP; 0x00 = zero entries; 0x00 = struct stop
        assertEquals("822100017 81b0000".replace(" ", ""), data.hex())
    }

    @Test
    fun `list longer than 14 uses the extended header`() {
        val data = packCompact("x", fields { strList(1, (1..20).map { "i$it" }) })
        // 0x19 field header, then 0xf8 (extended count, element type 8 = binary)
        // followed by the real count as a varint.
        assert(data.hex().startsWith("822100017819f814")) { data.hex() }
    }

    @Test
    fun `type codes match the Thrift spec`() {
        // LIST is 0x09 and SET is 0x0A in TCompact.  They are not the same type,
        // and swapping them silently truncates every list LINE sends back.
        assertEquals(TType.LIST, 15)
        assertEquals(TType.SET, 14)
    }
}
