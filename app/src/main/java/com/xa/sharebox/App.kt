package com.xa.sharebox

import android.app.Application
import android.util.Log
import android.widget.Toast

class App : Application() {
    override fun onCreate() {
        super.onCreate()

        // Global crash handler - catches unexpected errors and shows a toast
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("ShareBox", "Uncaught exception on ${thread.name}", throwable)
            // Show toast on main thread
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                Toast.makeText(this, "错误: ${throwable.javaClass.simpleName}: ${throwable.message}", Toast.LENGTH_LONG).show()
            }
            // Give the toast time to show before crashing
            Thread.sleep(500)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
