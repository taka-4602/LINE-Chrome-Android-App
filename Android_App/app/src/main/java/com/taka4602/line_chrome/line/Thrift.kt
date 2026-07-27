package com.taka4602.line_chrome.line

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * Minimal Apache Thrift encode/decode for LINE's TBinary and TCompact protocols.
 *
 * Port of `line_chrome/thrift.py`.  Decoded structs come back as
 * `Map<Int, Any?>` keyed by field id, exactly like the Python decoder, because
 * LINE ships no IDL and every response is read positionally.
 *
 * Two decoder quirks carried over verbatim, both of which cost real debugging
 * time in the Python version:
 *  - a struct result is returned **unwrapped**, but a scalar or list result is
 *    keyed at 0.  Which access is correct depends on the method's Thrift return
 *    type.
 *  - BINARY fields decode to [String] when the bytes happen to be valid UTF-8,
 *    and to [ByteArray] otherwise.  Use [asBytes] whenever the field is really
 *    binary (E2EE chunks, key material).
 */

object TType {
    const val BOOL = 2
    const val BYTE = 3
    const val DOUBLE = 4
    const val I16 = 6
    const val I32 = 8
    const val I64 = 10
    const val STRING = 11
    const val STRUCT = 12
    const val MAP = 13
    const val SET = 14
    const val LIST = 15
}

/** Thrift type -> TCompact type nibble. */
private val CTYPES = mapOf(
    TType.BOOL to 0x01,
    TType.BYTE to 0x03,
    TType.DOUBLE to 0x07,
    TType.I16 to 0x04,
    TType.I32 to 0x05,
    TType.I64 to 0x06,
    TType.STRING to 0x08,
    TType.STRUCT to 0x0C,
    TType.MAP to 0x0B,
    TType.SET to 0x0A,
    TType.LIST to 0x09,
)

/** TCompact type nibble -> Thrift type.  0x02 is bool-false. */
private val TTYPES: Map<Int, Int> = buildMap {
    CTYPES.forEach { (t, c) -> put(c, t) }
    put(0x02, TType.BOOL)
}

// ---------------------------------------------------------------------------
// Value model
// ---------------------------------------------------------------------------

sealed class TVal(val type: Int) {
    class Bool(val v: Boolean) : TVal(TType.BOOL)
    class Byte8(val v: Int) : TVal(TType.BYTE)
    class Dbl(val v: Double) : TVal(TType.DOUBLE)
    class I32(val v: Int) : TVal(TType.I32)
    class I64(val v: Long) : TVal(TType.I64)
    class Bin(val v: ByteArray) : TVal(TType.STRING)
    class Struct(val fields: List<TField>) : TVal(TType.STRUCT)
    class Mp(val kType: Int, val vType: Int, val entries: List<Pair<TVal, TVal>>) : TVal(TType.MAP)
    class Lst(val eType: Int, val items: List<TVal>) : TVal(TType.LIST)
    class St(val eType: Int, val items: List<TVal>) : TVal(TType.SET)

    companion object {
        fun str(s: String) = Bin(s.toByteArray(StandardCharsets.UTF_8))
    }
}

data class TField(val id: Int, val value: TVal)

/**
 * Builder for a field list.  Null values are skipped, which is how optional
 * Thrift fields are omitted — sending an explicit null is not a thing on the
 * wire.
 */
class FieldsBuilder {
    val out = mutableListOf<TField>()

    fun bool(id: Int, v: Boolean?) = v?.let { out += TField(id, TVal.Bool(it)) }
    fun byte(id: Int, v: Int?) = v?.let { out += TField(id, TVal.Byte8(it)) }
    fun i32(id: Int, v: Int?) = v?.let { out += TField(id, TVal.I32(it)) }
    fun i64(id: Int, v: Long?) = v?.let { out += TField(id, TVal.I64(it)) }
    fun str(id: Int, v: String?) = v?.let { out += TField(id, TVal.str(it)) }
    fun bin(id: Int, v: ByteArray?) = v?.let { out += TField(id, TVal.Bin(it)) }

    fun struct(id: Int, block: FieldsBuilder.() -> Unit) {
        out += TField(id, TVal.Struct(fields(block)))
    }

