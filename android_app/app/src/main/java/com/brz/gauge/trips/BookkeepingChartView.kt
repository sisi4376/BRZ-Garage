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
    private val monthText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(109, 119, 132)
        textSize = dp(10f)
        textAlign = Paint.Align.CENTER
    }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(79, 86, 100)
        textSize = dp(9f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(231, 234, 240)
        strokeWidth = dp(1f)
    }
    private var values = emptyList<MonthlyExpenseSummary>()

    fun submit(items: List<MonthlyExpenseSummary>) {
        values = items
        val latest = items.lastOrNull()
        contentDescription = buildString {
            append("最近 ${items.size} 个月用车开销堆叠柱状图")
            latest?.let { append("，${it.month.monthValue} 月合计 %.2f 元".format(it.total)) }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dp(320f).toInt(), widthMeasureSpec),
            resolveSize(dp(220f).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (values.isEmpty()) return
        val chartTop = dp(26f)
        val chartBottom = height - dp(31f)
        val chartHeight = chartBottom - chartTop
        val max = values.maxOfOrNull { it.total }?.coerceAtLeast(1.0) ?: 1.0
        val slot = width.toFloat() / values.size

        repeat(4) { index ->
            val y = chartTop + chartHeight * index / 3f
            canvas.drawLine(dp(5f), y, width - dp(5f), y, gridPaint)
        }

        val hasData = values.any { it.total > 0.0 }
        if (!hasData) {
            valueText.color = Color.rgb(145, 152, 164)
            valueText.textSize = dp(12f)
            valueText.isFakeBoldText = false
            canvas.drawText("暂无月度开销", width / 2f, chartTop + chartHeight / 2f, valueText)
            valueText.textSize = dp(9f)
            valueText.isFakeBoldText = true
        }

        val barWidth = (slot * .52f).coerceIn(dp(8f), dp(18f))
        values.forEachIndexed { index, item ->
            val left = index * slot + (slot - barWidth) / 2f
            paint.color = if (index == values.lastIndex) Color.rgb(226, 229, 238)
                else Color.rgb(241, 243, 247)
            canvas.drawRoundRect(RectF(left, chartTop, left + barWidth, chartBottom),
                dp(5f), dp(5f), paint)
            var bottom = chartBottom
            listOf(
                item.fuel to Color.rgb(54, 143, 181),
                item.maintenance to Color.rgb(218, 70, 111),
                item.daily to Color.rgb(225, 145, 47),
            ).forEach { (amount, color) ->
                if (amount <= 0.0) return@forEach
                val barHeight = (chartHeight * amount / max).toFloat()
                paint.color = color
                canvas.drawRoundRect(RectF(left, bottom - barHeight, left + barWidth, bottom),
                    dp(4f), dp(4f), paint)
                bottom -= barHeight
            }
            if (item.total > 0.0 && index == values.lastIndex) {
                valueText.color = Color.rgb(79, 86, 100)
                val compact = when {
                    item.total >= 10000.0 -> "%.1fw".format(item.total / 10000.0)
                    item.total >= 1000.0 -> "%.1fk".format(item.total / 1000.0)
                    else -> "%.0f".format(item.total)
                }
                canvas.drawText(compact, left + barWidth / 2f,
                    (bottom - dp(6f)).coerceAtLeast(dp(10f)), valueText)
            }
            if (index % 2 == 0 || index == values.lastIndex) {
                monthText.color = if (index == values.lastIndex) Color.rgb(86, 92, 169)
                    else Color.rgb(109, 119, 132)
                monthText.isFakeBoldText = index == values.lastIndex
                canvas.drawText("${item.month.monthValue}月", index * slot + slot / 2f,
                    height - dp(9f), monthText)
            }
        }
    }
}
