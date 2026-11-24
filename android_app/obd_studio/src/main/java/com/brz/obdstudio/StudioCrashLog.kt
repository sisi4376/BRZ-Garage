package com.brz.obdstudio

import java.io.File

/** Local evidence for an actual uncaught exception; never uploads anything. */
object StudioCrashLog {
    private var installed=false
    @Synchronized fun install(folder:File,metadata:Map<String,Any?>){
        if(installed)return
        val previous=Thread.getDefaultUncaughtExceptionHandler()?:return
        Thread.setDefaultUncaughtExceptionHandler { thread,error->
            try {
                check(folder.mkdirs()||folder.isDirectory)
                Journal(File(folder,"last-app-crash.jsonl"),"diagnostic","").use{journal->
                    journal.emit("app_crash",metadata+mapOf("thread" to thread.name,"exception" to error.javaClass.name,"stack" to error.stackTraceToString().take(24000)))
                }
            }catch(_:Throwable){} // Preserve Android's original crash handling even if storage fails.
            previous.uncaughtException(thread,error)
        }
        installed=true
    }
}