    /** An empty list of structs, which some LINE payloads require to be present. */
    fun emptyStructList(id: Int) {
        out += TField(id, TVal.Lst(TType.STRUCT, emptyList()))
    }

    fun strList(id: Int, v: List<String>?) = v?.let { items ->
        out += TField(id, TVal.Lst(TType.STRING, items.map { TVal.str(it) }))
    }

    fun binList(id: Int, v: List<ByteArray>?) = v?.let { items ->
        out += TField(id, TVal.Lst(TType.STRING, items.map { TVal.Bin(it) }))
    }

    fun strMap(id: Int, v: Map<String, String>?) = v?.let { m ->
        out += TField(
            id,
            TVal.Mp(TType.STRING, TType.STRING, m.map { (k, value) -> TVal.str(k) to TVal.str(value) })
        )
    }
}

fun fields(block: FieldsBuilder.() -> Unit): List<TField> = FieldsBuilder().apply(block).out

// ---------------------------------------------------------------------------
// Varint / zigzag
// ---------------------------------------------------------------------------

private fun zigzag32(n: Int): Long = ((n shl 1) xor (n shr 31)).toLong() and 0xFFFFFFFFL
private fun zigzag64(n: Long): Long = (n shl 1) xor (n shr 63)
private fun unzigzag(n: Long): Long = (n ushr 1) xor -(n and 1L)

private fun ByteArrayOutputStream.writeVarint(value: Long) {
    var v = value
    while (true) {
        if ((v and 0x7FL.inv()) == 0L) {
            write(v.toInt())
            return
        }
        write(((v and 0x7FL) or 0x80L).toInt())
        v = v ushr 7
    }
}

// ---------------------------------------------------------------------------
// TBinary encoder
// ---------------------------------------------------------------------------

private fun ByteArrayOutputStream.writeI16Be(n: Int) {
    write((n ushr 8) and 0xFF); write(n and 0xFF)
}

private fun ByteArrayOutputStream.writeI32Be(n: Int) {
    write((n ushr 24) and 0xFF); write((n ushr 16) and 0xFF)
    write((n ushr 8) and 0xFF); write(n and 0xFF)
}

private fun ByteArrayOutputStream.writeI64Be(n: Long) {
    for (shift in 56 downTo 0 step 8) write(((n ushr shift) and 0xFF).toInt())
}

private fun ByteArrayOutputStream.encodeBinValue(v: TVal) {
    when (v) {
        is TVal.Bool -> write(if (v.v) 1 else 0)
        is TVal.Byte8 -> write(v.v and 0xFF)
        is TVal.Dbl -> writeI64Be(java.lang.Double.doubleToLongBits(v.v))
        is TVal.I32 -> writeI32Be(v.v)
        is TVal.I64 -> writeI64Be(v.v)
        is TVal.Bin -> { writeI32Be(v.v.size); write(v.v) }
        is TVal.Struct -> encodeBinFields(v.fields)
        is TVal.Mp -> {
            write(v.kType); write(v.vType); writeI32Be(v.entries.size)
            v.entries.forEach { (k, value) -> encodeBinValue(k); encodeBinValue(value) }
        }
        is TVal.Lst -> { write(v.eType); writeI32Be(v.items.size); v.items.forEach { encodeBinValue(it) } }
        is TVal.St -> { write(v.eType); writeI32Be(v.items.size); v.items.forEach { encodeBinValue(it) } }
    }
}

private fun ByteArrayOutputStream.encodeBinFields(fs: List<TField>) {
    for (f in fs) {
        write(f.value.type)
        writeI16Be(f.id)
        encodeBinValue(f.value)
    }
    write(0)
}

/**
 * Encode a bare TBinary struct — no CALL message envelope.
 *
 * Used for payloads nested inside other encodings, such as the X-Talk-Meta
 * header that authorises a sealed media download.
 */
fun packBinaryStruct(params: List<TField>): ByteArray =
    ByteArrayOutputStream().apply { encodeBinFields(params) }.toByteArray()

/** Encode a TBinary Thrift CALL message. */
fun packBinary(methodName: String, params: List<TField>, seqId: Int = 0): ByteArray {
    val out = ByteArrayOutputStream()
    val name = methodName.toByteArray(StandardCharsets.UTF_8)
    out.write(byteArrayOf(0x80.toByte(), 0x01, 0x00, 0x01))
    out.writeI32Be(name.size)
    out.write(name)
    out.writeI32Be(seqId)
    out.encodeBinFields(params)
    return out.toByteArray()
}

