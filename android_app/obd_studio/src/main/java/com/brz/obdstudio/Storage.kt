package com.brz.obdstudio

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.util.ArrayDeque

fun json(map: Map<String,Any?>): JSONObject = JSONObject().also { o -> map.forEach{(k,v)->o.put(k,v?:JSONObject.NULL)} }
data class DisplayRow(val command: String,val text: String,val decoded: Value?,val wall: Long) {
    fun rawDisplayText(now: Long): String {
        val reading = decoded?.let { "%s %.2f %s".format(java.util.Locale.US, it.name, it.value, it.unit).trimEnd() } ?: "原始数据"
        return "$command  $reading  · ${(now - wall) / 1000}s 前\n${text.replace('\r', ' ')}"
    }
}
object StudioState {
    @Volatile var active=false
    @Volatile var status="就绪"
    @Volatile var full=false
    @Volatile var startWall=0L
    @Volatile var savedFile=""
    @Volatile var quickStage=0
    @Volatile var quickLabel=""
    @Volatile var pollingStartedNs=0L
    var count=0L;var byteCount=0L
    var trip=Trip(false)
    var epoch=""
    val rows=linkedMapOf<String,DisplayRow>()
    val log=ArrayDeque<String>()
    val trend=ArrayDeque<Pair<Long,Double>>()
    @Synchronized fun message(s: String){status=s;log.addLast("${java.time.LocalTime.now().withNano(0)}  $s");while(log.size>200)log.removeFirst()}
    @Synchronized fun reset(profile: String){full=profile=="all";trip=Trip(full);epoch="";rows.clear();trend.clear();count=0;byteCount=0;startWall=System.currentTimeMillis()}
    @Synchronized fun bytes(n: Int){byteCount+=n}
    @Synchronized fun reply(command: String,raw: String,t: Double,complete: Boolean=true,sampled: Double=t,clientEpoch: String="") {
        val c=norm(command);if(c.startsWith("AT")||c.isEmpty())return
        if(clientEpoch.isNotEmpty()&&epoch!=clientEpoch){epoch=clientEpoch;trip=Trip(full)}
        val text = body(raw,c)
        val parts = (if (complete) splitGroup(c,text) else emptyMap()).ifEmpty { mapOf(c to text) }
        val fresh = complete && (full || t - sampled < 5)
        val values = parts.mapValues { (pid,part) -> if (fresh) decode(pid,part) else null }
        trip.updateMany(values.mapValues { it.value?.value },t,sampled)
        val now = System.currentTimeMillis()
        for ((pid,part) in parts) {
            val value = values[pid]
            rows[pid] = DisplayRow(pid,part.trim(),value,now)
            if (pid == "010C" && value != null) {
                trend.addLast(now to value.value)
                while (trend.size > 2000 || now - trend.first.first > 60000) trend.removeFirst()
            }
        }
        count++
    }
    @Synchronized fun snapshot(): ViewState=ViewState(status,active,full,count,byteCount,rows.values.toList(),log.toList(),trend.toList(),trip.snapshot())
}
data class ViewState(val status: String,val active: Boolean,val full: Boolean,val count: Long,val bytes: Long,val rows: List<DisplayRow>,val log: List<String>,val trend: List<Pair<Long,Double>>,val metrics: Map<String,Any?>)
class Journal(val file: File,val profile: String,val address: String) : AutoCloseable {
    private val out=file.outputStream().bufferedWriter(Charsets.UTF_8)
    val start=System.nanoTime()
    private var closed=false
    fun time()=(System.nanoTime()-start)/1e9
    init { emit("session",mapOf("schema" to 1,"utc" to Instant.now().toString(),"address" to address,"protocol" to 6,"header" to "7E0","headers" to false,"profile" to profile)) }
    @Synchronized fun emit(kind: String,fields: Map<String,Any?> = emptyMap()) {if(closed)return;out.write(json(linkedMapOf("kind" to kind,"t" to time())+fields).toString());out.newLine();out.flush()}
    @Synchronized override fun close(){if(closed)return;emit("end");closed=true;out.close()}
}
fun loadTape(file: File): Tape {
    val samples=mutableMapOf<String,MutableList<Sample>>();val discovery=mutableMapOf<String,String>()
    var vehicle="";var session: JSONObject?=null;var end=0.0
    val trailing=RandomAccessFile(file,"r").use {r->if(r.length()==0L)false else {r.seek(r.length()-1);r.readByte().toInt()!=10}}
    file.bufferedReader(Charsets.UTF_8).use { reader ->
        var line=reader.readLine()
        while(line!=null){val next=reader.readLine();if(next==null&&trailing){StudioState.message("忽略文件末尾不完整行");break}
            val r=JSONObject(line);end=maxOf(end,r.getDouble("t"))
            if(r.getString("kind")=="session")session=r
            if(r.optString("kind")=="capture_stage")vehicle=r.optString("vehicle","")
            if(r.getString("kind")=="reply"&&r.optBoolean("complete")) {
                val c=norm(r.getString("command"));val key=r.getString("header")+"/"+c
                if(!c.startsWith("AT")) {
                    val text=body(unhex(r.getString("hex")).toString(Charsets.US_ASCII),c)
                    if(r.getString("phase")=="discovery")discovery[key]=text
                    if(r.getString("phase")=="poll") {
                        val t = r.getDouble("t")
                        samples.getOrPut(key){mutableListOf()}.add(Sample(t,text))
                        for ((pid,part) in splitGroup(c,text)) {
                            samples.getOrPut(r.getString("header")+"/"+pid){mutableListOf()}.add(Sample(t,part))
                        }
                    }
                }
            };line=next
        }
    }
    require(session?.optInt("schema")==1 && session?.has("headers")==true && session?.get("headers")==false){"需要 schema 1 / ATH0 JSONL 文件"}
    require(samples.isNotEmpty()){"文件没有完整实时采样"}
    samples.values.forEach{it.sortBy(Sample::t)}
    val origin=samples.values.minOf{it.first().t}
    return Tape(session!!.optString("profile","gauge"),samples,discovery,origin,maxOf(.001,end-origin),vehicle.ifBlank{session!!.optString("profile","gauge")})
}
