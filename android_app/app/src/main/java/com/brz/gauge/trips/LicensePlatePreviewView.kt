package com.brz.gauge.trips

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.roundToInt

class LicensePlatePreviewView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var plate: GeneratedLicensePlate? = null

    init {
        contentDescription = "小型汽车号牌预览"
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun showPlate(value: GeneratedLicensePlate?) {
        plate = value
        contentDescription = value?.let { "号牌预览 ${it.compactText}" } ?: "小型汽车号牌预览"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(suggestedMinimumWidth)
        val ratioHeight = (width * LicensePlateGenerator.HEIGHT_MM.toFloat() /
            LicensePlateGenerator.WIDTH_MM).roundToInt()
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(ratioHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val value = plate
        if (value == null) {
            paint.style = Paint.Style.FILL
            paint.color = Color.rgb(0, 82, 168)
            canvas.drawRoundRect(RectF(0f, 0f, w, h), w * 10f / 440f, w * 10f / 440f, paint)
            paint.style = Paint.Style.FILL
            paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = h * 0.19f
            paint.color = 0xAAFFFFFF.toInt()
            canvas.drawText("输入车牌号后生成预览", w / 2f, h * 0.56f, paint)
            return
        }

        LicensePlateArtwork.draw(context, canvas, value, RectF(0f, 0f, w, h))

        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        paint.textSize = h * 0.075f
        paint.textAlign = Paint.Align.CENTER
        paint.color = 0x99FFFFFF.toInt()
        canvas.drawText("模拟预览", w / 2f, h * 0.92f, paint)
    }

}
