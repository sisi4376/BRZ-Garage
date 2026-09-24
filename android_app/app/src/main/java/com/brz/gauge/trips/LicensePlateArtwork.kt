package com.brz.gauge.trips

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.LruCache

/** Draws one canonical, flat GA 36-2018 small conventional-car plate artwork. */
object LicensePlateArtwork {
    /*
     * Some province templates are only about 77 x 150 px. Drawing those JPEGs
     * directly into an xxhdpi preview enlarged their hard black/white pixels
     * and made the plate look soft. Build a supersampled alpha mask once, then
     * let Canvas downsample that mask at the actual display or perspective
     * size. The bounded cache keeps a normal seven-character plate below the
     * memory limit while avoiding work on every frame.
     */
    private const val GLYPH_MASK_WIDTH = 360
    private const val GLYPH_MASK_HEIGHT = 720
    private const val GLYPH_CACHE_KB = 12 * 1024
    private const val EDGE_BLACK = 72
    private const val EDGE_WHITE = 200

    private val glyphCache = object : LruCache<Char, Bitmap>(GLYPH_CACHE_KB) {
        override fun sizeOf(key: Char, value: Bitmap): Int =
            (value.byteCount / 1024).coerceAtLeast(1)
    }
    private val glyphPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
    )

    fun draw(context: Context, canvas: Canvas, value: GeneratedLicensePlate, bounds: RectF) {
        val sx = bounds.width() / LicensePlateGenerator.WIDTH_MM
        val sy = bounds.height() / LicensePlateGenerator.HEIGHT_MM
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(sx, sy)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 82, 168)
        canvas.drawRoundRect(RectF(0f, 0f, 440f, 140f), 10f, 10f, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(5f, 5f, 435f, 135f), 6.2f, 6.2f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(202, 211, 221)
        for (x in floatArrayOf(115f, 325f)) {
            for (y in floatArrayOf(12.5f, 127.5f)) {
                canvas.drawRoundRect(RectF(x - 7.5f, y - 4f, x + 7.5f, y + 4f), 4f, 4f, paint)
            }
        }

        drawGlyph(context, canvas, value.province, 15f)
        drawGlyph(context, canvas, value.authority, 72f)
        paint.color = Color.WHITE
        canvas.drawCircle(134f, 70f, 5f, paint)
        value.serial.forEachIndexed { index, character ->
            drawGlyph(context, canvas, character, 151f + index * 57f)
        }
        canvas.restore()
    }

    fun renderBitmap(context: Context, value: GeneratedLicensePlate): Bitmap =
        Bitmap.createBitmap(880, 280, Bitmap.Config.ARGB_8888).also { bitmap ->
            draw(context, Canvas(bitmap), value, RectF(0f, 0f, 880f, 280f))
        }

    private fun drawGlyph(context: Context, canvas: Canvas, character: Char, leftMm: Float) {
        val bitmap = glyphBitmap(context, character) ?: return
        canvas.drawBitmap(bitmap, null, RectF(leftMm, 25f, leftMm + 45f, 115f), glyphPaint)
    }

    private fun glyphBitmap(context: Context, character: Char): Bitmap? {
        val assetCharacter = when (character) {
            'I' -> '1'
            'O' -> '0'
            else -> character
        }
        glyphCache.get(assetCharacter)?.let { return it }
        return runCatching {
            val source = context.assets.open("license_plate_font/140_${assetCharacter}.jpg").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply {
                    inScaled = false
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            } ?: return@runCatching null
            buildHighResolutionMask(source)
        }.getOrNull()?.also { glyphCache.put(assetCharacter, it) }
    }

    private fun buildHighResolutionMask(source: Bitmap): Bitmap {
        val supersampled = Bitmap.createScaledBitmap(
            source,
            GLYPH_MASK_WIDTH,
            GLYPH_MASK_HEIGHT,
            true,
        )
        if (supersampled !== source) source.recycle()

        val pixels = IntArray(GLYPH_MASK_WIDTH * GLYPH_MASK_HEIGHT)
        supersampled.getPixels(
            pixels,
            0,
            GLYPH_MASK_WIDTH,
            0,
            0,
            GLYPH_MASK_WIDTH,
            GLYPH_MASK_HEIGHT,
        )
        val edgeRange = EDGE_WHITE - EDGE_BLACK
        for (index in pixels.indices) {
            val color = pixels[index]
            val luminance = (Color.red(color) * 77 +
                Color.green(color) * 150 + Color.blue(color) * 29) shr 8
            val alpha = ((EDGE_WHITE - luminance) * 255 / edgeRange).coerceIn(0, 255)
            pixels[index] = (alpha shl 24) or 0x00FFFFFF
        }
        supersampled.recycle()

        return Bitmap.createBitmap(
            GLYPH_MASK_WIDTH,
            GLYPH_MASK_HEIGHT,
            Bitmap.Config.ARGB_8888,
        ).apply {
            setPixels(
                pixels,
                0,
                GLYPH_MASK_WIDTH,
                0,
                0,
                GLYPH_MASK_WIDTH,
                GLYPH_MASK_HEIGHT,
            )
        }
    }
}
