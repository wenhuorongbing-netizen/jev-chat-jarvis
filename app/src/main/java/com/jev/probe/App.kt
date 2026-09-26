package com.jev.probe

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Custom Application: installs a crash-capture handler that records the last
 * uncaught exception to filesDir/crash_last.log (previous one rotated to
 * crash_prev.log, keeping at most two files), then hands the throwable back
 * to the system default handler so the normal crash flow continues.
 *
 * Privacy: only exception class name, message and stack trace are written.
 * Stack traces may contain class names / line numbers (allowed). No chat
 * content, no keys, no user text is ever written here.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val systemHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeCrashLog(thread, throwable) }
            // 不吞异常：交还系统默认 handler 继续标准崩溃流程（弹窗/杀死进程）。
            systemHandler?.uncaughtException(thread, throwable)
                ?: throw throwable
        }
    }

    private fun writeCrashLog(thread: Thread, throwable: Throwable) {
        val last = File(filesDir, CRASH_LAST)
        if (last.exists()) {
            // 轮换：最多保留两份（last + prev）。
            val prev = File(filesDir, CRASH_PREV)
            if (prev.exists()) prev.delete()
            last.renameTo(prev)
        }
        val trace = StringWriter().also { sw ->
            throwable.printStackTrace(PrintWriter(sw))
        }.toString()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        last.writeText(buildString {
            append("time=").append(stamp).append('\n')
            append("thread=").append(thread.name).append('\n')
            append("exception=").append(throwable.javaClass.name).append('\n')
            append("message=").append(throwable.message ?: "").append('\n')
            append("--- stack ---\n")
            append(trace)
        })
    }

    companion object {
        const val CRASH_LAST = "crash_last.log"
        const val CRASH_PREV = "crash_prev.log"
    }
}
