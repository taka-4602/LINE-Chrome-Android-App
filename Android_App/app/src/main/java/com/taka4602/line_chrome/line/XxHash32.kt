package com.taka4602.line_chrome.line

/**
 * xxHash32, needed for the 4-byte MAC legy puts after the AES-CBC body (`x-le: 18`).
 *
 * Python's `xxhash.xxh32` is used there in streaming mode, but streaming and
 * one-shot produce the same digest, so the caller concatenates instead.
 */
object XxHash32 {
    private const val P1 = -1640531535   // 2654435761
    private const val P2 = -2048144777   // 2246822519
    private const val P3 = -1028477379   // 3266489917
    private const val P4 = 668265263
    private const val P5 = 374761393

    private fun rotl(v: Int, r: Int) = (v shl r) or (v ushr (32 - r))
    private fun round(acc: Int, lane: Int) = rotl(acc + lane * P2, 13) * P1

    private fun lane(d: ByteArray, i: Int): Int =
        (d[i].toInt() and 0xFF) or
            ((d[i + 1].toInt() and 0xFF) shl 8) or
            ((d[i + 2].toInt() and 0xFF) shl 16) or
            ((d[i + 3].toInt() and 0xFF) shl 24)

    fun digest(data: ByteArray, seed: Int = 0): Int {
        var h: Int
        var p = 0
        val len = data.size

        if (len >= 16) {
            var v1 = seed + P1 + P2
            var v2 = seed + P2
            var v3 = seed
            var v4 = seed - P1
            while (p <= len - 16) {
                v1 = round(v1, lane(data, p)); p += 4
                v2 = round(v2, lane(data, p)); p += 4
                v3 = round(v3, lane(data, p)); p += 4
                v4 = round(v4, lane(data, p)); p += 4
            }
            h = rotl(v1, 1) + rotl(v2, 7) + rotl(v3, 12) + rotl(v4, 18)
        } else {
            h = seed + P5
        }

        h += len

        while (p <= len - 4) {
            h += lane(data, p) * P3
            h = rotl(h, 17) * P4
            p += 4
        }
        while (p < len) {
            h += (data[p].toInt() and 0xFF) * P5
            h = rotl(h, 11) * P1
            p++
        }

        h = h xor (h ushr 15)
        h *= P2
        h = h xor (h ushr 13)
        h *= P3
        h = h xor (h ushr 16)
        return h
    }

    /** The digest as 4 big-endian bytes, matching `bytes.fromhex(h.hexdigest())`. */
    fun digestBytes(data: ByteArray, seed: Int = 0): ByteArray {
        val h = digest(data, seed)
        return byteArrayOf(
            (h ushr 24).toByte(), (h ushr 16).toByte(), (h ushr 8).toByte(), h.toByte()
        )
    }
}
