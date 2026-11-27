package com.brz.gauge.trips

internal data class GaugeLayout(val pages: List<Int>, val items: List<Int>) {
    private fun copySlot(page: Int) = if (page >= 86) page - 86 else (page - 9) % 19

    val usesFourSlots get() = pages.any { it >= 86 }
    val usesThreeSlotGps get() = items.indices.any { items[it] == 13 &&
        (it < 3 || (it >= 10 && (it - 10) % 5 < 3)) }

    private fun gpsFieldAllowed(index: Int): Boolean {
        if (index < 10) return index < 3 || index == 7
        val field = (index - 10) % 5
        if (field == 3) return false
        val slot = (index - 10) / 5
        val allowedPages = if (field == 4) 28..46 else 9..27
        return pages.none { it >= 9 && copySlot(it) == slot && it !in allowedPages }
    }

    val valid get() = pages.size <= 20 && pages.all { it in 0..104 && it != 85 } &&
        pages.distinctBy { if (it < 9) it else 9 + copySlot(it) }.size == pages.size &&
        items.size == 105 && items.indices.all { i ->
            items[i] in 0..12 || (items[i] == 13 && gpsFieldAllowed(i))
        }

    fun encode() = pages.joinToString(",") + "/" + items.joinToString(",")

    fun packet(): ByteArray {
        require(valid)
        val bytes = ByteArray(128)
        bytes[0] = 2
        repeat(20) { bytes[it + 1] = (pages.getOrNull(it) ?: 255).toByte() }
        items.forEachIndexed { index, signal -> bytes[21 + index] = signal.toByte() }
        return withCrc(bytes)
    }

    fun chunk(index: Int): ByteArray {
        require(index in 0..7)
        return byteArrayOf(2, index.toByte()) + packet().copyOfRange(index * 16, (index + 1) * 16)
    }

    fun legacyPacket(): ByteArray? {
        if (!valid || 13 in items || pages.isEmpty() || pages.any { it > 11 }) return null
        val bytes = ByteArray(19)
        bytes[0] = 1
        fun nibble(offset: Int, index: Int, value: Int) {
            val at = offset + index / 2
            bytes[at] = (bytes[at].toInt() or (value shl ((index % 2) * 4))).toByte()
        }
        repeat(12) { nibble(1, it, pages.getOrNull(it) ?: 15) }
        repeat(19) { i ->
            val index = if (i < 10) i else 10 + ((i - 10) / 3) * 5 + (i - 10) % 3
            nibble(7, i, items[index])
        }
        return withCrc(bytes)
    }

    companion object {
        const val SYSTEM_PAGE = 85
        fun isPageId(id: Int) = id in 0..104

        private fun withCrc(bytes: ByteArray): ByteArray {
            val end = bytes.size - 2
            val crc = TripBleProtocol.crc16(bytes, end)
            bytes[end] = crc.toByte()
            bytes[end + 1] = (crc shr 8).toByte()
            return bytes
        }

        private fun expand(old: List<Int>): List<Int> = List(105) { i ->
            if (i < 10) old[i]
            else {
                val slot = (i - 10) / 5
                val field = (i - 10) % 5
                if (slot < 3 && field < 3) old[10 + slot * 3 + field] else 0
            }
        }
        val default = GaugeLayout(listOf(4,5,0,1,3,2,6,8,7), expand(listOf(0,1,2,0,2,3,4,1,0,8,0,2,8,5,6,4,7,10,11)))

        fun decode(value: String?): GaugeLayout? = runCatching {
            val parts = requireNotNull(value).split("/")
            require(parts.size == 2)
            val pages = if (parts[0].isEmpty()) emptyList() else parts[0].split(",").map(String::toInt)
            val items = parts[1].split(",").map(String::toInt)
            GaugeLayout(pages, if (items.size == 19) expand(items) else items)
        }.getOrNull()?.takeIf { it.valid }

        fun parse(bytes: ByteArray): GaugeLayout? {
            fun u8(i: Int) = bytes[i].toInt() and 255
            if (bytes.size == 128 && u8(0) == 2) {
                val layout = GaugeLayout((1..20).map(::u8).takeWhile { it != 255 }, (21..125).map(::u8))
                return layout.takeIf { it.valid && it.packet().contentEquals(bytes) }
            }
            if (bytes.size != 19 || u8(0) != 1) return null
            fun nibble(offset: Int, i: Int) = (u8(offset + i / 2) shr ((i % 2) * 4)) and 15
            val layout = GaugeLayout(List(12) { nibble(1,it) }.takeWhile { it != 15 },
                expand(List(19) { nibble(7,it) }))
            return layout.takeIf { it.legacyPacket()?.contentEquals(bytes) == true }
        }
    }
}
