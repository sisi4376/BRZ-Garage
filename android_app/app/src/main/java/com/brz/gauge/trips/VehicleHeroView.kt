package com.brz.gauge.trips

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/** Vehicle artwork with the generated plate perspective-installed on the front bumper. */
class VehicleHeroView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val bitmapPaint = Paint(
        Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG,
    )
    private val plateMountPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(155, 4, 7, 11)
        style = Paint.Style.FILL
    }
    private var vehicleBitmap: Bitmap? = null
    private var model: SupportedVehicleModel = SupportedVehicleModel.ZD8
    private var plateValue: GeneratedLicensePlate? = null
    private var plateBitmap: Bitmap? = null
    private var renderedPlateText: String? = null
    private var plateVisible = false

    fun setVehicleArtwork(bitmap: Bitmap?, vehicleModel: SupportedVehicleModel) {
        vehicleBitmap = bitmap
        model = vehicleModel
        invalidate()
    }

    fun showInstalledPlate(value: GeneratedLicensePlate?, visible: Boolean) {
        plateValue = value
        plateVisible = visible && value != null
        val text = value?.compactText
        if (text != renderedPlateText) {
            plateBitmap = value?.let { LicensePlateArtwork.renderBitmap(context, it) }
            renderedPlateText = text
        }
        contentDescription = buildString {
            append(model.heroDescription)
            if (plateVisible && value != null) append("，已安装车牌 ${value.compactText}")
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vehicle = vehicleBitmap ?: return
        val scale = min(width.toFloat() / vehicle.width, height.toFloat() / vehicle.height)
        val drawWidth = vehicle.width * scale
        val drawHeight = vehicle.height * scale
        val vehicleBounds = RectF(
            (width - drawWidth) / 2f,
            (height - drawHeight) / 2f,
            (width + drawWidth) / 2f,
            (height + drawHeight) / 2f,
        )
        canvas.drawBitmap(vehicle, null, vehicleBounds, bitmapPaint)

        val plate = plateBitmap?.takeIf { plateVisible } ?: return
        val normalizedQuad = model.frontPlateQuad
        val destination = FloatArray(8) { index ->
            if (index % 2 == 0) {
                vehicleBounds.left + normalizedQuad[index] * vehicleBounds.width()
            } else {
                vehicleBounds.top + normalizedQuad[index] * vehicleBounds.height()
            }
        }
        val source = floatArrayOf(
            0f, 0f,
            plate.width.toFloat(), 0f,
            plate.width.toFloat(), plate.height.toFloat(),
            0f, plate.height.toFloat(),
        )
        drawPlateMount(canvas, destination)
        val perspective = Matrix()
        if (!perspective.setPolyToPoly(source, 0, destination, 0, 4)) return
        canvas.save()
        canvas.concat(perspective)
        canvas.drawBitmap(plate, 0f, 0f, bitmapPaint)
        canvas.restore()
    }

    /** A narrow dark carrier around the calibrated quad makes the plate sit on the bumper. */
    private fun drawPlateMount(canvas: Canvas, quad: FloatArray) {
        val centerX = (quad[0] + quad[2] + quad[4] + quad[6]) / 4f
        val centerY = (quad[1] + quad[3] + quad[5] + quad[7]) / 4f
        val expanded = FloatArray(8)
        repeat(4) { corner ->
            val index = corner * 2
            expanded[index] = centerX + (quad[index] - centerX) * 1.035f
            expanded[index + 1] = centerY + (quad[index + 1] - centerY) * 1.10f + 0.5f
        }
        val path = Path().apply {
            moveTo(expanded[0], expanded[1])
            lineTo(expanded[2], expanded[3])
            lineTo(expanded[4], expanded[5])
            lineTo(expanded[6], expanded[7])
            close()
        }
        canvas.drawPath(path, plateMountPaint)
    }
}
