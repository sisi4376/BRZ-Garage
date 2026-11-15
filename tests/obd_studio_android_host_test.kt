package com.brz.obdstudio

import java.io.File
import kotlin.math.abs

fun main(args:Array<String>){
    val dir=File(args[0]);var checks=0
    fun equal(actual:Any?,expected:Any?){check(actual==expected){"$actual != $expected"};checks++}
    fun near(actual:Double,expected:Double){check(abs(actual-expected)<1e-7){"$actual != $expected"};checks++}
    val modes=listOf("zd8-mt","zd8-at","zc6-mt","zc6-at")
    for(mode in modes){
        val poll=GaugePoll(mode);val expected=File(dir,"$mode.tsv").readLines()
        for(line in expected){val parts=line.split('\t');val commands=poll.commands(parts[0].toDouble());equal(commands.joinToString(","),parts[1]);commands.forEach{c->if(c=="0140")poll.observe(c,"41 40 FE DC A0 51\r>")else if(c=="012F")poll.observe(c,"41 2F 6D\r>")}}
    }
    for(full in listOf(false,true)){
        val trip=Trip(full);trip.update("010D",60.0,0.0);trip.update("015E",6.0,0.0);trip.update("0142",12.0,60.0)
        near(trip.km,if(full)1.0 else 60*5.0/3600);near(trip.fuel,if(full).1 else 6*5.0/3600)
        equal(trip.snapshot()["fuel_rate_lph"],if(full)6.0 else null)
    }
    val trip=Trip(false)
    for(t in 0..60){trip.update("010D",60.0,t.toDouble());trip.update("015E",6.0,t.toDouble())}
    near(trip.km,1.0);near(trip.fuel,.1);near(trip.snapshot()["average_l100km"] as Double,10.0)
    trip.update("015E",0.0,60.0);equal(trip.rate(60.0).first,0.0)
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
    Journal(journalFile,"zc6-at","FA:KE").use{j->j.emit("reply",mapOf("command" to "2101","header" to "7E0","phase" to "poll","hex" to "61 01".toByteArray().hex(),"complete" to true,"started" to 0.0))}
    val roundtrip=loadTape(journalFile);equal(roundtrip.profile,"zc6-at")
    File(dir,"truncated.jsonl").writeText(File(dir,"synthetic.jsonl").readText()+"{broken")
    equal(loadTape(File(dir,"truncated.jsonl")).samples.size,3)
    println("PASS: $checks cross-platform checks")
}
