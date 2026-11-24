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
    private val handler=Handler(Looper.getMainLooper())
    private val renewWake=object:Runnable{override fun run(){if(StudioState.active){wake?.acquire(600000L);handler.postDelayed(this,300000L)}}}
    private val progress=object:Runnable{override fun run(){if(StudioState.active){notifyProgress();handler.postDelayed(this,1000L)}}}
    override fun onBind(intent:Intent?)=null
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,1,Intent(this,StudioActivity::class.java),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,2,Intent(this,StudioService::class.java).setAction("STOP"),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val elapsed=if(StudioState.pollingStartedNs>0)(System.nanoTime()-StudioState.pollingStartedNs)/1_000_000_000L else -1L
        val detail=if(StudioState.quickStage>0) "${StudioState.quickStage}/2 · ${StudioState.quickLabel} · "+
            if(elapsed<0)"准备中"else "${elapsed.coerceAtMost(300)/60}:${(elapsed.coerceAtMost(300)%60).toString().padStart(2,'0')} / 5:00"
            else "点击打开；使用停止按钮保存并断开蓝牙"
        return Notification.Builder(this,"obd-studio").setSmallIcon(R.drawable.studio_icon).setContentTitle("OBD Studio 正在运行")
            .setContentText(detail).setOnlyAlertOnce(true).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null,"停止并保存",stop).build()).build()
    }
    private fun notifyProgress(){runCatching{getSystemService(NotificationManager::class.java).notify(31,notification())}}
    override fun onStartCommand(intent:Intent?,flags:Int,id:Int):Int {
        if(intent?.action=="STOP"){cancelled=true;worker?.interrupt();if(worker==null)stopSelf();return START_NOT_STICKY}
        if(intent==null){stopSelf();return START_NOT_STICKY}
        if(StudioState.active)return START_NOT_STICKY
        cancelled=false;StudioState.startWall=System.currentTimeMillis();StudioState.active=true
        StudioState.quickStage=0;StudioState.quickLabel="";StudioState.pollingStartedNs=0
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("obd-studio","OBD 采集与回放",NotificationManager.IMPORTANCE_LOW))
        startForeground(31,notification())
        wake=getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"OBDStudio:recording").apply{setReferenceCounted(false);acquire(600000L)}
        handler.postDelayed(renewWake,300000L);handler.post(progress)
        worker=Thread({
            try {
                val job=LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
                when(intent.action){
                    "REPLAY"->{
                        val tape=loadTape(File(intent.getStringExtra("file")?:error("未选择文件")))
                        StudioState.reset(tape.profile);StudioState.message("录制：%.1f 秒，%d 种请求".format(tape.duration,tape.samples.size))
                        BleReplay(this,tape,intent.getDoubleExtra("speed",1.0),intent.getBooleanExtra("loop",false)){cancelled}.use{it.run()}
                    }
                    "QUICK_RECORD"->{
                        val model=intent.getStringExtra("profile")?:error("未选择车型")
                        quickCapturePlan(model)
                        StudioState.reset("all")
                        val name=intent.getStringExtra("obd_name")?.trim().orEmpty()
                        val address=intent.getStringExtra("address").orEmpty()
                        require(android.bluetooth.BluetoothAdapter.checkBluetoothAddress(address)){"请先扫描并选择 OBD"}
                        runQuickCapture(model,{cancelled}){index,stage->
                            StudioState.quickStage=index;StudioState.quickLabel=stage.label;StudioState.pollingStartedNs=0
                            if(index>1){StudioState.message("第 1 段已保存，正在切换车型轮询…");Thread.sleep(1000)}
                            record(address,stage.profile,emptyList(),stage.seconds,30L,intent,job,index,name)
                        }
                        StudioState.message("一键采集完成 · 两段各 5 分钟，录制和摘要已分别保存")
                    }
                    "RECORD"->record(intent.getStringExtra("address")?:error("未选择 ELM"),intent.getStringExtra("profile")?:"zd8-mt",
                        intent.getStringArrayListExtra("extra").orEmpty(),intent.getDoubleExtra("duration",0.0),intent.getLongExtra("interval",30L),intent,job,0,"")
                    else->error("未知任务")
                }
            }catch(e:InterruptedException){StudioState.message("已停止 · 已采集内容保存在文件列表，可分别导出")}
            catch(e:Exception){StudioState.message("错误：${e.message?:e.javaClass.simpleName}；已有录制保留在文件列表")}
            finally {
                Thread.interrupted();StudioState.active=false;StudioState.quickStage=0;StudioState.pollingStartedNs=0
                handler.removeCallbacks(renewWake);handler.removeCallbacks(progress);wake?.let{if(it.isHeld)it.release()};wake=null;worker=null
                stopForeground(STOP_FOREGROUND_REMOVE);stopSelf()
            }
        },"OBDStudio-worker").also{it.start()}
        return START_NOT_STICKY
    }
    private fun record(address:String,mode:String,extras:List<String>,duration:Double,interval:Long,intent:Intent,job:String,index:Int,name:String){
        require(mode in Modes.ids){"未知采集模式"}
        require(extras.all{it.matches(Regex("(01[0-9A-F]{2}|21[0-9A-F]{2}|22[0-9A-F]{4})"))}){"额外命令只允许只读 PID"}
        require(!Modes.gauge(mode)||extras.isEmpty()){"仪表同款模式不插入额外命令"}
        require(duration.isFinite()&&duration>=0&&interval>=0)
        if(cancelled||Thread.currentThread().isInterrupted)throw InterruptedException()
        StudioState.reset(mode);StudioState.pollingStartedNs=0;StudioState.message("正在连接 ELM327 · ${if(index>0)"第 $index/2 段"else mode}")
        val folder=File(filesDir,"recordings").apply{check(mkdirs()||isDirectory){"无法创建录制目录"}}
        val file=File(folder,"${job}_${index}_${mode}.jsonl");check(file.createNewFile()){"文件名冲突"}
        val journal=Journal(file,mode,address);var completed=false;var failure:Exception?=null
        journal.emit("capture_stage",mapOf("job" to job,"stage" to index,"obd_name" to name,"vehicle" to intent.getStringExtra("profile"),"seconds" to duration))
        try {
            BleCapture(this,journal){cancelled}.use{capture->capture.connect(address);capture.run(mode,extras,duration,interval,index>0){
                StudioState.pollingStartedNs=System.nanoTime();journal.emit("polling_started",mapOf("seconds" to duration))
            }}
            completed=true
        }catch(e:Exception){failure=e;runCatching{journal.emit("stop",mapOf("reason" to e.javaClass.simpleName,"detail" to e.message,"stack" to e.stackTraceToString().take(24000)))};throw e}
        finally {
            Thread.interrupted()
            try {save(journal,intent,job,index,completed)}
            catch(e:Exception){if(failure==null)throw e else StudioState.message("保存异常：${e.message}；请检查本地录制")}
        }
    }
    private fun save(journal:Journal,intent:Intent,job:String,index:Int,completed:Boolean){
        journal.emit("capture_stage_end",mapOf("completed" to completed));journal.close();StudioState.savedFile=journal.file.absolutePath
        val summary=File(journal.file.parentFile,journal.file.nameWithoutExtension+".summary.json")
        summary.writeText(json(mapOf("schema" to 1,"profile" to journal.profile,"vehicle" to intent.getStringExtra("profile"),"job" to job,"stage" to index,"completed" to completed,
            "coverage" to if(journal.profile=="all")"last_sample_hold"else "valid_samples_only")+StudioState.trip.snapshot()).toString(2))
        StudioState.message("已保存：${journal.file.name}${if(completed)""else "（未完成片段）"}")
        intent.getStringExtra("tree")?.let{tree->runCatching{
            val root=Uri.parse(tree);val parent=DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root))
            for(file in listOf(journal.file,summary)){
                val dest=DocumentsContract.createDocument(contentResolver,parent,"application/json",file.name)?:error("无法创建导出文件")
                contentResolver.openOutputStream(dest)?.use{out->file.inputStream().use{it.copyTo(out)}}?:error("无法写入导出文件")
            }
        }.onFailure{StudioState.message("自动导出失败：${it.message}；本地录制和摘要已保留")}}
    }
    override fun onDestroy(){cancelled=true;worker?.interrupt();super.onDestroy()}
}
