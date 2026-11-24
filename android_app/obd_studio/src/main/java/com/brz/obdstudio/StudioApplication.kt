package com.brz.obdstudio

import android.app.Application
import android.os.Build
import java.io.File

class StudioApplication:Application(){
    override fun onCreate(){
        super.onCreate()
        val folder=File(filesDir,"recordings")
        @Suppress("DEPRECATION") val version=packageManager.getPackageInfo(packageName,0).versionName
        StudioCrashLog.install(folder,mapOf("version" to version,"api" to Build.VERSION.SDK_INT,"phone" to "${Build.MANUFACTURER} ${Build.MODEL}"))
        StudioState.message("OBD Studio $version · Android API ${Build.VERSION.SDK_INT}")
        if(File(folder,"last-app-crash.jsonl").isFile)StudioState.message("检测到上次应用崩溃日志，可在文件页导出 last-app-crash.jsonl")
    }
}
