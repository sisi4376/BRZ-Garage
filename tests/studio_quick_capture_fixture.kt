package com.brz.obdstudio

import java.io.File
import java.io.ByteArrayOutputStream
import org.json.JSONObject

class Intent(values: Map<String,String> = emptyMap()) {
    val values=values.toMutableMap();var action=""
    constructor(context:Any,target:Class<*>):this()
    fun getStringExtra(key:String)=values[key]
    fun setAction(value:String):Intent{action=value;return this}
    fun putExtra(key:String,value:String):Intent{values[key]=value;return this}
}
object Uri { fun parse(value:String)=value }
object DocumentsContract {
    fun getTreeDocumentId(root:String)=root
    fun buildDocumentUriUsingTree(root:String,id:String)=root
    fun createDocument(resolver:Resolver,parent:String,mime:String,name:String):String?=parent+"/"+name
}
class Resolver(var failed:Boolean=false) {
    fun openOutputStream(uri:String):java.io.OutputStream? {
        if(failed)throw SecurityException("directory permission lost")
        return ByteArrayOutputStream()
    }
}
class BleCapture(val context:QuickFixture,val journal:Journal,val cancelled:()->Boolean):AutoCloseable {
    fun connect(address:String){context.calls.add("connect:"+journal.profile)}
    fun run(mode:String,extra:List<String>,duration:Double,interval:Long,strictDiscovery:Boolean,onStarted:()->Unit){
        check(duration==300.0 && strictDiscovery && extra.isEmpty())
        context.calls.add("poll:"+mode);onStarted()
        StudioState.reply("010D","41 0D 3C\r>",1.0)
        journal.emit("reply",mapOf("command" to "010D","header" to "7E0","phase" to "poll","started" to 0,"hex" to "41 0D 3C\r>".toByteArray().hex(),"complete" to true))
        if(context.failProfile==mode)error("link lost")
        if(context.stopProfile==mode){context.cancelled=true;throw InterruptedException("stopped")}
    }
    override fun close(){context.calls.add("close:"+journal.profile)}
}
class QuickFixture(val filesDir:File){
    var cancelled=false;var failProfile="";var stopProfile=""
    val contentResolver=Resolver();val calls=mutableListOf<String>()
    fun run(tree:Boolean=false){
        val intent=Intent(if(tree)mapOf("tree" to "folder","profile" to "zc6-at")else mapOf("profile" to "zc6-at"))
        runQuickCapture("zc6-at",{cancelled}){index,stage->
            if(index==2){
                val saved=File(filesDir,"recordings/job_1_all.summary.json")
                check(saved.isFile && JSONObject(saved.readText()).getBoolean("completed"))
                check(calls.last()=="close:all")
            }
            record("AA:BB:CC:DD:EE:FF",stage.profile,emptyList(),stage.seconds,30L,intent,"job",index,"OBDBLE")
        }
    }
    PRODUCTION_RECORD
    PRODUCTION_SAVE
}
fun runQuickCaptureFileTests(root:File){
    fun fixture(name:String)=QuickFixture(File(root,name).apply{mkdirs()})
    val normal=fixture("quick-normal");normal.run()
    check(normal.calls==listOf("connect:all","poll:all","close:all","connect:zc6-at","poll:zc6-at","close:zc6-at"))
    check(File(normal.filesDir,"recordings").listFiles()!!.size==4)
    for(name in listOf("job_1_all","job_2_zc6-at")){
        val recording=File(normal.filesDir,"recordings/$name.jsonl").readLines().map{JSONObject(it)}
        check(recording.last().getString("kind")=="end")
        check(recording.first{it.getString("kind")=="capture_stage_end"}.getBoolean("completed"))
        check(File(normal.filesDir,"recordings/$name.summary.json").isFile)
    }
    val stopped=fixture("quick-stop-first");stopped.stopProfile="all"
    check(runCatching{stopped.run()}.exceptionOrNull() is InterruptedException)
    check(stopped.calls==listOf("connect:all","poll:all","close:all"))
    check(!JSONObject(File(stopped.filesDir,"recordings/job_1_all.summary.json").readText()).getBoolean("completed"))
    val dropped=fixture("quick-drop-second");dropped.failProfile="zc6-at"
    check(runCatching{dropped.run()}.isFailure)
    check(JSONObject(File(dropped.filesDir,"recordings/job_1_all.summary.json").readText()).getBoolean("completed"))
    check(!JSONObject(File(dropped.filesDir,"recordings/job_2_zc6-at.summary.json").readText()).getBoolean("completed"))
    val export=fixture("quick-export-failure");export.contentResolver.failed=true;export.run(true)
    check(File(export.filesDir,"recordings").listFiles()!!.size==4)
    println("PASS: actual service stage persistence, stop, link failure and failed external export")
}