// ---------------------------------------------------------------------------
// TCompact encoder
// ---------------------------------------------------------------------------

private fun ByteArrayOutputStream.encodeCompactValue(v: TVal) {
    when (v) {
        is TVal.Bool -> write(if (v.v) 0x01 else 0x02)
        is TVal.Byte8 -> write(v.v and 0xFF)
        is TVal.Dbl -> {
            val bits = java.lang.Double.doubleToLongBits(v.v)
            for (shift in 0..56 step 8) write(((bits ushr shift) and 0xFF).toInt())  // little-endian
        }
        is TVal.I32 -> writeVarint(zigzag32(v.v))
        is TVal.I64 -> writeVarint(zigzag64(v.v))
        is TVal.Bin -> { writeVarint(v.v.size.toLong()); write(v.v) }
        is TVal.Struct -> encodeCompactFields(v.fields)
        is TVal.Mp -> {
            if (v.entries.isEmpty()) { write(0); return }
            writeVarint(v.entries.size.toLong())
            write((CTYPES.getValue(v.kType) shl 4) or CTYPES.getValue(v.vType))
            v.entries.forEach { (k, value) -> encodeCompactValue(k); encodeCompactValue(value) }
        }
        is TVal.Lst -> encodeCompactSeq(v.eType, v.items)
        is TVal.St -> encodeCompactSeq(v.eType, v.items)
    }
}

private fun ByteArrayOutputStream.encodeCompactSeq(eType: Int, items: List<TVal>) {
    val ctype = CTYPES.getValue(eType)
    if (items.size <= 14) {
        write((items.size shl 4) or ctype)
    } else {
        write(0xF0 or ctype)
        writeVarint(items.size.toLong())
    }
    items.forEach { encodeCompactValue(it) }
}

private fun ByteArrayOutputStream.encodeCompactFields(fs: List<TField>) {
    var lastFid = 0
    for (f in fs) {
        // A bool's value lives in the type nibble itself, so it never gets a
        // separate value write.
        val ctype = if (f.value is TVal.Bool) {
            if (f.value.v) 0x01 else 0x02
        } else {
            CTYPES.getValue(f.value.type)
        }
        val delta = f.id - lastFid
        if (delta in 1..15) {
            write((delta shl 4) or ctype)
        } else {
            write(ctype)
            writeVarint(zigzag32(f.id))
        }
        lastFid = f.id
        if (f.value !is TVal.Bool) encodeCompactValue(f.value)
    }
    write(0)
}

/** Encode a TCompact Thrift CALL message. */
fun packCompact(methodName: String, params: List<TField>, seqId: Int = 0): ByteArray {
    val out = ByteArrayOutputStream()
    // protocol_id=0x82, CALL(1) with version 1 -> 0x21
    out.write(0x82); out.write(0x21)
    out.writeVarint(seqId.toLong())
    val name = methodName.toByteArray(StandardCharsets.UTF_8)
    out.writeVarint(name.size.toLong())
    out.write(name)
    out.encodeCompactFields(params)
    return out.toByteArray()
}

// ---------------------------------------------------------------------------
// Decoders
// ---------------------------------------------------------------------------

class LineServiceError(val code: Int, override val message: String) : Exception(message) {
    override fun toString() = "[code=$code] $message"
}

private fun makeLineError(fields: Any?): LineServiceError {
    val m = fields as? Map<*, *> ?: return LineServiceError(0, fields.toString())
    val code = (m[1] as? Number)?.toInt() ?: 0
    val msg = m[2]?.toString() ?: m.toString()
    return LineServiceError(code, msg)
}

/** Decode bytes as UTF-8, falling back to the raw array when they are not text. */
private fun decodeStr(raw: ByteArray): Any = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(raw))
        .toString()
} catch (e: CharacterCodingException) {
    raw
}

private class BinReader(private val d: ByteArray) {
    private var p = 0

    fun read(n: Int): ByteArray {
        val end = minOf(p + n, d.size)
        val chunk = d.copyOfRange(p, end)
        p = end
        return chunk
    }

