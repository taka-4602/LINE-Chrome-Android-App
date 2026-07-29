package com.taka4602.line_chrome.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/**
 * Finding the URLs in message text, and turning them into tappable links.
 *
 * Deliberately plain Kotlin with no Android dependency — `android.util.Patterns`
 * is the obvious tool and is unusable here, because it is a framework class that
 * unit tests see as a stub.  Link detection is exactly the sort of thing that
 * wants tests, so it is written to be testable.
 */

/**
 * A scheme, or a bare `www.`, and then everything up to whitespace.
 *
 * Bare domains are **not** matched.  Detecting `example.com` without a scheme
 * means deciding that `Node.js`, `3.5`, `README.md` and every Japanese sentence
 * ending in a full stop are not domains, and getting that wrong turns ordinary
 * words blue and makes them open a browser.  A scheme or `www.` is what people
 * actually paste, and it is unambiguous.
 */
private val URL_PATTERN = Regex("""(?:https?://|www\.)\S+""", RegexOption.IGNORE_CASE)

private val PREFIX_PATTERN = Regex("""^(?:https?://|www\.)""", RegexOption.IGNORE_CASE)

/**
 * Punctuation that ends a sentence rather than a URL.
 *
 * The Japanese marks matter as much as the ASCII ones here: a link pasted into
 * a Japanese sentence is routinely followed by `。` or wrapped in `「」`, and
 * `\S+` swallows all of it.
 */
private const val TRAILING = ".,;:!?'\"、。，．！？…"

/** Closing brackets, each with the opener that would justify keeping it. */
private val CLOSERS = mapOf(
    ')' to '(', ']' to '[', '}' to '{', '>' to '<',
    '）' to '（', '」' to '「', '』' to '『', '】' to '【', '＞' to '＜',
)

/**
 * The ranges in [text] that are URLs.
 *
 * Ranges rather than strings, because the caller has to splice the untouched
 * text back in around them.
 */
internal fun findUrls(text: String): List<IntRange> =
    URL_PATTERN.findAll(text).mapNotNull { match ->
        val url = trimTail(match.value)
        val prefix = PREFIX_PATTERN.find(url)?.value?.length ?: return@mapNotNull null
        // "https://" with nothing after it, once the punctuation is off, is a
        // link to nowhere.
        if (url.length <= prefix) return@mapNotNull null
        match.range.first..(match.range.first + url.length - 1)
    }.toList()

/**
 * Drop the trailing characters that belong to the sentence, not the URL.
 *
 * A closing bracket is kept when something inside the URL opened it, so
 * Wikipedia-style `..._(disambiguation)` survives while `(see https://x.com)`
 * loses its final paren.
 */
private fun trimTail(url: String): String {
    var end = url.length
    while (end > 0) {
        val c = url[end - 1]
        val opener = CLOSERS[c]
        val head = url.take(end - 1)
        val drop = when {
            c in TRAILING -> true
            opener != null -> head.count { it == opener } <= head.count { it == c }
            else -> false
        }
        if (!drop) break
        end--
    }
    return url.take(end)
}

/** What to actually open.  A bare `www.` link has no scheme to hand to a browser. */
internal fun linkHref(url: String): String =
    if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url

/**
 * [text] with its URLs styled, and the means to tell which one a tap landed on.
 *
 * The obvious tool is `LinkAnnotation.Url`, which brings its own tap handling
 * and routes through the ambient `UriHandler`.  It is not used here because it
 * also *consumes* the gesture: on a message that is nothing but a URL — which
 * is most links people send — the whole bubble becomes link, and long-press
 * and double-tap stop reaching it.  Reply, Copy and Share would be
 * unreachable on exactly the messages most likely to want sharing.
 *
 * So the spans carry style only, and [hrefAt] lets the caller resolve a tap
 * against the text layout and decide for itself.  The cost is that these links
 * are not announced as links by TalkBack, which `LinkAnnotation` would have
 * done for free.
 */
class LinkedText(
    private val source: String,
    val annotated: AnnotatedString,
    val urls: List<IntRange>,
) {
    /** The URL at a character [offset], or null if that character is ordinary text. */
    fun hrefAt(offset: Int): String? = urls.firstOrNull { offset in it }
        ?.let { linkHref(source.substring(it.first, it.last + 1)) }
}

fun linkedText(text: String, linkStyle: SpanStyle): LinkedText {
    val urls = findUrls(text)
    if (urls.isEmpty()) return LinkedText(text, AnnotatedString(text), emptyList())
    val annotated = buildAnnotatedString {
        var cursor = 0
        for (range in urls) {
            append(text.substring(cursor, range.first))
            withStyle(linkStyle) { append(text.substring(range.first, range.last + 1)) }
            cursor = range.last + 1
        }
        append(text.substring(cursor))
    }
    return LinkedText(text, annotated, urls)
}

/**
 * Message text whose URLs are tappable, without giving up the bubble gestures.
 *
 * All three gestures are handled here rather than left to the enclosing
 * bubble, because a pointer handler on the text consumes what lands inside it
 * either way — so the choice is to handle them all or to lose them over the
 * text.  A tap that misses a URL does nothing, which is what tapping a message
 * did before.
 */
@Composable
fun LinkableText(
    text: String,
    style: TextStyle,
    color: Color,
    linkStyle: SpanStyle,
    onDoubleTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val linked = remember(text, linkStyle) { linkedText(text, linkStyle) }
    val uriHandler = LocalUriHandler.current
    var layout by remember(linked) { mutableStateOf<TextLayoutResult?>(null) }

    Text(
        text = linked.annotated,
        style = style,
        color = color,
        onTextLayout = { layout = it },
        modifier = Modifier.pointerInput(linked) {
            detectTapGestures(
                onTap = { position ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(position)
                    // getOffsetForPosition clamps to the nearest character, so
                    // the blank space beside a line that ends in a URL resolves
                    // onto that URL.  Require the tap to be inside the glyph.
                    if (offset !in linked.annotated.indices) return@detectTapGestures
                    if (!result.getBoundingBox(offset).contains(position)) return@detectTapGestures
                    val href = linked.hrefAt(offset) ?: return@detectTapGestures
                    runCatching { uriHandler.openUri(href) }
                },
                onDoubleTap = { onDoubleTap() },
                onLongPress = { onLongPress() },
            )
        },
    )
}
