@file:Suppress("DEPRECATION")
package com.brz.obdstudio

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.os.Handler
import android.os.HandlerThread
import android.content.Context
import android.os.Build
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

fun uuid(short: Int)=UUID.fromString("0000%04x-0000-1000-8000-00805f9b34fb".format(short))
@SuppressLint("MissingPermission")
class BleCapture(val context: Context,val journal: Journal,val cancelled: ()->Boolean):AutoCloseable {
    private data class Event(val kind:String,val status:Int=0)
    private val events=LinkedBlockingQueue<Event>()
    private val rx=LinkedBlockingQueue<ByteArray>()
    private var gatt:BluetoothGatt?=null
    private lateinit var write:BluetoothGattCharacteristic
    private lateinit var receive:BluetoothGattCharacteristic
    private val callbackThread=HandlerThread("OBD-GATT-callback").apply{start()}
    private val callbackHandler=Handler(callbackThread.looper)
    @Volatile private var connected=false
    @Volatile private var generation=0
    private var startup=true
    private data class Probe(val channel:BleChannel,val writeType:Int)
    private var probes=emptyList<Probe>()
    private var probeIndex=0
    private var address=""
    private var writeType=BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
    private class LinkFailure(val step:String,val code:Int,message:String):IllegalStateException(message)
    private class ResponseTimeout(val received:Int,command:String):IllegalStateException("ELM 响应超时，命令 $command（收到 $received 字节）；录制已保存")
    private fun report(stage:String,detail:String="") {
        journal.emit("ble_event",mapOf("stage" to stage,"detail" to detail))
        StudioState.message("蓝牙：$stage${if(detail.isEmpty())""else " · $detail"}")
    }
    private fun callback(token:Int)=object:BluetoothGattCallback(){
        private fun current()=token==generation
        override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int){
            if(!current())return
            journal.emit("ble_event",mapOf("stage" to "connection_callback","status" to status,"state" to newState))
            if(status==0&&newState==BluetoothProfile.STATE_CONNECTED){connected=true;events.offer(Event("connected"))}
            else if(status!=0||newState==BluetoothProfile.STATE_DISCONNECTED){connected=false;events.offer(Event("disconnected",status))}
        }
        override fun onServicesDiscovered(g:BluetoothGatt,status:Int){if(current())events.offer(Event("services",status))}
        override fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,status:Int){if(current()&&d.uuid==uuid(0x2902))events.offer(Event("notify",status))}
        override fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int){if(current()&&::write.isInitialized&&c.uuid==write.uuid){journal.emit("ble_event",mapOf("stage" to "write_callback","status" to status));events.offer(Event("written",status))}}
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic){received(c,c.value?:byteArrayOf())}
        override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){received(c,value)}
        private fun received(c:BluetoothGattCharacteristic,value:ByteArray){
            if(!current()||!::receive.isInitialized||c.uuid!=receive.uuid)return
            journal.emit("rx",mapOf("hex" to value.hex()));StudioState.bytes(value.size);rx.offer(value.copyOf())
        }
    }
    fun check(){if(cancelled()||Thread.currentThread().isInterrupted)throw InterruptedException("停止");check(connected){"ELM 已断开，录制已保存"}}
    private fun waitEvent(expected:String,seconds:Long=20){
        val deadline=System.nanoTime()+seconds*1000000000
        while(System.nanoTime()<deadline){
            if(cancelled())throw InterruptedException()
            val event=events.poll(100,TimeUnit.MILLISECONDS)?:continue
            if(event.kind=="disconnected"||event.kind==expected&&event.status!=0)
                throw LinkFailure(expected,event.status,"$expected 失败：GATT status=${event.status}；请确认仪表及其他 App 已断开 ELM")
            if(event.kind==expected)return
        }
        throw LinkFailure(expected,-1,"$expected 超时；请确认 ELM 供电、靠近手机，并重新扫描选择设备")
    }
    private fun findDevice(adapter:BluetoothAdapter,address:String):BluetoothDevice {
        val scanner=adapter.bluetoothLeScanner?:error("蓝牙扫描不可用")
        val found=LinkedBlockingQueue<BluetoothDevice>()
        val failures=LinkedBlockingQueue<Int>()
        val scan=object:ScanCallback(){
            override fun onScanResult(type:Int,result:ScanResult){if(result.device.address.equals(address,true))found.offer(result.device)}
            override fun onBatchScanResults(results:MutableList<ScanResult>){results.forEach{onScanResult(0,it)}}
            override fun onScanFailed(code:Int){failures.offer(code)}
        }
        report("重新确认已选设备",address)
        try {
            scanner.startScan(null,ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scan)
            val deadline=System.nanoTime()+10000000000L
            while(System.nanoTime()<deadline){
                if(cancelled())throw InterruptedException()
                failures.poll()?.let{error("连接前扫描失败：code=$it；请稍后再试")}
                found.poll(100,TimeUnit.MILLISECONDS)?.let{return it}
            }
            error("未发现所选 ELM 的广播：$address。请让仪表/其他 App 断开 ELM；地址可能已变化，请重新扫描选择")
        } finally {scanner.stopScan(scan)}
    }
    fun connect(address:String){this.address=address;openLink()}
    private fun openLink(){
        val adapter=context.getSystemService(BluetoothManager::class.java).adapter?:error("手机没有蓝牙")
        check(adapter.isEnabled){"请先开启蓝牙"}
        for(attempt in 1..2){
            val device=findDevice(adapter,address)
            generation++;val token=generation;events.clear();rx.clear();connected=false
            try {
                report("建立 GATT 连接","第 $attempt/2 次")
                gatt=device.connectGatt(context,false,callback(token),BluetoothDevice.TRANSPORT_LE,BluetoothDevice.PHY_LE_1M_MASK,callbackHandler)?:error("无法创建 GATT 连接")
                waitEvent("connected",30);break
            }catch(e:LinkFailure){
                generation++;connected=false;gatt?.disconnect();gatt?.close();gatt=null
                journal.emit("ble_event",mapOf("stage" to "connect_failed","attempt" to attempt,"status" to e.code))
                if(attempt==2)throw e
                report("连接未成功，释放后重试","status=${e.code}");Thread.sleep(1000)
            }
        }
        report("发现 GATT 服务");check(gatt!!.discoverServices()){"服务发现未启动"};waitEvent("services")
        val chars=gatt!!.services.flatMap{s->s.characteristics.map{c->BleEndpoint(s.uuid.toString(),c.uuid.toString(),c.properties,c.getDescriptor(uuid(0x2902))!=null)}}
        journal.emit("ble_inventory",mapOf("endpoints" to org.json.JSONArray(chars.map{json(mapOf("service" to it.service,"uuid" to it.id,"properties" to it.properties,"cccd" to it.cccd))})))
        if(probes.isEmpty())probes=BleChannelPolicy.channels(chars).flatMap{channel->
            buildList {
                if(channel.write.properties and 4 != 0)add(Probe(channel,BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE))
                if(channel.write.properties and 8 != 0)add(Probe(channel,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT))
            }
        }.take(8)
        val probe=probes[probeIndex];val channel=probe.channel;writeType=probe.writeType
        write=gatt!!.getService(UUID.fromString(channel.write.service)).getCharacteristic(UUID.fromString(channel.write.id))
        receive=gatt!!.getService(UUID.fromString(channel.receive.service)).getCharacteristic(UUID.fromString(channel.receive.id))
        report("订阅 ELM 数据",receive.uuid.toString())
        check(gatt!!.setCharacteristicNotification(receive,true)){"无法启用本地通知"}
        val descriptor=receive.getDescriptor(uuid(0x2902))?:error("ELM 缺少 CCCD")
        val value=if(channel.indication)BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if(Build.VERSION.SDK_INT>=33)check(gatt!!.writeDescriptor(descriptor,value)==BluetoothStatusCodes.SUCCESS){"ELM CCCD 写入未开始"}
        else {descriptor.value=value;check(gatt!!.writeDescriptor(descriptor)){"ELM CCCD 写入未开始"}}
        waitEvent("notify")
        journal.emit("transport",mapOf("service" to channel.write.service,"write" to write.uuid.toString(),"notify" to receive.uuid.toString(),"indication" to channel.indication,"write_type" to writeType,"probe" to probeIndex+1))
        // The gauge waits 300 ms after CCCD success before its first ATZ.
        Thread.sleep(300);startup=true;check()
        report("BLE 通道就绪，准备初始化 ELM")
    }
    fun initializeElm(){
        while(true){
            try {
                query("ATZ","init")
                Thread.sleep(30)
                // A prompt/banner alone does not prove a working command channel.
                val echo=query("ATE0","init")
                check(body(echo,"ATE0").contains("OK")){"ELM 未确认 ATE0：$echo"}
                report("ELM 收发验证成功")
                return
            }catch(e:Exception){
                val retry=e is ResponseTimeout && e.received==0 || e is LinkFailure && e.step=="written"
                journal.emit("ble_event",mapOf("stage" to "handshake_failed","probe" to probeIndex+1,"detail" to e.message))
                if(!retry||cancelled()||probeIndex+1>=probes.size)throw e
                // Never reuse a timed-out connection: late replies belong to the old GATT generation.
                releaseLink();probeIndex++
                report("ELM 未应答，重连验证备用通道","${probeIndex+1}/${probes.size}")
                Thread.sleep(1000);openLink()
            }
        }
    }
    private fun drainStartup(){
        val deadline=System.nanoTime()+1000000000L;var size=0
        while(System.nanoTime()<deadline){check();val bytes=rx.poll(150,TimeUnit.MILLISECONDS)?:break;size+=bytes.size}
        if(size>0)journal.emit("ble_event",mapOf("stage" to "startup_banner","bytes" to size))
    }
    fun query(input:String,phase:String="poll"):String {
        check();val c=norm(input)
        if(startup&&c=="ATZ"){drainStartup();startup=false}
        check(rx.isEmpty()){"命令间收到意外数据，停止以避免响应错配"}
        val packet=(c+"\r").toByteArray(Charsets.US_ASCII)
        val start=journal.time();val buffer=ByteArrayOutputStream()
        journal.emit("tx",mapOf("command" to c,"header" to "7E0","hex" to packet.hex(),"write_type" to writeType))
        var complete=false
        try {
            val type=writeType
            if(phase=="init")report("ELM 初始化",c)
            if(Build.VERSION.SDK_INT>=33)check(gatt!!.writeCharacteristic(write,packet,type)==BluetoothStatusCodes.SUCCESS){"ELM 写入未开始（Android GATT busy/permission），命令 $c"}
            else {write.writeType=type;write.value=packet;check(gatt!!.writeCharacteristic(write)){"ELM 写入未开始，命令 $c"}}
            // Android reports local write completion for both write types.
            // Await it before issuing any further ATT operation; never swallow NR errors.
            waitEvent("written",8)
            val deadline=System.nanoTime()+8000000000L
            while(System.nanoTime()<deadline){check();val bytes=rx.poll(100,TimeUnit.MILLISECONDS)?:continue;buffer.write(bytes)
                check(buffer.size()<=1048576){"ELM 无结束提示且响应超过 1 MiB"}
                if(bytes.contains('>'.code.toByte())){complete=true;break}}
            if(!complete)throw ResponseTimeout(buffer.size(),c)
            return buffer.toString("US-ASCII")
        } finally {
            journal.emit("reply",mapOf("command" to c,"header" to "7E0","phase" to phase,"started" to start,"hex" to buffer.toByteArray().hex(),"complete" to complete))
            if(phase=="poll")StudioState.reply(c,buffer.toString("US-ASCII"),journal.time(),complete)
        }
    }
    fun run(mode: String,extra: List<String>,duration: Double,interval: Long,strictDiscovery:Boolean=false,onPollingStarted: ()->Unit = {}){
        val gauge=Modes.gauge(mode)
        initializeElm()
        for(c in listOf("ATL0","ATS1","ATH0","ATAT1",if(gauge)"ATST0A" else "ATST19","ATSP6","ATSH7E0")){
            val r=query(c,"init");check(c=="ATZ"||body(r,c).contains("OK")){"ELM 拒绝初始化 $c：$r"};Thread.sleep(30)
        }
        if(gauge){
            val poll=GaugePoll(mode)
            repeat(3){poll.observe("0100",query("0100","discovery"));Thread.sleep(150)}
            journal.emit("poll_plan",mapOf("commands" to org.json.JSONArray(poll.plan),"schedule" to "brz-ab-v1","vehicle" to mode,"automatic" to mode.endsWith("-at"),"oil_command" to poll.oil))
            StudioState.message("正在录制 · $mode 仪表轮询")
            onPollingStarted();val started=journal.time()
            while(duration==0.0||journal.time()-started<duration){check();val slot=poll.slot;val commands=poll.commands(journal.time());journal.emit("poll_slot",mapOf("slot" to slot,"commands" to org.json.JSONArray(commands)))
                for(c in commands){if(duration>0&&journal.time()-started>=duration)return;poll.observe(c,query(c,if(c=="0140")"discovery" else "poll"))};Thread.sleep(1)}
        } else {
            val pids=mutableSetOf<Int>()
            for(base in 0..224 step 32){var block:Set<Int>?=null;repeat(if(base==0)3 else 1){if(block==null){block=supportedBlock(query("01%02X".format(base),"discovery"),base);if(block==null)Thread.sleep(150)}}
                check(!strictDiscovery||block!=null){"无法读取 PID 支持位图 01%02X，请检查车辆供电或使用高级手动采集".format(base)}
                val values=block.orEmpty();pids.addAll(values.filter{it<=255&&it%32!=0});if(base+32 !in values)break}
            check(!strictDiscovery||pids.isNotEmpty()){"ECU 未声明可采集的标准 PID，未开始全量计时"}
            if(pids.isEmpty()){pids.addAll(Modes.core);StudioState.message("支持位图不可用，尝试仪表常用 PID")}
            val commands=((if(mode=="all")pids else pids.intersect(Modes.core.toSet())).sorted().map{"01%02X".format(it)}+(if(mode=="zc6")listOf("2101")else emptyList())+extra).distinct()
            check(commands.isNotEmpty()){"没有可采集的 PID"};journal.emit("poll_plan",mapOf("commands" to org.json.JSONArray(commands)))
            StudioState.message("正在录制 · ${commands.size} 个请求"+(if(mode=="all")" · 保留最近值" else ""))
            onPollingStarted();val started=journal.time()
            while(duration==0.0||journal.time()-started<duration)for(c in commands){check();if(duration>0&&journal.time()-started>=duration)return;query(c);Thread.sleep(interval)}
        }
    }
    private fun releaseLink(){generation++;connected=false;try{gatt?.disconnect()}finally{gatt?.close();gatt=null;events.clear();rx.clear()}}
    override fun close(){try{releaseLink()}finally{callbackThread.quitSafely()}}
}
