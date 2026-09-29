package com.brz.gauge.trips

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

data class DailyExpenseSlice(
    val title: String,
    val amount: Double,
    val color: Int,
)

class DailyExpenseBreakdownView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(24f)
        strokeCap = Paint.Cap.BUTT
    }
    private val totalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(33, 39, 51)
        textAlign = Paint.Align.CENTER
        textSize = dp(20f)
        isFakeBoldText = true
    }
    private val captionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(109, 119, 132)
        textAlign = Paint.Align.CENTER
        textSize = dp(10f)
    }
    private var values = emptyList<DailyExpenseSlice>()

    fun submit(items: List<DailyExpenseSlice>) {
        values = items
        val total = items.sumOf { it.amount }
        contentDescription = if (total <= 0.0) "本年度暂无日常花费" else buildString {
            append("本年度日常花费分类图，合计 %.2f 元".format(total))
            items.filter { it.amount > 0.0 }.forEach { append("，${it.title} %.2f 元".format(it.amount)) }
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dp(280f).toInt(), widthMeasureSpec),
            resolveSize(dp(184f).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val total = values.sumOf { it.amount }
        val centerX = width / 2f
        val centerY = height / 2f - dp(2f)
        val radius = minOf(width, height).toFloat() / 2f - dp(25f)
        val oval = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)

        arcPaint.color = Color.rgb(232, 236, 241)
        canvas.drawArc(oval, -90f, 360f, false, arcPaint)
        if (total > 0.0) {
            var start = -90f
            values.filter { it.amount > 0.0 }.forEach { item ->
                val sweep = (item.amount / total * 360.0).toFloat()
                arcPaint.color = item.color
                canvas.drawArc(oval, start, sweep, false, arcPaint)
                start += sweep
            }
        }

        val totalText = when {
            total >= 10_000.0 -> "¥%.1f万".format(total / 10_000.0)
            else -> "¥%.0f".format(total)
        }
        canvas.drawText(totalText, centerX, centerY + dp(2f), totalPaint)
        canvas.drawText(if (total > 0.0) "年度日常" else "暂无支出", centerX, centerY + dp(21f), captionPaint)
    }
}
