package com.brz.gauge.trips

internal data class GaugeLayout(val pages: List<Int>, val items: List<Int>) {
    val valid get() = pages.size in 1..12 && pages.all { it in 0..11 } &&
        pages.distinct().size == pages.size && items.size == 19 && items.all { it in 0..12 }

    fun packet(): ByteArray {
        require(valid)
        val bytes = ByteArray(19)
        bytes[0] = 1
        fun setNibble(offset: Int, index: Int, value: Int) {
            val at = offset + index / 2
            bytes[at] = (bytes[at].toInt() or (value shl ((index % 2) * 4))).toByte()
        }
        repeat(12) { setNibble(1, it, pages.getOrNull(it) ?: 15) }
        items.forEachIndexed { i, value -> setNibble(7, i, value) }
        val crc = TripBleProtocol.crc16(bytes, 17)
        bytes[17] = crc.toByte()
        bytes[18] = (crc shr 8).toByte()
        return bytes
    }

    fun encode() = pages.joinToString(",") + "/" + items.joinToString(",")

    companion object {
        val default = GaugeLayout(listOf(4,5,0,1,3,2,6,8,7), listOf(0,1,2,0,2,3,4,1,0,8,0,2,8,5,6,4,7,10,11))
        fun decode(value: String?): GaugeLayout? = runCatching {
            val parts = requireNotNull(value).split("/")
            require(parts.size == 2)
            GaugeLayout(parts[0].split(",").map(String::toInt), parts[1].split(",").map(String::toInt))
        }.getOrNull()?.takeIf { it.valid }

        fun parse(bytes: ByteArray): GaugeLayout? {
            if (bytes.size != 19 || bytes[0].toInt() != 1) return null
            fun nibble(offset: Int, i: Int) = (bytes[offset + i / 2].toInt() shr ((i % 2) * 4)) and 15
            val pages = List(12) { nibble(1, it) }.takeWhile { it != 15 }
            val items = List(19) { nibble(7, it) }
            return GaugeLayout(pages, items).takeIf { it.valid && it.packet().contentEquals(bytes) }
        }
    }
}
