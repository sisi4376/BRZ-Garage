package com.brz.obdstudio

import kotlin.math.*

object Modes {
    val ids = listOf("zd8-mt", "zd8-at", "zc6-mt", "zc6-at", "all", "gauge", "zc6")
    val labels = listOf("ZD8 6MT · 仪表轮询", "ZD8 6AT · 仪表轮询", "ZC6 6MT · 仪表轮询", "ZC6 6AT · 仪表轮询", "全部标准 PID · 保留最近值", "仪表常用 PID", "ZC6 · 原有精简采集")
    fun gauge(id: String) = id in ids.take(4) || id == "zd8"
    val core = listOf(3,4,5,11,12,13,15,16,17,31,47,51,66,68,92,94,164,166)
}
fun norm(s: String): String {
    val v = s.uppercase().filterNot(Char::isWhitespace)
    return if (!v.startsWith("AT") && v.length % 2 == 1 && v.lastOrNull() in '1'..'9') v.dropLast(1) else v
}
fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
fun unhex(s: String): ByteArray {
    val v = s.filterNot(Char::isWhitespace)
    require(v.length % 2 == 0 && v.all { it in "0123456789abcdefABCDEF" }) { "Invalid hex" }
    return v.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
fun body(reply: String, cmd: String) = reply.replace(">", "").split('\r','\n').map(String::trim)
    .filter { it.isNotEmpty() && norm(it) != norm(cmd) }.joinToString("\r", postfix="\r")
fun supported(reply: String, base: Int): Set<Int> {
    val result = mutableSetOf<Int>()
    reply.split('\r','\n').forEach { line ->
        val s = line.filterNot(Char::isWhitespace).replace(">", "")
        if (s.matches(Regex("41%02X[0-9a-fA-F]{8}".format(base), RegexOption.IGNORE_CASE))) {
            val mask = s.substring(4).toLong(16)
            for (i in 1..32) if (mask and (1L shl (32-i)) != 0L) result.add(base+i)
        }
    }
    return result
}
class GaugePoll(val mode: String) {
    val oil = if (mode.startsWith("zc6")) "2101" else "015C"
    var slot = 0
    var slowIndex = 0
    var status: Boolean? = null
    var statusProbes=0
    val plan get() = listOf("010C","010F","010D","0105","0104","0111",oil,"0142","0103","0144","0110","012F","015E","0140")
    fun commands(t: Double): List<String> {
        val s=slot; val out=mutableListOf<String>()
        when(s) {
            5 -> { out.add(listOf("010F","0105",oil,"0142","012F","015E","0140")[slowIndex]); slowIndex=(slowIndex+1)%7 }
            0,1,2,4 -> out.add(mapOf(0 to "0110",1 to "0104",2 to "0111",4 to "0144").getValue(s))
            3 -> if(status != false) { if(status==null && statusProbes>=3)status=false else {if(status==null)statusProbes++;out.add("0103")} }
        }
        out.add(if(s%2!=0)"010D" else "010C")
        slot=(s+1)%6; return out
    }
    fun observe(cmd: String, reply: String) {
        reply.replace(">", "").split('\r','\n').forEach { line ->
            val d=runCatching { unhex(line).map { it.toInt() and 255 } }.getOrNull() ?: return@forEach
            when {
                cmd=="0100" && d.size>=6 && d.take(2)==listOf(65,0) -> status=d[2] and 32 != 0
                cmd=="0103" && d.size>=4 && d.take(2)==listOf(65,3) -> status=true
            }
        }
    }
}
data class Value(val name: String, val value: Double, val unit: String)
fun decode(cmd: String, text: String): Value? {
    if(cmd=="2101") return runCatching {
        val lines=text.replace(">", "").split('\r','\n').map(String::trim).filter(String::isNotEmpty)
        val p=if(lines.size==1) unhex(lines[0]) else {
            require(lines[0].length==3);val n=lines[0].toInt(16);require(n==40||n==41)
            var b=byteArrayOf()
            lines.drop(1).forEachIndexed { row,line ->
                require(line.startsWith("${row.toString(16).uppercase()}:"));val c=unhex(line.substring(2))
                require(c.size==min(if(row==0)6 else 7,n-b.size) && c.isNotEmpty());b+=c
            };require(b.size==n);b
        }
        require(p.size==40||p.size==41);require(p[0]==0x61.toByte()&&p[1]==1.toByte())
        Value("机油温度 · ZC6",(p[p.size-5].toInt() and 255)-40.0,"°C")
    }.getOrNull()
    val fields=mapOf("010C" to Triple("转速","rpm",2),"010D" to Triple("车速","km/h",1),"0105" to Triple("水温","°C",1),"015C" to Triple("油温","°C",1),"0111" to Triple("节气门","%",1),"0104" to Triple("负荷","%",1),"012F" to Triple("燃油余量","%",1),"0142" to Triple("电压","V",2),"0110" to Triple("MAF","g/s",2),"015E" to Triple("燃油流量","L/h",2),"0144" to Triple("指令 λ","",2),"01A6" to Triple("ECU 总里程","km",4))
    val f=fields[cmd]?:return null
    for(line in text.split('\r','\n')) {
        val d=runCatching{unhex(line)}.getOrNull()?:continue
        if(d.size!=f.third+2 || d[0]!=65.toByte() || (d[1].toInt() and 255)!=cmd.takeLast(2).toInt(16))continue
        var raw=0L;d.drop(2).forEach{raw=(raw shl 8)+(it.toInt() and 255)}
        if(cmd=="01A6" && raw==0xffffffffL)return null
        val n=when(cmd){"010C"->raw/4.0;"0105","015C"->raw-40.0;"0111","0104","012F"->raw*100.0/255;"0142"->raw/1000.0;"0110"->raw/100.0;"015E"->raw/20.0;"0144"->raw/32768.0;"01A6"->raw/10.0;else->raw.toDouble()}
        return Value(f.first,n,f.second)
    };return null
}
class Trip(val full: Boolean) {
    private val maxAge=if(full)Double.POSITIVE_INFINITY else 5.0
    val samples=mutableMapOf<String,Pair<Double,Double>>()
    var t: Double?=null;var km=0.0;var fuel=0.0;var pairKm=0.0;var pairFuel=0.0
    var kmSeconds=0.0;var fuelSeconds=0.0;var pairSeconds=0.0
    var hasSpeed=false;var hasFuel=false;var estimated=false
    fun get(c: String,now: Double): Double?=samples[c]?.takeIf{now-it.second>=0&&now-it.second<maxAge}?.first
    fun rate(now: Double): Pair<Double?,String> {
        get("015E",now)?.let{return it to "ECU"}
        val rpm=get("010C",now);if(rpm==0.0)return 0.0 to "发动机停止"
        val maf=get("0110",now)
        if(rpm==null||maf==null||maf<=0)return null to "等待燃油数据"
        val afr=((get("0144",now)?:1.0)*14.7).takeIf{it in 8.0..22.0}?:14.7
        return maf/afr/745*3600 to "MAF 汽油估算"
    }
    fun update(c: String,v: Double?,now: Double,sampled: Double=now) {
        val previous=t
        if(previous!=null) {
            require(now>=previous){"Time moved backwards"}
            val breaks=(listOf(previous,now)+samples.values.map{it.second+maxAge}.filter{it>previous&&it<now}).distinct().sorted()
            breaks.zipWithNext().forEach{(a,b)->
                val mid=(a+b)/2;val speed=get("010D",mid);val r=rate(mid);val dt=b-a
                if(speed!=null){km+=speed*dt/3600;kmSeconds+=dt}
                if(r.first!=null){fuel+=r.first!!*dt/3600;fuelSeconds+=dt;estimated=estimated||r.second.startsWith("MAF")}
                if(speed!=null&&r.first!=null){pairKm+=speed*dt/3600;pairFuel+=r.first!!*dt/3600;pairSeconds+=dt}
            }
        }
        t=now;if(v==null)samples.remove(c) else samples[c]=v to sampled
        hasSpeed=hasSpeed||get("010D",now)!=null;hasFuel=hasFuel||rate(now).first!=null
        estimated=estimated||rate(now).second.startsWith("MAF")
    }
    fun snapshot(): Map<String,Any?> {
        val now=t?:0.0;val speed=get("010D",now);val r=rate(now)
        return linkedMapOf("distance_km" to km.takeIf{hasSpeed},"fuel_l" to fuel.takeIf{hasFuel},"fuel_rate_lph" to r.first,"fuel_source" to r.second,"instant" to if(speed!=null&&r.first!=null) {if(speed<3)r.first else r.first!!*100/speed} else null,"instant_unit" to if(speed!=null&&speed<3)"L/h" else "L/100km","average_l100km" to (pairFuel*100/pairKm).takeIf{pairKm>=.01},"distance_seconds" to kmSeconds,"fuel_seconds" to fuelSeconds,"paired_seconds" to pairSeconds,"includes_maf_estimate" to estimated)
    }
}
data class Sample(val t: Double,val text: String)
data class Tape(val profile: String,val samples: Map<String,List<Sample>>,val discovery: Map<String,String>,val origin: Double,val duration: Double)
data class Reply(val command: String,val bytes: ByteArray,val t: Double?=null,val sampled: Double?=null,val cycle: Int=0)
class ReplayElm(val tape: Tape,val speed: Double,val loop: Boolean,val clock: ()->Double) {
    var echo=true;var spaces=true;var headers=false;var lf=false;var header="7E0"
    var started: Double?=null
    private val pending=StringBuilder();private var discard=false;private var afterCr=false
    fun reset(){echo=true;spaces=true;headers=false;lf=false;header="7E0"}
    fun command(input: String): Reply {
        val c=norm(input);val oldEcho=echo
        var stamp: Double?=null;var sampled: Double?=null;var cycle=0
        val response=if(c.startsWith("AT")) when {
            c in listOf("ATZ","ATWS","ATD")->{reset();if(c=="ATD")"OK\r" else "ELM327 v1.5\r"}
            c=="ATI"->"ELM327 v1.5\r";c=="ATRV"->"NO DATA\r";c=="ATDPN"->"6\r";c=="ATDP"->"ISO 15765-4 (CAN 11/500)\r"
            c.matches(Regex("AT[EHSL][01]"))->{val on=c.last()=='1';when(c[2]){'E'->echo=on;'H'->headers=on;'S'->spaces=on;'L'->lf=on};"OK\r"}
            c.matches(Regex("ATSH[0-9A-F]{3}"))->{header=c.drop(4);"OK\r"}
            c in listOf("ATSP0","ATSP6","ATSPA6","ATTP6","ATAL","ATPC","ATAR","ATCRA")||c.matches(Regex("AT(?:AT[012]|ST[0-9A-F]{2}|CAF[01]|CFC[01]|CRA[0-9A-F]{3})"))->"OK\r"
            else->"?\r"
        } else {
            val key="$header/$c"
            var result=tape.discovery[key]
            if(result==null) {
                if(started==null)started=clock()
                var elapsed=(clock()-started!!)*speed
                if(loop){cycle=floor(elapsed/tape.duration).toInt();elapsed%=tape.duration}
                stamp=tape.origin+min(elapsed,tape.duration)
                val rows=tape.samples[key].orEmpty()
                // Binary search keeps long recordings responsive.
                var low=0;var high=rows.size
                while(low<high){val mid=(low+high)/2;if(rows[mid].t<=stamp)low=mid+1 else high=mid}
                val row=if(low>0&&elapsed<=tape.duration)rows[low-1] else null
                sampled=row?.t;result=row?.text?:"NO DATA\r"
            }
            if(headers)"NO DATA\r" else result
        }
        val formatted=if(spaces)response else response.split('\r').joinToString("\r"){if(it.matches(Regex("[0-9a-fA-F :]+")))it.replace(" ","") else it}
        var out=(if(oldEcho)input+"\r" else "")+formatted
        if(lf)out=out.replace("\r","\r\n")
        return Reply(input,(out+">").toByteArray(Charsets.US_ASCII),stamp,sampled,cycle)
    }
    fun feed(bytes: ByteArray): List<Reply> {
        val out=mutableListOf<Reply>()
        bytes.forEach { b->val c=(b.toInt() and 255).toChar()
            if(c=='\n'&&afterCr){afterCr=false;return@forEach}
            afterCr=c=='\r'
            if(c=='\r'||c=='\n') {
                val cmd=pending.toString().trim();pending.clear()
                out+=if(discard)Reply("<oversize command>","?\r>".toByteArray()) else if(cmd.isEmpty())Reply("","\r>".toByteArray()) else command(cmd)
                discard=false
            } else if(!discard) {if(pending.length>=256){pending.clear();discard=true}else pending.append(c)}
        };return out
    }
}
