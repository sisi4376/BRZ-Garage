package com.brz.gauge.trips

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.min

/** Small outline icons used by the grouped Settings page. */
class SettingsIconView(context: Context) : View(context) {
    enum class Icon {
        VEHICLE, MILEAGE, GAUGE, FUEL, TRIP, BLUETOOTH, UPDATE, FIRMWARE,
        DISPLAY, AUTOSTART, HEALTH, LEGACY, PLATE,
    }

    var icon: Icon = Icon.VEHICLE
        set(value) {
            field = value
            invalidate()
        }

    var iconColor: Int = Color.DKGRAY
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val box = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = iconColor
        val scale = min(width, height) / 24f
        val left = (width - 24f * scale) / 2f
        val top = (height - 24f * scale) / 2f
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(scale, scale)
        when (icon) {
            Icon.VEHICLE -> drawVehicle(canvas)
            Icon.MILEAGE -> drawMileage(canvas)
            Icon.GAUGE -> drawGauge(canvas)
            Icon.FUEL -> drawFuel(canvas)
            Icon.TRIP -> drawTrip(canvas)
            Icon.BLUETOOTH -> drawBluetooth(canvas)
            Icon.UPDATE -> drawUpdate(canvas)
            Icon.FIRMWARE -> drawFirmware(canvas)
            Icon.DISPLAY -> drawDisplay(canvas)
            Icon.AUTOSTART -> drawAutostart(canvas)
            Icon.HEALTH -> drawHealth(canvas)
            Icon.LEGACY -> drawLegacy(canvas)
            Icon.PLATE -> drawPlate(canvas)
        }
        canvas.restore()
    }

    private fun drawVehicle(canvas: Canvas) {
        path.reset()
        path.moveTo(5f, 12f)
        path.lineTo(6.5f, 8.2f)
        path.quadTo(7f, 7f, 8.5f, 7f)
        path.lineTo(15.5f, 7f)
        path.quadTo(17f, 7f, 17.5f, 8.2f)
        path.lineTo(19f, 12f)
        canvas.drawPath(path, paint)
        canvas.drawRoundRect(3.5f, 11f, 20.5f, 18f, 2f, 2f, paint)
        canvas.drawLine(6f, 18f, 6f, 20f, paint)
        canvas.drawLine(18f, 18f, 18f, 20f, paint)
        canvas.drawCircle(7f, 14.5f, .6f, paint)
        canvas.drawCircle(17f, 14.5f, .6f, paint)
    }

    private fun drawMileage(canvas: Canvas) {
        canvas.save()
        canvas.rotate(-38f, 12f, 12f)
        canvas.drawRoundRect(3.5f, 8f, 20.5f, 16f, 1.6f, 1.6f, paint)
        for (x in listOf(7f, 10f, 13f, 16f)) {
            canvas.drawLine(x, 8f, x, if (x == 10f || x == 16f) 11.5f else 10.5f, paint)
        }
        canvas.restore()
    }

    private fun drawGauge(canvas: Canvas) {
        box.set(4f, 4f, 20f, 20f)
        canvas.drawArc(box, 145f, 250f, false, paint)
        canvas.drawLine(12f, 13f, 17f, 9f, paint)
        canvas.drawCircle(12f, 13f, 1.2f, paint)
        canvas.drawLine(7f, 18f, 17f, 18f, paint)
    }

    private fun drawFuel(canvas: Canvas) {
        canvas.drawRoundRect(5f, 3f, 14.5f, 21f, 1.5f, 1.5f, paint)
        canvas.drawRoundRect(7f, 5.5f, 12.5f, 9.5f, .8f, .8f, paint)
        canvas.drawLine(7f, 17f, 12.5f, 17f, paint)
        path.reset()
        path.moveTo(14.5f, 7f)
        path.cubicTo(18f, 7f, 18f, 9f, 18f, 11f)
        path.lineTo(18f, 17f)
        path.quadTo(18f, 19f, 16f, 19f)
        path.lineTo(14.5f, 19f)
        canvas.drawPath(path, paint)
        canvas.drawLine(17f, 5f, 20f, 8f, paint)
    }

    private fun drawTrip(canvas: Canvas) {
        canvas.drawCircle(6f, 6f, 2f, paint)
        canvas.drawCircle(18f, 18f, 2f, paint)
        path.reset()
        path.moveTo(6f, 8f)
        path.cubicTo(6f, 13f, 18f, 10f, 18f, 16f)
        canvas.drawPath(path, paint)
    }

    private fun drawBluetooth(canvas: Canvas) {
        path.reset()
        path.moveTo(11f, 3f)
        path.lineTo(17f, 8.5f)
        path.lineTo(7f, 17f)
        path.moveTo(11f, 3f)
        path.lineTo(11f, 21f)
        path.lineTo(17f, 15.5f)
        path.lineTo(7f, 7f)
        canvas.drawPath(path, paint)
    }

    private fun drawUpdate(canvas: Canvas) {
        box.set(4f, 4f, 20f, 20f)
        canvas.drawArc(box, 205f, 230f, false, paint)
        path.reset()
        path.moveTo(4f, 8f)
        path.lineTo(4f, 4f)
        path.lineTo(8f, 4f)
        path.moveTo(20f, 16f)
        path.lineTo(20f, 20f)
        path.lineTo(16f, 20f)
        canvas.drawPath(path, paint)
    }

    private fun drawFirmware(canvas: Canvas) {
        canvas.drawRoundRect(6f, 6f, 18f, 18f, 2f, 2f, paint)
        canvas.drawRoundRect(9f, 9f, 15f, 15f, 1f, 1f, paint)
        for (v in listOf(8f, 12f, 16f)) {
            canvas.drawLine(v, 3.5f, v, 6f, paint)
            canvas.drawLine(v, 18f, v, 20.5f, paint)
            canvas.drawLine(3.5f, v, 6f, v, paint)
            canvas.drawLine(18f, v, 20.5f, v, paint)
        }
    }

    private fun drawDisplay(canvas: Canvas) {
        canvas.drawRoundRect(3.5f, 4.5f, 20.5f, 17f, 2f, 2f, paint)
        canvas.drawLine(9f, 21f, 15f, 21f, paint)
        canvas.drawLine(12f, 17f, 12f, 21f, paint)
    }

    private fun drawAutostart(canvas: Canvas) {
        box.set(4f, 4f, 20f, 20f)
        canvas.drawArc(box, -45f, 270f, false, paint)
        canvas.drawLine(12f, 3f, 12f, 12f, paint)
    }

    private fun drawHealth(canvas: Canvas) {
        path.reset()
        path.moveTo(12f, 3f)
        path.lineTo(20f, 6f)
        path.lineTo(20f, 12f)
        path.quadTo(20f, 18f, 12f, 21f)
        path.quadTo(4f, 18f, 4f, 12f)
        path.lineTo(4f, 6f)
        path.close()
        canvas.drawPath(path, paint)
        path.reset()
        path.moveTo(8.5f, 12f)
        path.lineTo(11f, 14.5f)
        path.lineTo(16f, 9.5f)
        canvas.drawPath(path, paint)
    }

    private fun drawLegacy(canvas: Canvas) {
        canvas.drawRoundRect(5f, 5f, 19f, 19f, 2f, 2f, paint)
        canvas.drawRoundRect(3f, 3f, 17f, 17f, 2f, 2f, paint)
        canvas.drawLine(7f, 8f, 13f, 8f, paint)
        canvas.drawLine(7f, 12f, 13f, 12f, paint)
    }

    private fun drawPlate(canvas: Canvas) {
        canvas.drawRoundRect(3f, 6f, 21f, 18f, 2f, 2f, paint)
        canvas.drawCircle(6f, 12f, .8f, paint)
        canvas.drawCircle(18f, 12f, .8f, paint)
        canvas.drawLine(9f, 10f, 15f, 10f, paint)
        canvas.drawLine(9f, 14f, 15f, 14f, paint)
    }
}
