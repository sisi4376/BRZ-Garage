package com.brz.obdstudio

import android.app.*
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.DocumentsContract
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class StudioService:Service(){
    @Volatile private var cancelled=false
    private var worker:Thread?=null
    private var wake:PowerManager.WakeLock?=null
    private val wakeHandler=Handler(Looper.getMainLooper())
    private val renewWake=object:Runnable{override fun run(){
        if(StudioState.active){wake?.acquire(600000L);wakeHandler.postDelayed(this,300000L)}
    }}
    override fun onBind(intent:Intent?)=null
    override fun onStartCommand(intent:Intent?,flags:Int,id:Int):Int{
        if(intent?.action=="STOP"){cancelled=true;worker?.interrupt();if(worker==null)stopSelf();return START_NOT_STICKY}
        if(intent==null){stopSelf();return START_NOT_STICKY}
        if(StudioState.active)return START_NOT_STICKY
        cancelled=false;StudioState.startWall=System.currentTimeMillis();StudioState.active=true
        val channel="obd-studio";getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(channel,"OBD 采集与回放",NotificationManager.IMPORTANCE_LOW))
        val open=PendingIntent.getActivity(this,1,Intent(this,StudioActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,2,Intent(this,StudioService::class.java).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(31,Notification.Builder(this,channel).setSmallIcon(com.brz.obdstudio.R.drawable.studio_icon).setContentTitle("OBD Studio 正在运行").setContentText("点击打开；使用停止按钮保存并断开蓝牙").setContentIntent(open).setOngoing(true).addAction(Notification.Action.Builder(null,"停止并保存",stop).build()).build())
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OBDStudio:recording").apply{setReferenceCounted(false);acquire(600000L)}
        wakeHandler.postDelayed(renewWake,300000L)
        worker=Thread({
            var journal:Journal?=null
            try {
                if(intent.action=="REPLAY"){
                    val file=File(intent.getStringExtra("file")?:error("未选择文件"));val tape=loadTape(file)
                    StudioState.reset(tape.profile)
                    StudioState.message("录制：%.1f 秒，%d 种请求".format(tape.duration,tape.samples.size))
                    BleReplay(this,tape,intent.getDoubleExtra("speed",1.0),intent.getBooleanExtra("loop",false)){cancelled}.use{it.run()}
                }else {
                    val mode=intent.getStringExtra("profile")?:"zd8-mt"
                    require(mode in Modes.ids){"未知采集模式"}
                    StudioState.reset(mode);StudioState.message("正在连接 ELM327…")
                    val extras=intent.getStringArrayListExtra("extra").orEmpty()
                    require(extras.all{it.matches(Regex("(01[0-9A-F]{2}|21[0-9A-F]{2}|22[0-9A-F]{4})"))}){"额外命令只允许只读 PID"}
                    require(!Modes.gauge(mode)||extras.isEmpty()){"仪表同款模式不插入额外命令"}
                    val duration=intent.getDoubleExtra("duration",0.0);val interval=intent.getLongExtra("interval",30)
                    require(duration.isFinite()&&duration>=0&&interval>=0)
                    val folder=File(filesDir,"recordings").apply{mkdirs()}
                    val file=File(folder,LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))+".jsonl")
                    check(file.createNewFile()){"文件名冲突"}
                    journal=Journal(file,mode,intent.getStringExtra("address")?:error("未选择 ELM"))
                    try {BleCapture(this,journal){cancelled}.use {it.connect(journal.address);it.run(mode,extras,duration,interval)}}
                    catch(e:Exception){journal.emit("stop",mapOf("reason" to e.javaClass.simpleName,"detail" to e.message));throw e}
                }
            }catch(e:InterruptedException){StudioState.message("已停止，正在保存…")}
            catch(e:Exception){StudioState.message("错误：${e.message?:e.javaClass.simpleName}")}
            finally {
                Thread.interrupted()
                try {
                journal?.let{j->
                    runCatching {
                        j.close();StudioState.savedFile=j.file.absolutePath;val summary=File(j.file.parentFile,j.file.nameWithoutExtension+".summary.json")
                        summary.writeText(json(mapOf("schema" to 1,"profile" to j.profile,"coverage" to if(j.profile=="all")"last_sample_hold"else "valid_samples_only")+StudioState.trip.snapshot()).toString(2))
                        StudioState.message("已保存：${j.file.name}")
                        intent.getStringExtra("tree")?.let{tree->
                            val root=Uri.parse(tree);val parent=DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root))
                            for(f in listOf(j.file,summary)){
                                val dest=DocumentsContract.createDocument(contentResolver,parent,"application/json",f.name)?:error("无法创建导出文件")
                                contentResolver.openOutputStream(dest)?.use{out->f.inputStream().use{it.copyTo(out)}}?:error("无法写入导出文件")
                            };StudioState.message("已导出录制与统计摘要")
                        }
                    }.onFailure{StudioState.message("保存/导出异常：${it.message}；请在文件列表检查本地副本")}
                }
                } finally {
                StudioState.active=false;wakeHandler.removeCallbacks(renewWake);wake?.let{if(it.isHeld)it.release()};wake=null;worker=null
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
                }
            }
        },"OBDStudio-worker").also{it.start()}
        return START_NOT_STICKY
    }
    override fun onDestroy(){cancelled=true;worker?.interrupt();super.onDestroy()}
}
