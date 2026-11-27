package com.brz.obdstudio

import java.io.File
import java.util.UUID

annotation class SuppressLint(vararg val value:String)
object Build {object VERSION {var SDK_INT=33}}
class Handler(val looper:Any)
class HandlerThread(name:String){val looper=Any();fun start(){};fun quitSafely()=true}
object BluetoothProfile {const val STATE_CONNECTED=2;const val STATE_DISCONNECTED=0}
object BluetoothStatusCodes {const val SUCCESS=0}
class Context {
    @Suppress("UNCHECKED_CAST") fun <T>getSystemService(clazz:Class<T>):T=BluetoothManager() as T
}
class BluetoothManager {val adapter:BluetoothAdapter?=BluetoothAdapter()}
class BluetoothAdapter {val isEnabled=true;val bluetoothLeScanner:BluetoothLeScanner?=BluetoothLeScanner()}
class BluetoothLeScanner {
    fun startScan(filters:Any?,settings:ScanSettings,callback:ScanCallback){callback.onBatchScanResults(mutableListOf(ScanResult(FakeBle.target)))}
    fun stopScan(callback:ScanCallback){}
}
class ScanSettings {class Builder{fun setScanMode(mode:Int)=this;fun build()=ScanSettings()};companion object{const val SCAN_MODE_LOW_LATENCY=2}}
open class ScanCallback {open fun onScanResult(type:Int,result:ScanResult){};open fun onBatchScanResults(results:MutableList<ScanResult>){};open fun onScanFailed(code:Int){}}
class ScanResult(val device:BluetoothDevice)
class BluetoothDevice(val address:String){
    companion object {const val TRANSPORT_LE=2;const val PHY_LE_1M_MASK=1}
    fun connectGatt(context:Context,auto:Boolean,callback:BluetoothGattCallback,transport:Int,phy:Int,handler:Handler):BluetoothGatt? {
        check(this===FakeBle.target){"Must connect using freshly scanned device"}
        check(!auto&&transport==TRANSPORT_LE&&phy==PHY_LE_1M_MASK)
        FakeBle.connections++
        val g=BluetoothGatt(callback)
        if(FakeBle.failFirst&&FakeBle.connections==1){FakeBle.old=g;callback.onConnectionStateChange(g,133,0)}else{
            FakeBle.old?.let{it.callback.onConnectionStateChange(it,133,0)}
            callback.onConnectionStateChange(g,0,2)
        };return g
    }
}
open class BluetoothGattCallback {
    open fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int){}
    open fun onServicesDiscovered(g:BluetoothGatt,status:Int){}
    open fun onDescriptorWrite(g:BluetoothGatt,d:BluetoothGattDescriptor,status:Int){}
    open fun onCharacteristicWrite(g:BluetoothGatt,c:BluetoothGattCharacteristic,status:Int){}
    open fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic){}
    open fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){}
}
class BluetoothGattDescriptor(val uuid:UUID){var value=byteArrayOf();companion object{val ENABLE_NOTIFICATION_VALUE=byteArrayOf(1,0);val ENABLE_INDICATION_VALUE=byteArrayOf(2,0)}}
class BluetoothGattCharacteristic(val uuid:UUID,val properties:Int){
    var writeType=0;var value=byteArrayOf();private val descriptor=BluetoothGattDescriptor(uuid(0x2902))
    fun getDescriptor(id:UUID):BluetoothGattDescriptor?=if(FakeBle.cccd)descriptor else null
    companion object {const val PROPERTY_WRITE_NO_RESPONSE=4;const val WRITE_TYPE_NO_RESPONSE=1;const val WRITE_TYPE_DEFAULT=2}
}
class BluetoothGattService(val uuid:UUID,val characteristics:List<BluetoothGattCharacteristic>){fun getCharacteristic(id:UUID)=characteristics.first{it.uuid==id}}
class BluetoothGatt(val callback:BluetoothGattCallback){
    val services=listOf(BluetoothGattService(uuid(0xfff0),listOf(BluetoothGattCharacteristic(uuid(0xfff1),FakeBle.writeProperties or if(FakeBle.multiNotify)16 else 0),BluetoothGattCharacteristic(uuid(0xfff2),if(FakeBle.indicate)32 else 16))))
    private var subscribed:UUID?=null
    fun discoverServices():Boolean{callback.onServicesDiscovered(this,0);return true}
    fun getService(id:UUID)=services.first{it.uuid==id}
    fun setCharacteristicNotification(c:BluetoothGattCharacteristic,on:Boolean):Boolean{subscribed=c.uuid;return true}
    fun writeDescriptor(d:BluetoothGattDescriptor,value:ByteArray):Int{d.value=value;writeDescriptor(d);return 0}
    fun writeDescriptor(d:BluetoothGattDescriptor):Boolean{
        check(d.value.contentEquals(if(FakeBle.indicate)byteArrayOf(2,0)else byteArrayOf(1,0)))
        val c=services[0].getCharacteristic(subscribed!!);callback.onCharacteristicChanged(this,c,"ELM startup\r>".toByteArray())
        callback.onDescriptorWrite(this,d,0);return true
    }
    fun writeCharacteristic(c:BluetoothGattCharacteristic,value:ByteArray,type:Int):Int{c.value=value;c.writeType=type;writeCharacteristic(c);return 0}
    fun writeCharacteristic(c:BluetoothGattCharacteristic):Boolean{
        callback.onCharacteristicWrite(this,c,if(FakeBle.writeError)3 else 0)
        val command=c.value.toString(Charsets.US_ASCII).trim()
        FakeBle.commands.add(command)
        val target=if(FakeBle.uartOnFirst)0 else 1
        if(!FakeBle.writeError && subscribed==services[0].characteristics[target].uuid && (FakeBle.requiredType==0||c.writeType==FakeBle.requiredType) && !(FakeBle.silentPoll&&command=="010C")){
            val bytes = when {
                command == "ATZ" -> "ELM327 v2.1\r" + if (FakeBle.partial) "" else ">"
                FakeBle.rejectEcho -> "?\r>"
                command.startsWith("01") && FakeBle.mode01Replies != null ->
                    FakeBle.mode01Replies?.get(command) ?: "NO DATA\r>"
                else -> "OK\r>"
            }
            callback.onCharacteristicChanged(this,services[0].characteristics[target],bytes.toByteArray())};return true
    }
    fun disconnect(){}
    fun close(){FakeBle.closed++}
}
object FakeBle {
    val target=BluetoothDevice("81:23:45:67:89:BA")
    var connections=0;var closed=0;var failFirst=false;var indicate=false;var writeError=false;var cccd=true;var old:BluetoothGatt?=null
    var multiNotify=false;var uartOnFirst=false;var requiredType=0;var writeProperties=12;var partial=false;var rejectEcho=false;var silentPoll=false
    var mode01Replies: Map<String, String>? = null
    val commands=mutableListOf<String>()
    fun reset(){connections=0;closed=0;failFirst=false;indicate=false;writeError=false;cccd=true;old=null;multiNotify=false;uartOnFirst=false;requiredType=0;writeProperties=12;partial=false;rejectEcho=false;silentPoll=false;mode01Replies=null;commands.clear()}
}
fun main(args:Array<String>){
    val dir=File(args[0]);var checks=0
    for(sdk in listOf(32,33))for(indicate in listOf(false,true)){
        FakeBle.reset();Build.VERSION.SDK_INT=sdk;FakeBle.indicate=indicate
        Journal(File(dir,"ble-$sdk-$indicate.jsonl"),"zd8-mt",FakeBle.target.address).use{journal->
            BleCapture(Context(),journal){false}.use{capture->
                capture.connect(FakeBle.target.address)
                check(capture.query("ATZ","init")=="ELM327 v2.1\r>");checks++
                check(capture.query("ATE0","init")=="OK\r>");checks++
            }
        };check(FakeBle.closed==1);checks++
    }
    FakeBle.reset();FakeBle.failFirst=true
    Journal(File(dir,"retry.jsonl"),"all",FakeBle.target.address).use{j->BleCapture(Context(),j){false}.use{it.connect(FakeBle.target.address);it.check()}}
    check(FakeBle.connections==2&&FakeBle.closed==2);checks++
    FakeBle.reset();FakeBle.writeError=true
    Journal(File(dir,"write-error.jsonl"),"all",FakeBle.target.address).use{j->BleCapture(Context(),j){false}.use{c->
        c.connect(FakeBle.target.address);check(runCatching{c.query("ATZ","init")}.exceptionOrNull()?.message?.contains("status=3")==true);checks++
    }}
    fun ep(s:Int,id:Int,p:Int,cccd:Boolean=true)=BleEndpoint(uuid(s).toString(),uuid(id).toString(),p,cccd)
    val fallback=BleChannelPolicy.select(listOf(ep(0xfff0,0xfff1,28),ep(0xfff0,0xfff2,16,false)))
    check(fallback.receive.id==uuid(0xfff1).toString());checks++
    check(BleChannelPolicy.select(listOf(ep(0xffe0,0xffe1,28))).receive.id==uuid(0xffe1).toString());checks++
    check(runCatching{BleChannelPolicy.select(listOf(ep(0xffe0,0xffe1,28),ep(0xffe5,0xffe6,28)))}.isFailure);checks++
    check(runCatching{BleChannelPolicy.select(listOf(ep(0xfff0,0xfff1,12),ep(0xfff0,0xfff2,16,false)))}.isFailure);checks++
    // Regression: both FFF1 and FFF2 notify. The gauge subscribes FFF1, not the UUID-preferred FFF2.
    val dual=listOf(ep(0xfff0,0xfff1,28),ep(0xfff0,0xfff2,16))
    check(BleChannelPolicy.select(dual).receive.id==uuid(0xfff1).toString());checks++
    FakeBle.reset();FakeBle.multiNotify=true;FakeBle.uartOnFirst=true
    Journal(File(dir,"same-channel.jsonl"),"all",FakeBle.target.address).use{j->BleCapture(Context(),j){false}.use{c->c.connect(FakeBle.target.address);c.initializeElm()}}
    check(FakeBle.connections==1&&FakeBle.commands==listOf("ATZ","ATE0"));checks++
    // A successful Android write callback is not evidence the adapter consumed the command.
    FakeBle.reset();FakeBle.requiredType=BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
    Journal(File(dir,"write-fallback.jsonl"),"all",FakeBle.target.address).use{j->BleCapture(Context(),j){false}.use{c->c.connect(FakeBle.target.address);c.initializeElm()}}
    check(FakeBle.connections==2&&FakeBle.closed==2&&FakeBle.commands==listOf("ATZ","ATZ","ATE0"));checks++
    FakeBle.reset();FakeBle.multiNotify=true;FakeBle.writeProperties=4 // first subscription is a silent non-UART endpoint
    Journal(File(dir,"notify-fallback.jsonl"),"all",FakeBle.target.address).use{j->BleCapture(Context(),j){false}.use{c->c.connect(FakeBle.target.address);c.initializeElm()}}
    check(FakeBle.connections==2&&FakeBle.commands==listOf("ATZ","ATZ","ATE0"));checks++
    FakeBle.reset();FakeBle.partial=true
    Journal(File(dir,"partial.jsonl"),"all",FakeBle.target.address).use{j->BleCapture(Context(),j){false}.use{c->c.connect(FakeBle.target.address);check(runCatching{c.initializeElm()}.isFailure)}}
    check(FakeBle.connections==1);checks++ // partial response must not be retried or mixed
    FakeBle.reset();FakeBle.rejectEcho=true
    Journal(File(dir,"bad-echo.jsonl"),"all",FakeBle.target.address).use{j->BleCapture(Context(),j){false}.use{c->c.connect(FakeBle.target.address);check(runCatching{c.initializeElm()}.isFailure)}}
    check(FakeBle.connections==1);checks++
    FakeBle.reset();FakeBle.silentPoll=true
    Journal(File(dir,"poll-timeout.jsonl"),"all",FakeBle.target.address).use{j->BleCapture(Context(),j){false}.use{c->c.connect(FakeBle.target.address);c.initializeElm();check(runCatching{c.query("010C")}.isFailure)}}
    check(FakeBle.connections==1&&FakeBle.commands==listOf("ATZ","ATE0","010C"));checks++
    FakeBle.reset()
    StudioState.reset("all")
    FakeBle.mode01Replies = mapOf(
        "0100" to "41 00 08 00 00 00\r>",
        "010C" to "41 0C 00 00\r>",
        "010D" to "41 0C 00 00\r>",
        "0110" to "41 10 00\r>",
        "016D" to "00D\r0: 41 6D 1B 01 90 00\r1: 21 00 01 90 00 19 00\r>",
        "017A" to "009\r0: 41 7A 01 00 16 00\r>",
        "01A6" to "41 A6 FF FF FF FF\r>",
        "01FF" to "41 FF 01\r>",
    )
    Journal(File(dir, "probe-all.jsonl"), "all", FakeBle.target.address).use { journal ->
        BleCapture(Context(), journal) { false }.use { capture ->
            capture.connect(FakeBle.target.address)
            capture.run("all", emptyList(), 0.02, 0, probeAllStandardPids = true) {
                check(FakeBle.commands.filter { it.startsWith("01") } == (0..255).map { "01%02X".format(it) })
                checks++
            }
        }
    }
    val previous=Thread.getDefaultUncaughtExceptionHandler();var forwarded:Throwable?=null
    Thread.setDefaultUncaughtExceptionHandler{_,error->forwarded=error}
    try {
        StudioCrashLog.install(File(dir,"crashes"),mapOf("version" to "test","api" to 33))
        val error=IllegalStateException("callback failure",IllegalArgumentException("cause"))
        Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(),error)
        check(forwarded===error);checks++
        check(File(dir,"crashes/last-app-crash.jsonl").readText().contains("IllegalArgumentException"));checks++
    }finally{Thread.setDefaultUncaughtExceptionHandler(previous)}
    println("PASS: $checks actual BLE connect/subscribe/write checks")
}
