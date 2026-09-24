package com.brz.gauge.trips

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class BookkeepingChartView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(109, 119, 132)
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
    }
    private var values = emptyList<MonthlyExpenseSummary>()

    fun submit(items: List<MonthlyExpenseSummary>) {
        values = items
        contentDescription = "最近 ${items.size} 个月用车开销柱状图"
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(320f).toInt(), widthMeasureSpec),
            resolveSize(dp(220f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val chartTop = dp(18f)
        val chartBottom = height - dp(30f)
        val max = values.maxOfOrNull { it.total }?.coerceAtLeast(1.0) ?: 1.0
        val slot = width.toFloat() / values.size
        val barWidth = (slot * .55f).coerceAtLeast(dp(8f))
        values.forEachIndexed { index, item ->
            val left = index * slot + (slot - barWidth) / 2f
            var bottom = chartBottom
            listOf(
                item.fuel to Color.rgb(69, 166, 201),
                item.maintenance to Color.rgb(238, 89, 126),
                item.daily to Color.rgb(255, 181, 71),
            ).forEach { (amount, color) ->
                if (amount <= 0.0) return@forEach
                val barHeight = ((chartBottom - chartTop) * amount / max).toFloat()
                paint.color = color
                canvas.drawRoundRect(RectF(left, bottom - barHeight, left + barWidth, bottom),
                    dp(3f), dp(3f), paint)
                bottom -= barHeight
            }
            canvas.drawText("${item.month.monthValue}月", index * slot + slot / 2f,
                height - dp(10f), text)
        }
    }
}
