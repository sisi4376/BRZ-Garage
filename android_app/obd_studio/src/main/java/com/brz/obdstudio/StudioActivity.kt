@file:Suppress("DEPRECATION")
package com.brz.obdstudio

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.view.*
import android.widget.*
import java.io.File
import java.util.Locale

@SuppressLint("MissingPermission")
class StudioActivity:Activity(){
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var root:LinearLayout
    private lateinit var content:LinearLayout
    private lateinit var status:TextView
    private lateinit var stats:TextView
    private lateinit var start:Button
    private lateinit var stop:Button
    private var page=0
    private var pending:(()->Unit)?=null
    private val devices=linkedMapOf<String,Pair<String,Int>>()
    private var scanning=false
    private var scanCallback:ScanCallback?=null
    private var address="";private var manualAddress="";private var modeIndex=0;private var extra="";private var duration="0";private var interval="30"
    private var speedIndex=2;private var looping=false;private var selectedFile="";private var exportSummary=false
    private var seenRecording=StudioState.savedFile
    private var fileList=listOf<File>()
    private val shownValues=mutableMapOf<String,TextView>()
    private var raw:TextView?=null;private var notes:TextView?=null;private var trend:TrendView?=null;private var logs:TextView?=null
    private val cyan=Color.rgb(80,222,194)
    private val tick=object:Runnable{override fun run(){refresh();handler.postDelayed(this,500)}}
    override fun onCreate(state:Bundle?){super.onCreate(state);window.statusBarColor=Color.rgb(12,20,34);window.navigationBarColor=Color.rgb(12,20,34)
        root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(12,20,34));setPadding(dp(14),dp(12),dp(14),dp(12))};setContentView(root)
        root.setOnApplyWindowInsetsListener{v,insets->
            if(Build.VERSION.SDK_INT>=30){val i=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime());v.setPadding(i.left+dp(14),i.top+dp(8),i.right+dp(14),i.bottom+dp(8))}
            else v.setPadding(insets.systemWindowInsetLeft+dp(14),insets.systemWindowInsetTop+dp(8),insets.systemWindowInsetRight+dp(14),insets.systemWindowInsetBottom+dp(8));insets}
        root.addView(text("OBD STUDIO",24).apply{setTextColor(cyan)})
        status=text("就绪",14);root.addView(status)
        stats=text("",12);root.addView(stats)
        val tabs=LinearLayout(this)
        listOf("采集","回放","实时","文件").forEachIndexed{i,s->tabs.addView(button(s){page=i;render()},LinearLayout.LayoutParams(0,dp(46),1f))};root.addView(tabs)
        val scroll=ScrollView(this).apply{isFillViewport=true};content=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};scroll.addView(content);root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val actions=LinearLayout(this);start=button("开始录制"){permissions {if(page==1)beginReplay()else beginRecord()}};stop=button("停止并保存"){stopScan();startService(Intent(this,StudioService::class.java).setAction("STOP"))}
        actions.addView(start,LinearLayout.LayoutParams(0,dp(52),1f));actions.addView(stop,LinearLayout.LayoutParams(0,dp(52),1f));root.addView(actions)
        render();handler.post(tick)
    }
    private fun dp(n:Int)=(n*resources.displayMetrics.density).toInt()
    private fun text(s:String,size:Int=14)=TextView(this).apply{text=s;textSize=size.toFloat();setTextColor(Color.rgb(224,235,248));setPadding(dp(5),dp(7),dp(5),dp(7))}
    private fun button(s:String,action:()->Unit)=Button(this).apply{text=s;isAllCaps=false;textSize=13f;setTextColor(cyan);setOnClickListener{runCatching(action).onFailure{StudioState.message("错误：${it.message}")}}}
    private fun field(label:String,value:String,onEdit:(String)->Unit):EditText{
        content.addView(text(label));return EditText(this).apply{setText(value);setSingleLine();setTextColor(Color.WHITE);setHintTextColor(Color.GRAY)
            addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,a:Int,c:Int,f:Int){};override fun onTextChanged(s:CharSequence?,a:Int,b:Int,c:Int){onEdit(s.toString())};override fun afterTextChanged(s:android.text.Editable?){} })
            content.addView(this,LinearLayout.LayoutParams(-1,dp(52)))}
    }
    private fun spinner(label:String,labels:List<String>,index:Int,select:(Int)->Unit):Spinner{
        content.addView(text(label));val s=Spinner(this);s.adapter=ArrayAdapter(this,android.R.layout.simple_spinner_dropdown_item,labels)
        s.setSelection(index.coerceIn(0,maxOf(0,labels.size-1)));s.onItemSelectedListener=object:AdapterView.OnItemSelectedListener{override fun onNothingSelected(p:AdapterView<*>?){};override fun onItemSelected(p:AdapterView<*>?,v:View?,i:Int,id:Long){select(i)}}
        content.addView(s,LinearLayout.LayoutParams(-1,dp(54)));return s
    }
    private fun files():List<File> = listOf(File(filesDir,"recordings"),File(filesDir,"imports")).flatMap{it.listFiles()?.toList().orEmpty()}.filter{it.extension=="jsonl"}.sortedByDescending{it.lastModified()}
    private fun fileChooser(){
        fileList=files();val index=fileList.indexOfFirst{it.absolutePath==selectedFile}.coerceAtLeast(0)
        if(fileList.isNotEmpty())selectedFile=fileList[index].absolutePath
        spinner("录制文件",fileList.map{it.name}.ifEmpty{listOf("请先录制或导入 JSONL")},index){i->fileList.getOrNull(i)?.let{selectedFile=it.absolutePath}}
    }
    private fun render(){
        content.removeAllViews();shownValues.clear();raw=null;trend=null;notes=null;logs=null
        start.text=if(page==1)"启动仪表回放"else "开始录制"
        when(page){
            0->{
                content.addView(button(if(scanning)"停止扫描"else "扫描 BLE ELM327"){if(scanning)stopScan()else permissions{scan()}})
                val list=devices.entries.sortedByDescending{it.value.second}
                spinner("附近设备",list.map{"${it.value.first} · ${it.key} · ${it.value.second} dBm"}.ifEmpty{listOf("请扫描，或手动输入地址")},list.indexOfFirst{it.key==address}.coerceAtLeast(0)){i->list.getOrNull(i)?.let{address=it.key}}
                field("手动蓝牙地址（填写后优先于扫描选择）",manualAddress){manualAddress=it}
                spinner("采集范围",Modes.labels,modeIndex){modeIndex=it}
                field("额外只读请求（非仪表模式，可用逗号分隔，如 221017,2101）",extra){extra=it}
                field("采集时长 / 秒（0 = 手动停止）",duration){duration=it}
                field("普通轮询请求间隔 / 毫秒（仪表模式固定 1 ms 时隙间隔）",interval){interval=it}
                content.addView(button("选择自动导出文件夹"){if(!StudioState.active)startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),12)})
                content.addView(text("默认保存在应用本地；选定导出文件夹后，停止时自动复制 JSONL 和摘要。\n${getPreferences(0).getString("tree",null)?:"尚未选择自动导出位置"}"))
                content.addView(text("采集前请让手机车库 App 和仪表断开 ELM。全量模式保留最近值，累计结果包含采样间隔保持值估算。"))
            }
            1->{fileChooser();content.addView(button("导入电脑版 / 手机版 JSONL"){pickImport()})
                spinner("回放速度",listOf("0.25 ×","0.5 ×","1 ×","2 ×","4 ×"),speedIndex){speedIndex=it}
                content.addView(CheckBox(this).apply{text="循环回放";isChecked=looping;setOnCheckedChangeListener{_,b->looping=b}})
                content.addView(text("启动后，在仪表 BLE SCAN 页面选择此手机的 FFF0 服务。时间轴从首个实时请求开始；每个仪表独立计时，重连从头开始，ATZ 不重置时间轴。\n手机必须支持 BLE 外设广播。未录到的数据返回 NO DATA。"))
            }
            2->{
                val grid=GridLayout(this).apply{columnCount=if(resources.configuration.screenWidthDp>=720)4 else 2}
                val names=linkedMapOf("010C" to "转速 · rpm","010D" to "车速 · km/h","0105" to "水温 · °C","015C" to "油温 · °C","instant" to "瞬时油耗","average_l100km" to "采样平均 · L/100km","distance_km" to "本次里程 · km","fuel_l" to "本次耗油 · L","fuel_rate_lph" to "燃油流量 · L/h","012F" to "燃油余量 · %","01A6" to "ECU 总里程 · km","0142" to "ECU 电压 · V")
                names.entries.forEachIndexed{i,(key,label)->
                    val card=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(8));background=GradientDrawable().apply{setColor(Color.rgb(19,31,48));cornerRadius=dp(10).toFloat()}}
                    card.addView(text(label,12));val v=text("—",24).apply{setTextColor(cyan)};shownValues[key]=v;card.addView(v)
                    grid.addView(card,GridLayout.LayoutParams(GridLayout.spec(i/grid.columnCount),GridLayout.spec(i%grid.columnCount,1f)).apply{width=0;setMargins(dp(3),dp(3),dp(3),dp(3))})
                };content.addView(grid)
                notes=text("等待数据",12);content.addView(notes)
                content.addView(text("转速曲线 · 最近 60 秒"));trend=TrendView(this);content.addView(trend,LinearLayout.LayoutParams(-1,dp(180)))
                content.addView(text("原始响应 · 每个请求的最新结果与更新时间"));raw=text("等待数据",12).apply{setTextIsSelectable(true);typeface=Typeface.MONOSPACE};content.addView(raw)
            }
            3->{fileChooser();content.addView(button("导入 JSONL"){pickImport()});content.addView(button("导出所选原始录制"){export(false)});content.addView(button("导出所选统计摘要"){export(true)})
                content.addView(text("电脑版和安卓版 JSONL 可相互导入回放。导出的原始文件不会修改；摘要与它同名，后缀为 .summary.json。卸载应用会删除未导出的本地文件。"))
            }
        }
        content.addView(text("运行日志",13));logs=text("",12).apply{setTextIsSelectable(true)};content.addView(logs)
        refresh()
    }
    private fun refresh(){
        if(StudioState.savedFile!=seenRecording){seenRecording=StudioState.savedFile;selectedFile=seenRecording}
        val s=StudioState.snapshot();status.text=s.status
        val seconds=if(s.active)(System.currentTimeMillis()-StudioState.startWall).coerceAtLeast(0)/1000 else 0
        stats.text="${seconds/3600}:${(seconds/60%60).toString().padStart(2,'0')}:${(seconds%60).toString().padStart(2,'0')} · ${s.count} 条响应 · %.1f KB".format(s.bytes/1024.0)
        start.isEnabled=!s.active;stop.isEnabled=s.active||scanning
        logs?.text=s.log.takeLast(12).joinToString("\n")
        val latest=s.rows.associateBy{if(it.command=="2101")"015C"else it.command};val now=System.currentTimeMillis()
        shownValues.forEach{(key,v)->
            val row=latest[key]
            val number=if(row!=null)row.decoded?.value?.takeIf{s.full||now-row.wall<=15000}else s.metrics[key] as? Double
            v.text=number?.let{String.format(Locale.US,if(key=="fuel_l")"%.3f"else if(key=="distance_km")"%.2f"else if(key in listOf("010C","010D","0105","015C"))"%.0f"else "%.1f",it)}?:"—"
            if(key=="instant"&&number!=null)v.append("\n"+s.metrics["instant_unit"])
        }
        val age=if(s.rows.isEmpty())0 else (now-s.rows.maxOf{it.wall})/1000
        if(!s.full&&age>5){shownValues["instant"]?.text="—";shownValues["fuel_rate_lph"]?.text="—"}
        notes?.text="燃油来源：${s.metrics["fuel_source"]} · ${if(s.full)"全量保留最近值，无过期限制"else "仅累计有效采样时段"}\n里程覆盖 ${s.metrics["distance_seconds"]?.let{"%.0f".format(it)}} s / 燃油覆盖 ${s.metrics["fuel_seconds"]?.let{"%.0f".format(it)}} s"
        raw?.text=s.rows.joinToString("\n\n"){r->"${r.command}  ${r.decoded?.let{"${it.name} %.2f ${it.unit}".format(it.value)}?:"原始数据"}  · ${(now-r.wall)/1000}s 前\n${r.text.replace('\r',' ')}"}
        trend?.points=s.trend;trend?.invalidate()
    }
    private fun permissions(action:()->Unit){
        val needed=if(Build.VERSION.SDK_INT>=31)mutableListOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT,Manifest.permission.BLUETOOTH_ADVERTISE)else mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if(Build.VERSION.SDK_INT>=33)needed+=Manifest.permission.POST_NOTIFICATIONS
        val missing=needed.filter{checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED}
        if(missing.isEmpty())action()else{pending=action;requestPermissions(missing.toTypedArray(),1)}
    }
    override fun onRequestPermissionsResult(code:Int,permissions:Array<out String>,grants:IntArray){super.onRequestPermissionsResult(code,permissions,grants)
        val required=permissions.indices.filter{permissions[it]!="android.permission.POST_NOTIFICATIONS"}
        if(required.all{grants.getOrNull(it)==PackageManager.PERMISSION_GRANTED})pending?.invoke()else StudioState.message("需要蓝牙/扫描权限，请授权后重试");pending=null}
    private fun scan(){
        if(StudioState.active)return
        val adapter=getSystemService(BluetoothManager::class.java).adapter?:error("手机没有蓝牙");check(adapter.isEnabled){"请先开启蓝牙"}
        devices.clear();scanning=true;StudioState.message("正在扫描 BLE 设备，约 8 秒")
        val cb=object:ScanCallback(){override fun onScanResult(type:Int,result:ScanResult){runOnUiThread{devices[result.device.address]=(result.scanRecord?.deviceName?:result.device.name?:"未命名设备") to result.rssi}}
            override fun onScanFailed(errorCode:Int){runOnUiThread{stopScan();StudioState.message("扫描失败：$errorCode")}}}
        val scanner=adapter.bluetoothLeScanner?:error("蓝牙扫描不可用")
        scanCallback=cb;scanner.startScan(cb);handler.postDelayed({if(scanCallback===cb){stopScan();render()}},8000)
    }
    private fun stopScan(){scanCallback?.let{runCatching{getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner?.stopScan(it)}};scanCallback=null;if(scanning)StudioState.message("扫描完成 · ${devices.size} 个设备");scanning=false}
    private fun beginRecord(){
        val selectedAddress=manualAddress.trim().ifEmpty{address}.uppercase()
        check(BluetoothAdapter.checkBluetoothAddress(selectedAddress)){"请选择 ELM 或输入 AA:BB:CC:DD:EE:FF 地址"}
        val mode=Modes.ids[modeIndex];val extras=extra.split(',', '，',';','；').map{norm(it.trim())}.filter{it.isNotEmpty()}
        check(extras.all{it.matches(Regex("(01[0-9A-F]{2}|21[0-9A-F]{2}|22[0-9A-F]{4})"))}){"额外请求只允许 01xx / 21xx / 22xxxx"}
        check(!Modes.gauge(mode)||extras.isEmpty()){"仪表同款模式不插入额外请求"}
        val d=duration.toDouble();val gap=interval.toLong();check(d.isFinite()&&d>=0&&gap>=0){"时长和间隔必须非负"}
        stopScan();val intent=Intent(this,StudioService::class.java).setAction("RECORD").putExtra("address",selectedAddress).putExtra("profile",mode).putStringArrayListExtra("extra",ArrayList(extras)).putExtra("duration",d).putExtra("interval",gap)
        getPreferences(0).getString("tree",null)?.let{intent.putExtra("tree",it)};startForegroundService(intent);page=2;render()
    }
    private fun beginReplay(){check(File(selectedFile).isFile){"请选择或导入录制文件"};stopScan()
        startForegroundService(Intent(this,StudioService::class.java).setAction("REPLAY").putExtra("file",selectedFile).putExtra("speed",listOf(.25,.5,1.0,2.0,4.0)[speedIndex]).putExtra("loop",looping));page=2;render()}
    private fun pickImport(){if(StudioState.active)return;startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),11)}
    private fun export(summary:Boolean){check(File(selectedFile).isFile){"请选择文件"};exportSummary=summary
        val source=if(summary)File(File(selectedFile).parentFile,File(selectedFile).nameWithoutExtension+".summary.json")else File(selectedFile)
        check(source.exists()){"该文件没有本地统计摘要"};startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").putExtra(Intent.EXTRA_TITLE,source.name),13)}
    override fun onActivityResult(request:Int,result:Int,data:Intent?){super.onActivityResult(request,result,data);if(result!=RESULT_OK)return;val uri=data?.data?:return
        when(request){
            12->{runCatching{contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);getPreferences(0).edit().putString("tree",uri.toString()).apply();render()}.onFailure{StudioState.message("无法保留文件夹访问权限：${it.message}")}}
            11->{StudioState.message("正在导入…");Thread{
                var file:File?=null
                runCatching{
                    val name=contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use{c->if(c.moveToFirst())c.getString(0)else "recording.jsonl"}?:"recording.jsonl"
                    val folder=File(filesDir,"imports").apply{mkdirs()};file=File(folder,"${System.currentTimeMillis()}-"+name.replace(Regex("[^a-zA-Z0-9._-]"),"_").removeSuffix(".jsonl")+".jsonl")
                    contentResolver.openInputStream(uri)?.use{input->file!!.outputStream().use{input.copyTo(it)}}?:error("无法读取文件")
                    loadTape(file!!);selectedFile=file!!.absolutePath;StudioState.message("已导入：${file!!.name}")
                }.onFailure{file?.delete();StudioState.message("导入失败：${it.message}")}
                runOnUiThread{if(!isDestroyed)render()}
            }.start()}
            13->{val path=selectedFile;val summary=exportSummary;Thread{runCatching{
                val f=File(path);val source=if(summary)File(f.parentFile,f.nameWithoutExtension+".summary.json")else f
                contentResolver.openOutputStream(uri,"wt")?.use{out->source.inputStream().use{it.copyTo(out)}}?:error("无法导出")
                StudioState.message("导出完成：${source.name}")
            }.onFailure{StudioState.message("导出失败：${it.message}")}}.start()}
        }
    }
    override fun onDestroy(){handler.removeCallbacksAndMessages(null);stopScan();super.onDestroy()}
    override fun onConfigurationChanged(newConfig:android.content.res.Configuration){super.onConfigurationChanged(newConfig);render()}
}
class TrendView(context:Context):View(context){
    var points=listOf<Pair<Long,Double>>()
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas){super.onDraw(canvas);val pad=42f;val bottom=height-20f;val now=System.currentTimeMillis()
        paint.textSize=26f;paint.strokeWidth=1f
        for(i in 0..4){val y=10+(bottom-10)*i/4;paint.color=Color.rgb(36,53,75);canvas.drawLine(pad,y,width.toFloat(),y,paint);paint.color=Color.GRAY;canvas.drawText((8000-i*2000).toString(),0f,y+8,paint)}
        val path=Path();var first=true
        points.filter{now-it.first<=60000}.forEach{(t,rpm)->val x=pad+(width-pad)*(1-(now-t)/60000f);val y=10+(bottom-10)*(1-(rpm/8000).coerceIn(0.0,1.0)).toFloat();if(first){path.moveTo(x,y);first=false}else path.lineTo(x,y)}
        paint.style=Paint.Style.STROKE;paint.color=Color.rgb(80,222,194);paint.strokeWidth=3f;canvas.drawPath(path,paint);paint.style=Paint.Style.FILL
    }
}
