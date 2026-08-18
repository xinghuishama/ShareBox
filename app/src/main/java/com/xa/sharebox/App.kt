package com.xa.sharebox

import android.app.Application
import android.content.Context
import android.util.Log

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        app = this

        // Global crash handler — log only, don't interfere with normal crash reporting
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("ShareBox", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        lateinit var app: App
            private set

        /** App-private files directory for log files. */
        val logDir: java.io.File
            get() = app.filesDir
    }
}
