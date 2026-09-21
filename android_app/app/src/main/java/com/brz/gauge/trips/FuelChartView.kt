package com.brz.gauge.trips

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

class FuelChartView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var points = emptyList<FuelConsumptionPoint>()
    private var historicalAverage: Double? = null
    private val dateFormat = DateTimeFormatter.ofPattern("MM/dd")

    fun submit(value: List<FuelConsumptionPoint>, average: Double?) {
        points = value
        historicalAverage = average?.takeIf { it.isFinite() && it > 0.0 }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val left = 42f * density
        val right = width - 12f * density
        val top = 20f * density
        val bottom = height - 30f * density
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        paint.textSize = 10f * density
        if (points.isEmpty() && historicalAverage == null) {
            paint.color = Color.rgb(133, 141, 153)
            paint.textAlign = Paint.Align.CENTER
            canvas.drawText("至少录入两次且里程递增后生成曲线", width / 2f, height / 2f, paint)
            return
        }
        val values = points.map { it.litresPer100Km } + listOfNotNull(historicalAverage)
        val rawMin = values.min()
        val rawMax = values.max()
        val minY = max(0.0, floor((rawMin - 1.0) / 2.0) * 2.0)
        val maxY = max(minY + 4.0, ceil((rawMax + 1.0) / 2.0) * 2.0)
        paint.strokeWidth = density
        paint.textAlign = Paint.Align.RIGHT
        for (i in 0..4) {
            val y = bottom - (bottom - top) * i / 4f
            paint.color = Color.rgb(226, 229, 234)
            canvas.drawLine(left, y, right, y, paint)
            paint.color = Color.rgb(126, 135, 148)
            val value = minY + (maxY - minY) * i / 4.0
            canvas.drawText(String.format("%.1f", value), left - 7f * density, y + 4f * density, paint)
        }
        fun x(index: Int) = if (points.size == 1) (left + right) / 2f else left + (right - left) * index / (points.size - 1f)
        fun y(value: Double) = bottom - ((value - minY) / (maxY - minY) * (bottom - top)).toFloat()
        if (points.isNotEmpty()) {
            val line = Path()
            points.forEachIndexed { index, point ->
                if (index == 0) line.moveTo(x(index), y(point.litresPer100Km))
                else line.lineTo(x(index), y(point.litresPer100Km))
            }
            val fill = Path(line).apply { lineTo(x(points.lastIndex), bottom); lineTo(x(0), bottom); close() }
            paint.style = Paint.Style.FILL
            paint.color = Color.argb(28, 220, 49, 86)
            canvas.drawPath(fill, paint)
            paint.style = Paint.Style.STROKE
            paint.pathEffect = null
            paint.strokeWidth = 3f * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
            paint.color = Color.rgb(220, 49, 86)
            canvas.drawPath(line, paint)
            paint.style = Paint.Style.FILL
            points.forEachIndexed { index, point ->
                canvas.drawCircle(x(index), y(point.litresPer100Km), 4f * density, paint)
            }
        }

        historicalAverage?.let { average ->
            val averageY = y(average)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
            paint.color = Color.rgb(38, 96, 164)
            canvas.drawLine(left, averageY, right, averageY, paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            paint.textSize = 10f * density
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(String.format("历史均值 %.2f", average), right, averageY - 5f * density, paint)
        }

        if (points.isNotEmpty()) {
            paint.textSize = 10f * density
            paint.color = Color.rgb(109, 119, 132)
            paint.textAlign = Paint.Align.LEFT
            canvas.drawText(LocalDate.ofEpochDay(points.first().dateEpochDay).format(dateFormat), left, height - 7f * density, paint)
            paint.textAlign = Paint.Align.RIGHT
            canvas.drawText(LocalDate.ofEpochDay(points.last().dateEpochDay).format(dateFormat), right, height - 7f * density, paint)
        }
        contentDescription = historicalAverage?.let {
            "平均油耗变化曲线，共${points.size}个有效区间，历史平均油耗${String.format("%.2f", it)}升每百公里"
        } ?: "平均油耗变化曲线，共${points.size}个有效区间"
    }
}
