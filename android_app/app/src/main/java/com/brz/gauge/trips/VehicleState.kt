package com.brz.gauge.trips

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Optional v1 vehicle snapshot. Unknown values never turn into zero fuel. */
data class VehicleState(
    val flags: Int, val epochS: Long, val uptimeS: Long,
    val fuelPercentX100: Int, val averageX100: Int,
    val currentDistanceM: Long, val currentDurationS: Long, val currentFuelMl: Long,
    val totalDistanceM: Long, val totalDurationS: Long, val totalFuelMl: Long,
    val rpm: Int, val speed: Int,
    val maxSpeedKmh: Int? = null, val maxRpm: Int? = null,
    val maxAccelX100: Int? = null, val maxDecelX100: Int? = null,
    val currentStartEpochS: Long = 0,
    val fuelSampleSequence: Long? = null,
) {
    val fuelPercent: Double? get() = if (flags and 1 != 0 && fuelPercentX100 in 0..10000) fuelPercentX100 / 100.0 else null
    val average: Double? get() = if (flags and 8 != 0 && averageX100 in 1..6000) averageX100 / 100.0 else null
    val engineAvailable: Boolean get() = flags and 2 != 0
    fun remainingLitres(tankLitres: Double): Double? = fuelPercent?.let { if (tankLitres in 1.0..150.0) tankLitres * it / 100 else null }
    fun estimatedRangeKm(tankLitres: Double, effectiveFuelPercent: Double? = fuelPercent): Int? {
        if (tankLitres !in 1.0..150.0 || effectiveFuelPercent == null || effectiveFuelPercent !in 0.0..100.0) return null
        val litres = tankLitres * effectiveFuelPercent / 100.0
        val consumption = average ?: return null
        // Avoid an idle-only sample, but do not hide useful data for the first
        // 10 km as older builds did. One kilometre is enough for a provisional
        // estimate; the UI already labels it as an estimate.
        if (totalDistanceM < 1000) return null
        return (litres / consumption * 100).toInt().coerceIn(0, 3000)
    }

    companion object {
        fun parse(bytes: ByteArray): VehicleState? {
            if (bytes.size !in setOf(64, 84)) return null
            val version = bytes[0].toInt() and 0xff
            if ((version == 1 && bytes.size != 64) || (version == 2 && bytes.size != 84)) return null
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun u16() = b.short.toInt() and 0xffff
            fun u32() = b.int.toLong() and 0xffff_ffffL
            b.get()
            val flags = b.get().toInt() and 0xff
            val encodedSize = u16()
            val crcOffset = bytes.size - 2
            if (encodedSize != bytes.size || (b.getShort(crcOffset).toInt() and 0xffff) !=
                TripBleProtocol.crc16(bytes, crcOffset)) return null
            val epochS = b.long
            val uptimeS = u32()
            val fuelPercentX100 = u16()
            val averageX100 = u16()
            val currentDistanceM = u32()
            val currentDurationS = u32()
            val currentFuelMl = u32()
            val totalDistanceM = b.long
            val totalDurationS = b.long
            val totalFuelMl = b.long
            val rpm = u16()
            val speed = u16()
            val state = if (version >= 2) {
                val maxSpeed = u16()
                val maxRpm = u16()
                val maxAccel = b.short.toInt()
                val maxDecel = b.short.toInt()
                val startEpoch = b.long
                val fuelSequence = u32()
                VehicleState(flags, epochS, uptimeS, fuelPercentX100, averageX100,
                    currentDistanceM, currentDurationS, currentFuelMl,
                    totalDistanceM, totalDurationS, totalFuelMl, rpm, speed,
                    maxSpeed.takeIf { it > 0 }, maxRpm.takeIf { it > 0 },
                    maxAccel.takeIf { it != 0 }, maxDecel.takeIf { it != 0 },
                    startEpoch, fuelSequence)
            } else VehicleState(flags, epochS, uptimeS, fuelPercentX100, averageX100,
                currentDistanceM, currentDurationS, currentFuelMl,
                totalDistanceM, totalDurationS, totalFuelMl, rpm, speed)
            return state.takeIf { it.totalDistanceM >= 0 && it.totalDurationS >= 0 && it.totalFuelMl >= 0 }
        }
    }
}
