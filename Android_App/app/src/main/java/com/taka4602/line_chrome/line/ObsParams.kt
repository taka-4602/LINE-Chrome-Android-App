package com.taka4602.line_chrome.line

/**
 * The JSON that goes into the `X-Obs-Params` header, byte-for-byte as Python's
 * `json.dumps` would render it.
 *
 * Two defaults of `json.dumps` are load-bearing and neither is obvious:
 *
 *  - it separates with `", "` and `": "`, spaces included;
 *  - `ensure_ascii=True` escapes every non-ASCII character to `\uXXXX`.
 *
 * The second one matters. A filename in Japanese is entirely normal here, and
 * emitting it as raw UTF-8 inside a base64 header only works if the server
 * decodes that header as UTF-8. The reference client never finds out, because
 * it never sends a byte above 0x7E. Neither do we.
 *
 * `org.json.JSONObject` is no use for this: it does not order keys and it does
 * not escape non-ASCII.
 */
fun obsParamsJson(vararg pairs: Pair<String, String>): String =
    pairs.joinToString(", ", "{", "}") { (key, value) ->
        "${jsonString(key)}: ${jsonString(value)}"
    }

/**
 * Where an attachment lives in object storage.
 *
 * Plain and sealed media are addressed completely differently, and the
 * thumbnail rule is the part that catches people out:
 *
 *  - plain media hangs off the message id, and a thumbnail is a `/preview` path
 *    segment on the same object;
 *  - a sealed object lives under the namespace and object id the *sender*
 *    recorded, and its thumbnail is a **separate object** with `__ud-preview`
 *    appended to the id. There is no path variant, because the server cannot
 *    resize something it cannot read — which is also why [size] applies to
 *    plain media only.
 */
fun mediaObjectUrl(
    host: String,
    messageId: String,
    oid: String?,
    sid: String?,
    sealed: Boolean,
    preview: Boolean,
    size: String? = null,
): String {
    if (sealed && oid != null && sid != null) {
        val objectId = if (preview) "${oid}__ud-preview" else oid
        return "$host/oa/r/talk/$sid/$objectId"
    }
    val base = "$host/oa/r/talk/m/$messageId"
    return when {
        preview -> "$base/preview"
        size != null -> "$base/$size"
        else -> base
    }
}

private fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (ch in value) {
        when {
            ch == '"' -> out.append("\\\"")
            ch == '\\' -> out.append("\\\\")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch == '\t' -> out.append("\\t")
            ch == '\b' -> out.append("\\b")
            ch == '\u000C' -> out.append("\\f")
            // Anything outside printable ASCII goes out as an escape, which is
            // what ensure_ascii=True does.  Surrogate pairs escape as two
            // units, exactly as Python emits them.
            ch.code < 0x20 || ch.code > 0x7E -> out.append("\\u%04x".format(ch.code))
            else -> out.append(ch)
        }
    }
    out.append('"')
    return out.toString()
}
