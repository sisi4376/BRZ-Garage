package com.brz.gauge.trips

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import kotlin.math.sqrt

data class DrivingDayActivity(
    val date: LocalDate,
    val trips: Int,
    val distanceM: Long,
    val durationS: Long,
    val fuelMl: Long,
)

class DrivingCalendarView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = value * density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        textSize = dp(10f)
        color = Color.rgb(109, 119, 132)
    }
    private val cells = HashMap<LocalDate, DrivingDayActivity>()
    private var year = LocalDate.now().year
    private var start = LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    private var weeks = 53
    private var selected: LocalDate? = null
    private var listener: ((LocalDate, DrivingDayActivity?) -> Unit)? = null
    private val labelWidth get() = dp(24f)
    private val headerHeight get() = dp(20f)
    private val cell get() = dp(12f)
    private val gap get() = dp(3f)

    fun submit(valueYear: Int, activity: Collection<DrivingDayActivity>) {
        year = valueYear
        start = LocalDate.of(year, 1, 1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val end = LocalDate.of(year, 12, 31).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        weeks = ((end.toEpochDay() - start.toEpochDay() + 1L) / 7L).toInt()
        cells.clear()
        activity.filter { it.date.year == year }.forEach { cells[it.date] = it }
        selected = null
        requestLayout()
        invalidate()
    }

    fun setOnDaySelected(listener: (LocalDate, DrivingDayActivity?) -> Unit) {
        this.listener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (labelWidth + weeks * (cell + gap) + dp(8f)).toInt()
        val desiredHeight = (headerHeight + 7 * (cell + gap) + dp(8f)).toInt()
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec), resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val maxDistance = cells.values.maxOfOrNull { it.distanceM }?.coerceAtLeast(1L) ?: 1L
        listOf(1 to "一", 3 to "三", 5 to "五", 7 to "日").forEach { (day, name) ->
            canvas.drawText(name, dp(2f), headerHeight + (day - 1) * (cell + gap) + cell * .82f, textPaint)
        }
        var lastMonth = -1
        repeat(weeks) { week ->
            val weekStart = start.plusWeeks(week.toLong())
            val firstInYear = (0L..6L).map { weekStart.plusDays(it) }.firstOrNull { it.year == year }
            if (firstInYear != null && firstInYear.monthValue != lastMonth) {
                lastMonth = firstInYear.monthValue
                canvas.drawText("${lastMonth}月", labelWidth + week * (cell + gap), dp(11f), textPaint)
            }
            repeat(7) { day ->
                val date = weekStart.plusDays(day.toLong())
                if (date.year != year) return@repeat
                val item = cells[date]
                val ratio = item?.let { sqrt(it.distanceM.toDouble() / maxDistance) } ?: 0.0
                paint.color = when {
                    item == null -> Color.rgb(232, 236, 241)
                    ratio < .25 -> Color.rgb(213, 238, 246)
                    ratio < .5 -> Color.rgb(138, 210, 229)
                    ratio < .75 -> Color.rgb(69, 166, 201)
                    else -> Color.rgb(25, 104, 148)
                }
                val left = labelWidth + week * (cell + gap)
                val top = headerHeight + day * (cell + gap)
                canvas.drawRoundRect(RectF(left, top, left + cell, top + cell), dp(2.5f), dp(2.5f), paint)
                if (date == selected) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2f)
                    paint.color = Color.rgb(220, 49, 86)
                    canvas.drawRoundRect(RectF(left - dp(1f), top - dp(1f), left + cell + dp(1f),
                        top + cell + dp(1f)), dp(3f), dp(3f), paint)
                    paint.style = Paint.Style.FILL
                }
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val week = ((event.x - labelWidth) / (cell + gap)).toInt()
        val day = ((event.y - headerHeight) / (cell + gap)).toInt()
        if (week !in 0 until weeks || day !in 0..6) return true
        val date = start.plusWeeks(week.toLong()).plusDays(day.toLong())
        if (date.year != year) return true
        selected = date
        contentDescription = "${date}，${cells[date]?.trips ?: 0} 次行程"
        listener?.invoke(date, cells[date])
        invalidate()
        performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
