package com.brz.obdstudio

data class BleEndpoint(val service: String,val id: String,val properties: Int,val cccd: Boolean)
data class BleChannel(val write: BleEndpoint,val receive: BleEndpoint) {
    val indication get() = receive.properties and 16 == 0 && receive.properties and 32 != 0
}
object BleChannelPolicy {
    private fun short(n: Int)="0000%04x-0000-1000-8000-00805f9b34fb".format(n)
    fun select(endpoints: List<BleEndpoint>): BleChannel = channels(endpoints).first()
    fun channels(endpoints: List<BleEndpoint>): List<BleChannel> {
        fun candidates(service: String):List<BleChannel> {
            val chars=endpoints.filter{it.service==service}
            val writes=chars.filter{it.properties and 12 != 0}
            val reads=chars.filter{it.properties and 48 != 0 && it.cccd}.sortedBy{if(it.properties and 16 != 0)0 else 1}
            // Match the gauge: first writable / first NOTIFY in discovery order.
            // UUID FFF2 is not necessarily the UART receive endpoint.
            return writes.flatMap{w->reads.map{r->BleChannel(w,r)}}
        }
        val known=listOf(0xfff0,0x18f0,0xff12).flatMap{candidates(short(it))}
        if(known.isNotEmpty())return known.distinct()
        val alternatives=endpoints.map{it.service}.distinct().map{candidates(it)}.filter{it.isNotEmpty()}
        require(alternatives.size==1){if(alternatives.isEmpty())"没有可写入并订阅的 ELM 通道（含 CCCD）" else "存在多个未知串口服务，无法安全确定 ELM 通道；请导出诊断录制"}
        return alternatives.single()
    }
}
