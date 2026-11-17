@file:Suppress("DEPRECATION")
package com.brz.obdstudio

import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

fun uuid(short: Int)=UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))
@SuppressLint("MissingPermission")
class BleCapture(val context: Context,val journal: Journal,val cancelled: ()->Boolean):AutoCloseable {
    private val events=LinkedBlockingQueue<String>()
    private val rx=LinkedBlockingQueue<ByteArray>()
    private var gatt: BluetoothGatt?=null
    private lateinit var write: BluetoothGattCharacteristic
    @Volatile private var connected=false
    @Volatile private var waitingWrite=false
    private val callback=object:BluetoothGattCallback(){
        override fun onConnectionStateChange(g: BluetoothGatt,status: Int,newState: Int){
            connected=status==0&&newState==BluetoothProfile.STATE_CONNECTED
            events.offer(if(connected)"connected" else "ERROR:蓝牙断开 status=$status")
        }
        override fun onServicesDiscovered(g: BluetoothGatt,status: Int){events.offer(if(status==0)"services" else "ERROR:服务发现失败 $status")}
        override fun onDescriptorWrite(g: BluetoothGatt,d: BluetoothGattDescriptor,status: Int){events.offer(if(status==0)"notify" else "ERROR:通知订阅失败 $status")}
        override fun onCharacteristicWrite(g: BluetoothGatt,c: BluetoothGattCharacteristic,status: Int){if(waitingWrite)events.offer(if(status==0)"written" else "ERROR:写入失败 $status")}
        override fun onCharacteristicChanged(g: BluetoothGatt,c: BluetoothGattCharacteristic){received(c.value?:byteArrayOf())}
        override fun onCharacteristicChanged(g: BluetoothGatt,c: BluetoothGattCharacteristic,value: ByteArray){received(value)}
        private fun received(value: ByteArray){
            journal.emit("rx",mapOf("hex" to value.hex()));StudioState.bytes(value.size);rx.offer(value.copyOf())
        }
    }
    fun check(){if(cancelled()||Thread.currentThread().isInterrupted)throw InterruptedException("停止");check(connected){"ELM 已断开，录制已保存"}}
    private fun waitEvent(expected: String,seconds: Long=20){
        val deadline=System.nanoTime()+seconds*1000000000
        while(System.nanoTime()<deadline){if(cancelled())throw InterruptedException();val e=events.poll(200,TimeUnit.MILLISECONDS)?:continue
            check(!e.startsWith("ERROR")){e};if(e==expected)return}
        error("蓝牙操作超时：$expected")
    }
    fun connect(address: String){
        val adapter=context.getSystemService(BluetoothManager::class.java).adapter?:error("手机没有蓝牙")
        check(adapter.isEnabled){"请先开启蓝牙"}
        val device=adapter.getRemoteDevice(address)
        gatt=device.connectGatt(context,false,callback,BluetoothDevice.TRANSPORT_LE)?:error("无法连接 ELM")
        waitEvent("connected");check(gatt!!.discoverServices()){"无法发现服务"};waitEvent("services")
        var notify:BluetoothGattCharacteristic?=null
        for((s,w,n)in listOf(Triple(0xfff0,0xfff1,0xfff2),Triple(0x18f0,0x2af1,0x2af0),Triple(0xff12,0xff15,0xff14))){
            val service=gatt!!.getService(uuid(s))?:continue
            val writes=service.characteristics.filter{it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)!=0}
            val notices=service.characteristics.filter{it.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY!=0}
            if(writes.isNotEmpty()&&notices.isNotEmpty()){write=writes.firstOrNull{it.uuid==uuid(w)}?:writes.first();notify=notices.firstOrNull{it.uuid==uuid(n)}?:notices.first();break}
        }
        val n=notify?:error("未找到兼容 FFF0 / 18F0 / FF12 的 BLE ELM 服务")
        check(gatt!!.setCharacteristicNotification(n,true)){"无法启用通知"}
        val descriptor=n.getDescriptor(uuid(0x2902))?:error("ELM 缺少 CCCD")
        descriptor.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        check(gatt!!.writeDescriptor(descriptor)){"无法订阅 ELM 通知"};waitEvent("notify")
        journal.emit("transport",mapOf("write" to write.uuid.toString(),"notify" to n.uuid.toString()))
    }
    fun query(input: String,phase: String="poll"):String {
        check();check(rx.isEmpty()){"命令间收到意外数据，停止以避免响应错配"}
        val c=norm(input);val packet=(c+"\r").toByteArray(Charsets.US_ASCII)
        val start=journal.time();val buffer=ByteArrayOutputStream()
        journal.emit("tx",mapOf("command" to c,"header" to "7E0","hex" to packet.hex()))
        var complete=false
        try {
            waitingWrite=write.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE==0
            write.writeType=if(waitingWrite)BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            write.value=packet;check(gatt!!.writeCharacteristic(write)){"ELM 写入未开始"}
            if(waitingWrite)waitEvent("written",8)
            val deadline=System.nanoTime()+8000000000
            while(System.nanoTime()<deadline){check();val bytes=rx.poll(100,TimeUnit.MILLISECONDS)?:continue;buffer.write(bytes)
                check(buffer.size()<=1048576){"ELM 无结束提示且响应超过 1 MiB"}
                if(bytes.contains('>'.code.toByte())){complete=true;break}}
            check(complete){"ELM 响应超时，保存后停止以避免错配"}
            return buffer.toString("US-ASCII")
        } finally {
            waitingWrite=false
            journal.emit("reply",mapOf("command" to c,"header" to "7E0","phase" to phase,"started" to start,"hex" to buffer.toByteArray().hex(),"complete" to complete))
            if(phase=="poll")StudioState.reply(c,buffer.toString("US-ASCII"),journal.time(),complete)
        }
    }
    fun run(mode: String,extra: List<String>,duration: Double,interval: Long){
        val gauge=Modes.gauge(mode)
        for(c in listOf("ATZ","ATE0","ATL0","ATS1","ATH0","ATAT1",if(gauge)"ATST0A" else "ATST19","ATSP6","ATSH7E0")){
            val r=query(c,"init");check(c=="ATZ"||body(r,c).contains("OK")){"ELM 拒绝初始化 $c：$r"};Thread.sleep(30)
        }
        if(gauge){
            val poll=GaugePoll(mode)
            repeat(3){poll.observe("0100",query("0100","discovery"));Thread.sleep(150)}
            journal.emit("poll_plan",mapOf("commands" to org.json.JSONArray(poll.plan),"schedule" to "brz-6slot-v6","vehicle" to mode,"automatic" to mode.endsWith("-at"),"oil_command" to poll.oil))
            StudioState.message("正在录制 · $mode 仪表轮询")
            val started=journal.time()
            while(duration==0.0||journal.time()-started<duration){check();val slot=poll.slot;val commands=poll.commands(journal.time());journal.emit("poll_slot",mapOf("slot" to slot,"commands" to org.json.JSONArray(commands)))
                for(c in commands){if(duration>0&&journal.time()-started>=duration)return;poll.observe(c,query(c,if(c=="0140")"discovery" else "poll"))};Thread.sleep(1)}
        } else {
            val pids=mutableSetOf<Int>()
            for(base in 0..224 step 32){var block=setOf<Int>();repeat(if(base==0)3 else 1){if(block.isEmpty()){block=supported(query("01%02X".format(base),"discovery"),base);if(block.isEmpty())Thread.sleep(150)}}
                pids.addAll(block.filter{it<=255&&it%32!=0});if(base+32 !in block)break}
            if(pids.isEmpty()){pids.addAll(Modes.core);StudioState.message("支持位图不可用，尝试仪表常用 PID")}
            val commands=((if(mode=="all")pids else pids.intersect(Modes.core.toSet())).sorted().map{"01%02X".format(it)}+(if(mode=="zc6")listOf("2101")else emptyList())+extra).distinct()
            check(commands.isNotEmpty()){"没有可采集的 PID"};journal.emit("poll_plan",mapOf("commands" to org.json.JSONArray(commands)))
            StudioState.message("正在录制 · ${commands.size} 个请求"+(if(mode=="all")" · 保留最近值" else ""))
            val started=journal.time()
            while(duration==0.0||journal.time()-started<duration)for(c in commands){check();if(duration>0&&journal.time()-started>=duration)return;query(c);Thread.sleep(interval)}
        }
    }
    override fun close(){connected=false;gatt?.disconnect();gatt?.close();gatt=null}
}
