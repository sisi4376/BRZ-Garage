package com.brz.obdstudio

class SelectionPreferences {
    val values=mutableMapOf<String,String>()
    fun edit()=this
    fun putString(key:String,value:String):SelectionPreferences{values[key]=value;return this}
    fun getString(key:String,fallback:String?)=values[key]?:fallback
    fun apply(){}
}
object BluetoothAdapter { fun checkBluetoothAddress(value:String)=value.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")) }
object StudioService
class DeviceSelectionFixture {
    val devices=linkedMapOf<String,Pair<String,Int>>()
    val capturePrefs=SelectionPreferences();val exportPrefs=SelectionPreferences()
    var address="";var obdName="";var scanning=false;var vehicleIndex=0;var page=0
    var started:Intent?=null
    fun render(){}
    fun getPreferences(mode:Int)=exportPrefs
    fun stopScan(){scanning=false}
    fun startForegroundService(intent:Intent){started=intent}
    fun showDevices(){chooseObd()}
    fun start(){beginQuickRecord()}
    PRODUCTION_CHOOSE_OBD
    PRODUCTION_BEGIN_QUICK
}
fun runStudioDeviceSelectionTests(){
    val fixture=DeviceSelectionFixture()
    check(runCatching{fixture.start()}.isFailure && fixture.started==null)
    fixture.devices["AA:BB:CC:DD:EE:01"]="OBDBLE" to -40
    fixture.devices["AA:BB:CC:DD:EE:02"]="OBDBLE" to -60
    fixture.devices["AA:BB:CC:DD:EE:03"]="未命名设备" to -80
    fixture.showDevices()
    val dialog=android.app.AlertDialog.last!!
    check(dialog.labels.size==3 && dialog.labels[1].contains("OBDBLE") && dialog.labels[1].contains("AA:BB:CC:DD:EE:02"))
    check(fixture.address.isBlank() && fixture.capturePrefs.values.isEmpty())
    dialog.pick(1)
    check(fixture.address=="AA:BB:CC:DD:EE:02" && fixture.obdName=="OBDBLE")
    check(fixture.capturePrefs.getString("obd_address",null)==fixture.address)
    check(fixture.capturePrefs.getString("obd_name",null)==fixture.obdName)
    fixture.vehicleIndex=3;fixture.exportPrefs.putString("tree","export-folder")
    fixture.start()
    check(fixture.started!!.action=="QUICK_RECORD")
    check(fixture.started!!.getStringExtra("address")=="AA:BB:CC:DD:EE:02")
    check(fixture.started!!.getStringExtra("obd_name")=="OBDBLE")
    check(fixture.started!!.getStringExtra("profile")=="zc6-at")
    check(fixture.started!!.getStringExtra("tree")=="export-folder" && fixture.page==2)
    fixture.scanning=true;fixture.started=null
    check(runCatching{fixture.start()}.isFailure && fixture.started==null)
    fixture.showDevices();android.app.AlertDialog.last!!.cancel()
    check(fixture.address=="AA:BB:CC:DD:EE:02")
    fixture.showDevices();android.app.AlertDialog.last!!.pick(2)
    fixture.scanning=false;fixture.start()
    check(fixture.started!!.getStringExtra("address")=="AA:BB:CC:DD:EE:03")
    println("PASS: actual device picker requires selection, persists address, distinguishes duplicate/unnamed devices and starts selected capture")
}
