package com.brz.obdstudio

import java.io.File
import kotlin.math.abs

fun main(args:Array<String>){
    val dir=File(args[0]);var checks=0
    fun equal(actual:Any?,expected:Any?){check(actual==expected){"$actual != $expected"};checks++}
    fun near(actual:Double,expected:Double){check(abs(actual-expected)<1e-7){"$actual != $expected"};checks++}
    val modes=listOf("zd8-mt","zd8-at","zc6-mt","zc6-at")
    runQuickCaptureFileTests(dir)
    runStudioDeviceSelectionTests()
    equal(supportedBlock("41 00 00 00 00 00\r>",0),emptySet<Int>())
    equal(supportedBlock("NO DATA\r>",0),null)
    for(mode in modes){
        val captures=mutableListOf<String>()
        runQuickCapture(mode,{false}){index,stage->
            equal(index,captures.size+1);equal(stage.seconds,300.0);captures.add(stage.profile)
        }
        equal(captures,listOf("all",mode))
        var cancelled=false;val partial=mutableListOf<String>()
        check(runCatching{runQuickCapture(mode,{cancelled}){_,stage->partial.add(stage.profile);cancelled=true}}.exceptionOrNull() is InterruptedException)
        equal(partial,listOf("all"))
        val failed=mutableListOf<String>()
        check(runCatching{runQuickCapture(mode,{false}){_,stage->failed.add(stage.profile);error("link dropped")}}.isFailure)
        equal(failed,listOf("all"))
        var attempted=false
        check(runCatching{runQuickCapture(mode,{true}){_,_->attempted=true}}.exceptionOrNull() is InterruptedException)
        equal(attempted,false)
    }
    check(runCatching{quickCapturePlan("all")}.isFailure)
    for(mode in modes){
        val poll=GaugePoll(mode);val expected=File(dir,"$mode.tsv").readLines()
        for(line in expected){val parts=line.split('\t');val commands=poll.commands(parts[0].toDouble());equal(commands.joinToString(","),parts[1]);commands.forEach{c->if(c=="0140")poll.observe(c,"41 40 FE DC A0 51\r>")else if(c=="012F")poll.observe(c,"41 2F 6D\r>")}}
    }
    val groupCommand = "010C0D10440E0F"
    val groupRaw = "010\r0: 41 0C 0C 80 0D 20\r1: 10 01 F4 44 80 00 0E\r2: 7F 0F 44\r>"
    equal(splitGroup(groupCommand,groupRaw).size,6)
    equal(splitGroup(groupCommand,groupRaw.replace("1:","2:")).size,0)
    StudioState.reset("zd8-mt")
    StudioState.reply(groupCommand,groupRaw,0.0)
    equal(StudioState.rows["010E"]?.decoded?.value,-0.5)
    equal(StudioState.rows["010F"]?.decoded?.value,28.0)
    equal(StudioState.rows["010C"]?.decoded?.value,800.0)
    equal(StudioState.count,1L)
    for ((pid, raw) in listOf("0104" to "41 04 00", "0111" to "41 11 2B", "012F" to "41 2F 6D")) {
        StudioState.reply(pid, raw, 1.0)
        val row = StudioState.rows.getValue(pid)
        check(row.rawDisplayText(row.wall).contains("%  · 0s 前\n$raw"))
    }
    // Optionally exercise every complete polling reply from a local vehicle recording.
    args.getOrNull(1)?.let { path ->
        StudioState.reset("all")
        File(path).useLines { lines ->
            lines.map { org.json.JSONObject(it) }
                .filter { it.optString("kind") == "reply" && it.optString("phase") == "poll" && it.optBoolean("complete") }
                .forEach { r ->
                    val command = r.getString("command")
                    val reply = unhex(r.getString("hex")).toString(Charsets.US_ASCII)
                    if (command.length == 4 && command.startsWith("01")) {
                        equal(hasMode01Data(command.takeLast(2).toInt(16), command, reply), true)
                    }
                    StudioState.reply(command, reply, r.getDouble("t"))
                    StudioState.snapshot().rows.forEach { it.rawDisplayText(it.wall) }
                }
        }
        println("Rendered ${StudioState.count} complete vehicle replies, ${StudioState.rows.size} PID rows")
    }
    val failed = GaugePoll("zc6-at")
    repeat(2) { failed.observe(failed.commands(0.0).single(),"NO DATA\r>"); failed.commands(0.0) }
    equal(failed.commands(0.0),listOf("010C","010D","0110","0111","0104","0103"))
    for(full in listOf(false,true)){
        val trip=Trip(full);trip.update("010D",60.0,0.0);trip.update("010C",2000.0,0.0);trip.update("0110",6.0*745*14.7/3600,0.0);trip.update("0142",12.0,60.0)
        near(trip.km,if(full)1.0 else 60*5.0/3600);near(trip.fuel,if(full).1 else 6*5.0/3600)
        if(full)near(trip.rate(60.0).first!!,6.0) else equal(trip.rate(60.0).first,null)
    }
    val trip=Trip(false)
    for(t in 0..60){trip.update("010D",60.0,t.toDouble());trip.update("010C",2000.0,t.toDouble());trip.update("0110",6.0*745*14.7/3600,t.toDouble())}
    near(trip.km,1.0);near(trip.fuel,.1);near(trip.snapshot()["average_l100km"] as Double,10.0)
    trip.update("015E",0.0,60.0);near(trip.rate(60.0).first!!,6.0)
    val at=Trip(true);listOf("010C" to 2000.0,"010D" to 60.0,"0110" to 5.0,"0111" to 14.0,"0103" to decode("0103","41 03 04 00")!!.value).forEach{at.update(it.first,it.second,0.0)}
    equal(at.rate(1.0).first,0.0);at.update("015E",0.0,5.0);near(at.fuel,5.0/14.7/745*2.5);check(at.rate(5.0).first!!>0)
    val shared=Trip(false)
    listOf("010C" to 2000.0,"010D" to 60.0,"0110" to 5.0,"0111" to 16.0,"0104" to 3.0).forEach{shared.update(it.first,it.second,0.0)}
    shared.update("015E",0.0,.399);check(shared.rate(.399).first!!>0)
    shared.update("010C",1000.0,1.0);near(shared.fuel,5.0/14.7/745*.4)
    shared.update("0104",5.0,1.0);check(shared.rate(1.0).first==0.0)
    shared.update("010C",950.0,1.1);check(shared.rate(1.1).first!!>0)
    val maf=Trip(false);maf.update("010C",2000.0,0.0);maf.update("0110",14.7,0.0);near(maf.rate(0.0).first!!,3600.0/745)
    for(size in listOf(40,41)){
        val payload=ByteArray(size){0x55};payload[0]=0x61;payload[1]=1;payload[size-5]=130.toByte()
        val rows=listOf("%03X".format(size),"0: "+payload.take(6).toByteArray().hex())+payload.drop(6).chunked(7).mapIndexed{i,c->"${i+1}: "+c.toByteArray().hex()}
        equal(decode("2101",payload.hex())?.value,90.0);equal(decode("2101",rows.joinToString("\r"))?.value,90.0)
        equal(decode("2101",rows.dropLast(1).joinToString("\r")),null)
        equal(decode("2101",rows.joinToString("\r").replace("1:","2:")),null)
    }
    equal(decode("01A6","41 A6 FF FF FF FF"),null);equal(decode("01A6","41 A6 00 01 E2 40")?.value,12345.6)
    val tape=loadTape(File(dir,"synthetic.jsonl"));equal(tape.profile,"all");equal(tape.samples.size,3)
    for(setting in listOf("normal","fast-loop")){
        var now=0.0;val elm=ReplayElm(tape,if(setting=="normal")1.0 else 2.0,setting!="normal"){now}
        File(dir,"$setting.tsv").readLines().forEach{line->val p=line.split('\t');now=p[0].toDouble();equal(elm.command(p[1]).bytes.hex(),p[2])}
    }
    var clock=0.0;val fragmented=ReplayElm(tape,1.0,false){clock}
    equal(fragmented.feed("ATE".toByteArray()).size,0)
    equal(fragmented.feed("0\r\n010C\r010D\r".toByteArray()).size,3)
    equal(fragmented.feed(("X".repeat(300)+"\r").toByteArray()).single().bytes.toString(Charsets.US_ASCII),"?\r>")
    val journalFile=File(dir,"android-roundtrip.jsonl")
    Journal(journalFile,"zc6-at","FA:KE").use { journal ->
        journal.emit("reply",mapOf("command" to groupCommand,"header" to "7E0","phase" to "poll",
            "hex" to groupRaw.toByteArray().hex(),"complete" to true,"started" to 0.0))
    }
    val roundtrip=loadTape(journalFile)
    equal(roundtrip.profile,"zc6-at")
    val groupedElm=ReplayElm(roundtrip,1.0,false){0.0}
    groupedElm.command("ATE0")
    equal(groupedElm.command(groupCommand).bytes.toString(Charsets.US_ASCII),groupRaw)
    equal(groupedElm.command("010E").bytes.toString(Charsets.US_ASCII),"41 0E 7F\r>")
    File(dir,"truncated.jsonl").writeText(File(dir,"synthetic.jsonl").readText()+"{broken")
    equal(loadTape(File(dir,"truncated.jsonl")).samples.size,3)
    println("PASS: $checks cross-platform checks")
}