    fun readI8(): Int = d[p++].toInt()
    fun readU8(): Int = d[p++].toInt() and 0xFF
    fun readI16(): Int = (readU8() shl 8 or readU8()).toShort().toInt()
    fun readI32(): Int = (readU8() shl 24) or (readU8() shl 16) or (readU8() shl 8) or readU8()
    fun readI64(): Long {
        var v = 0L
        repeat(8) { v = (v shl 8) or readU8().toLong() }
        return v
    }

    fun readValue(ttype: Int): Any? = when (ttype) {
        TType.BOOL -> readI8() != 0
        TType.BYTE -> readI8()
        TType.DOUBLE -> java.lang.Double.longBitsToDouble(readI64())
        TType.I16 -> readI16()
        TType.I32 -> readI32()
        TType.I64 -> readI64()
        TType.STRING -> decodeStr(read(readI32()))
        TType.STRUCT -> readStruct()
        TType.MAP -> {
            val ktype = readI8(); val vtype = readI8(); val n = readI32()
            val m = LinkedHashMap<Any?, Any?>()
            repeat(n) { m[readValue(ktype)] = readValue(vtype) }
            m
        }
        TType.SET, TType.LIST -> {
            val etype = readI8(); val n = readI32()
            (0 until n).map { readValue(etype) }
        }
        else -> throw IllegalArgumentException("Unknown TBinary type: $ttype")
    }

    fun readStruct(): Map<Int, Any?> {
        val result = LinkedHashMap<Int, Any?>()
        while (true) {
            val ttype = readI8()
            if (ttype == 0) break
            result[readI16()] = readValue(ttype)
        }
        return result
    }

    fun unpack(): Map<Int, Any?> {
        val sz = readI32()
        require(sz < 0) { "Old-style TBinary message not supported: sz=$sz" }
        val version = sz and 0xFFFF0000.toInt()
        require(version == 0x80010000.toInt()) { "Bad TBinary version" }
        readI32().let { nameLen -> read(nameLen) }   // method name
        readI32()                                    // seq id
        val ttype = readI8()
        if (ttype == 0) return emptyMap()
        val fid = readI16()
        val value = readValue(ttype)
        if (fid == 0) {
            @Suppress("UNCHECKED_CAST")
            return (value as? Map<Int, Any?>) ?: mapOf(0 to value)
        }
        throw makeLineError(value)
    }
}

private class CompactReader(private val d: ByteArray) {
    private var p = 0
    private var boolVal: Boolean? = null

    fun read(n: Int): ByteArray {
        val end = minOf(p + n, d.size)
        val chunk = d.copyOfRange(p, end)
        p = end
        return chunk
    }

    fun readU8(): Int = d[p++].toInt() and 0xFF
    fun readI8(): Int = d[p++].toInt()

    fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = readU8()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun readI16(): Int = unzigzag(readVarint()).toInt()
    fun readI32(): Int = unzigzag(readVarint()).toInt()
    fun readI64(): Long = unzigzag(readVarint())

    fun readDouble(): Double {
        var bits = 0L
        val raw = read(8)
        for (i in 7 downTo 0) bits = (bits shl 8) or (raw[i].toLong() and 0xFF)  // little-endian
        return java.lang.Double.longBitsToDouble(bits)
    }

    fun readValue(ctype: Int): Any? {
        if (ctype == 0x01) return true
        if (ctype == 0x02) return false
        val ttype = TTYPES[ctype] ?: throw IllegalArgumentException("Unknown compact ctype: $ctype")
        return when (ttype) {
            TType.BOOL -> boolVal
            TType.BYTE -> readI8()
            TType.DOUBLE -> readDouble()
            TType.I16 -> readI16()
            TType.I32 -> readI32()
            TType.I64 -> readI64()
            TType.STRING -> decodeStr(read(readVarint().toInt()))
            TType.STRUCT -> readStruct()
            TType.MAP -> readMap()
            TType.SET, TType.LIST -> readList()
            else -> throw IllegalArgumentException("Unsupported compact ttype: $ttype")
        }
    }

