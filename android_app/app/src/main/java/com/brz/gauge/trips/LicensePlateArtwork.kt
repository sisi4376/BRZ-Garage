package com.brz.gauge.trips

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.LruCache
import org.json.JSONObject

/** Draws one canonical, flat GA 36-2018 small conventional-car plate artwork. */
object LicensePlateArtwork {
    private const val GLYPH_ASSET = "license_plate_vector/glyphs.json"
    private const val GLYPH_CACHE_SIZE = 67
    private val plateBlue = Color.rgb(0, 82, 168)
    private val glyphLock = Any()
    private var glyphTable: JSONObject? = null
    private val glyphCache = LruCache<Char, List<GlyphLayer>>(GLYPH_CACHE_SIZE)

    private data class GlyphLayer(
        val path: Path,
        val cutout: Boolean,
    )

    fun draw(context: Context, canvas: Canvas, value: GeneratedLicensePlate, bounds: RectF) {
        val sx = bounds.width() / LicensePlateGenerator.WIDTH_MM
        val sy = bounds.height() / LicensePlateGenerator.HEIGHT_MM
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        canvas.translate(bounds.left, bounds.top)
        canvas.scale(sx, sy)

        paint.style = Paint.Style.FILL
        paint.color = plateBlue
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

        drawGlyph(context, canvas, value.province, 15f, paint)
        drawGlyph(context, canvas, value.authority, 72f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(134f, 70f, 5f, paint)
        value.serial.forEachIndexed { index, character ->
            drawGlyph(context, canvas, character, 151f + index * 57f, paint)
        }
        canvas.restore()
    }

    /**
     * Produces a 4 px/mm intermediate plate for the home-screen perspective pass.
     * The final installed plate is small, but the extra source samples keep the
     * outlined strokes and corners crisp on GPU implementations that minify a
     * perspective texture less accurately than an axis-aligned bitmap.
     */
    fun renderBitmap(context: Context, value: GeneratedLicensePlate): Bitmap =
        Bitmap.createBitmap(1760, 560, Bitmap.Config.ARGB_8888).also { bitmap ->
            draw(context, Canvas(bitmap), value, RectF(0f, 0f, 1760f, 560f))
            bitmap.setHasMipMap(true)
            bitmap.prepareToDraw()
        }

    private fun drawGlyph(
        context: Context,
        canvas: Canvas,
        character: Char,
        leftMm: Float,
        paint: Paint,
    ) {
        val layers = glyphLayers(context, character) ?: return
        canvas.save()
        canvas.translate(leftMm, 25f)
        paint.style = Paint.Style.FILL
        for (layer in layers) {
            paint.color = if (layer.cutout) plateBlue else Color.WHITE
            canvas.drawPath(layer.path, paint)
        }
        canvas.restore()
    }

    private fun glyphLayers(context: Context, character: Char): List<GlyphLayer>? {
        glyphCache.get(character)?.let { return it }
        return synchronized(glyphLock) {
            glyphCache.get(character) ?: runCatching {
                val table = glyphTable ?: context.assets.open(GLYPH_ASSET).bufferedReader().use {
                    JSONObject(it.readText()).getJSONObject("glyphs")
                }.also { glyphTable = it }
                val sourceLayers = table.getJSONArray(character.toString())
                List(sourceLayers.length()) { index ->
                    val sourceLayer = sourceLayers.getJSONObject(index)
                    GlyphLayer(
                        path = parsePathData(sourceLayer.getString("path")),
                        cutout = sourceLayer.getBoolean("cutout"),
                    )
                }
            }.getOrNull()?.also { glyphCache.put(character, it) }
        }
    }

    /** Parses the absolute M/L/C/Z subset emitted by the asset generator. */
    private fun parsePathData(data: String): Path {
        val path = Path()
        var index = 0

        fun skipSeparators() {
            while (index < data.length && (data[index] == ',' || data[index].isWhitespace())) {
                index++
            }
        }

        fun number(): Float {
            skipSeparators()
            val start = index
            if (index < data.length && (data[index] == '-' || data[index] == '+')) index++
            while (index < data.length && data[index].isDigit()) index++
            if (index < data.length && data[index] == '.') {
                index++
                while (index < data.length && data[index].isDigit()) index++
            }
            require(index > start) { "Expected path number at $start" }
            return data.substring(start, index).toFloat()
        }

        while (index < data.length) {
            skipSeparators()
            if (index >= data.length) break
            when (val command = data[index++]) {
                'M' -> path.moveTo(number(), number())
                'L' -> path.lineTo(number(), number())
                'C' -> path.cubicTo(number(), number(), number(), number(), number(), number())
                'Z' -> path.close()
                else -> error("Unsupported glyph path command: $command")
            }
        }
        return path
    }
}
