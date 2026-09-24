package com.brz.gauge.trips

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF

/** Draws one canonical, flat GA 36-2018 small conventional-car plate artwork. */
object LicensePlateArtwork {
    private val glyphCache = mutableMapOf<Char, Bitmap>()
    private val glyphPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
        // Convert each black-on-white source template to a white alpha mask.
        colorFilter = ColorMatrixColorFilter(floatArrayOf(
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 255f,
            0f, 0f, 0f, 0f, 255f,
            -0.3333f, -0.3333f, -0.3333f, 0f, 255f,
        ))
    }

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
        return glyphCache[assetCharacter] ?: runCatching {
            context.assets.open("license_plate_font/140_${assetCharacter}.jpg").use {
                BitmapFactory.decodeStream(it)
            }
        }.getOrNull()?.also { glyphCache[assetCharacter] = it }
    }
}
