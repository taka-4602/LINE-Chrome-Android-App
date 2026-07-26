package com.example.line_chrome.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import java.io.File
import kotlin.math.absoluteValue

/**
 * Stand-in photographs for the demo account, drawn on the device.
 *
 * The alternative was bundling real images, which either means shipping
 * somebody's photograph in the repo or fetching one at demo time — and a
 * conversation full of empty grey rectangles because the venue Wi-Fi is bad is
 * exactly the failure a demo cannot afford.  These are deterministic in the
 * message id, so the same bubble draws the same picture every launch.
 */
object DemoMedia {

    /** Background pairs, all dark enough for white foreground detail. */
    private val palettes = listOf(
        0xFF1D976C.toInt() to 0xFF93F9B9.toInt(),
        0xFF4568DC.toInt() to 0xFFB06AB3.toInt(),
        0xFFEB3349.toInt() to 0xFFF45C43.toInt(),
        0xFF2C3E50.toInt() to 0xFF4CA1AF.toInt(),
        0xFFDA4453.toInt() to 0xFF89216B.toInt(),
        0xFF3A1C71.toInt() to 0xFFFFAF7B.toInt(),
    )

    private val glyphs = listOf("🌸", "🍜", "🏔", "☕", "🐈", "🎸", "🌇", "🍰")

    /**
     * Write a generated photo for [seed] into [file] unless it is already there.
     *
     * @param preview draw the smaller variant, which is what a chat bubble asks
     *   for first — a real thumbnail is a separate object, so the demo has two
     *   as well.
     */
    fun ensure(file: File, seed: String, preview: Boolean): File {
        if (file.exists() && file.length() > 0) return file
        file.parentFile?.mkdirs()

        val width = if (preview) 480 else 1080
        val height = if (preview) 320 else 720
        val bitmap = draw(seed, width, height)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun draw(seed: String, width: Int, height: Int): Bitmap {
        val slot = seed.hashCode().absoluteValue
        val (top, bottom) = palettes[slot % palettes.size]
        val glyph = glyphs[(slot / 7) % glyphs.size]

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f, 0f, width.toFloat(), height.toFloat(),
            top, bottom, Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null

        // Two soft discs, so the result reads as a picture rather than a
        // flat colour swatch at thumbnail size.
        paint.color = Color.argb(38, 255, 255, 255)
        canvas.drawCircle(width * 0.22f, height * 0.28f, height * 0.34f, paint)
        canvas.drawCircle(width * 0.82f, height * 0.78f, height * 0.30f, paint)

        paint.color = Color.WHITE
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = height * 0.42f
        // Centre on the glyph's own box: baseline placement alone leaves an
        // emoji sitting noticeably low.
        val bounds = android.graphics.Rect()
        paint.getTextBounds(glyph, 0, glyph.length, bounds)
        canvas.drawText(
            glyph,
            width / 2f,
            height / 2f - bounds.exactCenterY(),
            paint,
        )

        paint.textAlign = Paint.Align.LEFT
        paint.textSize = height * 0.055f
        paint.color = Color.argb(150, 255, 255, 255)
        canvas.drawText("DEMO", width * 0.04f, height * 0.95f, paint)

        return bitmap
    }
}