    fun readStruct(): Map<Int, Any?> {
        val result = LinkedHashMap<Int, Any?>()
        var lastFid = 0
        while (true) {
            if (p >= d.size) break
            val b = readU8()
            if (b == 0) break
            val ctype = b and 0x0F
            val delta = b shr 4
            val fid = if (delta == 0) readI16() else lastFid + delta
            lastFid = fid
            if (ctype == 0x01 || ctype == 0x02) boolVal = (ctype == 0x01)
            result[fid] = readValue(ctype)
        }
        return result
    }

    private fun readMap(): Map<Any?, Any?> {
        val n = readVarint().toInt()
        if (n == 0) return emptyMap()
        val types = readU8()
        val ktype = types shr 4
        val vtype = types and 0x0F
        val m = LinkedHashMap<Any?, Any?>()
        repeat(n) { m[readValue(ktype)] = readValue(vtype) }
        return m
    }

    private fun readList(): List<Any?> {
        val b = readU8()
        var n = b shr 4
        val etype = b and 0x0F
        if (n == 15) n = readVarint().toInt()
        return (0 until n).map { readValue(etype) }
    }

    fun unpack(): Map<Int, Any?> {
        val protoId = readU8()
        require(protoId == 0x82) { "Bad compact protocol id: $protoId" }
        val verType = readU8()
        require(verType and 0x1F == 1) { "Bad compact version" }
        readVarint()                       // seq id
        read(readVarint().toInt())         // method name
        if (p >= d.size) return emptyMap()
        val b = readU8()
        if (b == 0) return emptyMap()
        val ctype = b and 0x0F
        val delta = b shr 4
        val fid = if (delta != 0) delta else readI16()
        if (ctype == 0x01 || ctype == 0x02) boolVal = (ctype == 0x01)
        val value = readValue(ctype)
        if (fid == 0) {
            @Suppress("UNCHECKED_CAST")
            return (value as? Map<Int, Any?>) ?: mapOf(0 to value)
        }
        throw makeLineError(value)
    }
}

/** Decode a TBinary response.  Throws [LineServiceError] on a Thrift exception. */
fun unpackBinary(data: ByteArray): Map<Int, Any?> = BinReader(data).unpack()

/** Decode a TCompact response.  Throws [LineServiceError] on a Thrift exception. */
fun unpackCompact(data: ByteArray): Map<Int, Any?> = CompactReader(data).unpack()

/**
 * Decode a bare TCompact struct — no message envelope, no exception check.
 * Used for Thrift-encoded payloads that are not RPC responses, such as the
 * E2EE key chain handed over by the primary device.
 */
fun unpackCompactStruct(data: ByteArray): Map<Int, Any?> = CompactReader(data).readStruct()

// ---------------------------------------------------------------------------
// Field access helpers
// ---------------------------------------------------------------------------

/** Safe nested field lookup, mirroring the Python `_check` helper. */
fun tGet(data: Any?, vararg keys: Int): Any? {
    var cur = data
    for (k in keys) {
        val m = cur as? Map<*, *> ?: return null
        cur = m[k]
    }
    return cur
}

fun tStr(data: Any?, vararg keys: Int): String? = when (val v = tGet(data, *keys)) {
    is String -> v
    is ByteArray -> String(v, StandardCharsets.UTF_8)
    else -> null
}

fun tInt(data: Any?, vararg keys: Int): Int? = (tGet(data, *keys) as? Number)?.toInt()
fun tLong(data: Any?, vararg keys: Int): Long? = (tGet(data, *keys) as? Number)?.toLong()
fun tBool(data: Any?, vararg keys: Int): Boolean = tGet(data, *keys) == true

@Suppress("UNCHECKED_CAST")
fun tList(data: Any?, vararg keys: Int): List<Any?> = tGet(data, *keys) as? List<Any?> ?: emptyList()

@Suppress("UNCHECKED_CAST")
fun tMap(data: Any?, vararg keys: Int): Map<Any?, Any?> = tGet(data, *keys) as? Map<Any?, Any?> ?: emptyMap()

/**
 * Thrift BINARY fields come back as [String] when the bytes happen to be valid
 * UTF-8, so anything genuinely binary has to be coerced back.
 */
fun asBytes(v: Any?): ByteArray = when (v) {
    null -> ByteArray(0)
    is ByteArray -> v
    is String -> v.toByteArray(StandardCharsets.UTF_8)
    else -> throw IllegalArgumentException("Not binary: ${v.javaClass}")
}
