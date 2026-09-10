package com.example.flowave.utils

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.flowave.diagnostics.FloWaveLogger

object FloWaveCrashHandler : Thread.UncaughtExceptionHandler {

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null
    private const val FILE_NAME = "crash_report.txt"

    fun init(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            saveCrashReport(throwable)
            appContext?.let {
                FloWaveLogger.getInstance(it).error(
                    "crash",
                    "uncaught_exception",
                    context = mapOf("thread" to thread.name),
                    throwable = throwable
                )
            }
        } catch (e: Exception) {
            Log.e("FloWaveCrashHandler", "Failed to save crash report", e)
        }

        // Forward to default system handler so the app can terminate/crash cleanly
        defaultHandler?.uncaughtException(thread, throwable)
    }

    private fun saveCrashReport(throwable: Throwable) {
        val context = appContext ?: return
        val reportFile = File(context.filesDir, FILE_NAME)

        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val dateString = sdf.format(Date())

        val appVersion = try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "Unknown"
        }

        val report = """
            ========================================
            FLOWAVE CRASH REPORT
            ========================================
            Date: $dateString
            App Version: $appVersion
            Package Name: ${context.packageName}
            Device Manufacturer: ${Build.MANUFACTURER}
            Device Model: ${Build.MODEL}
            OS Version: Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})
            Device Product: ${Build.PRODUCT}
            ========================================
            EXCEPTION STACK TRACE:
            $stackTrace
            ========================================
        """.trimIndent()

        reportFile.writeText(report)
        Log.e("FloWaveCrashHandler", "Crash report written to ${reportFile.absolutePath}")
    }

    fun hasCrashReport(context: Context): Boolean {
        return File(context.filesDir, FILE_NAME).exists()
    }

    fun getCrashReport(context: Context): String? {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.exists()) {
            try {
                file.readText()
            } catch (e: Exception) {
                Log.e("FloWaveCrashHandler", "Failed to read crash report", e)
                null
            }
        } else {
            null
        }
    }

    fun clearCrashReport(context: Context): Boolean {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.exists()) {
            file.delete()
        } else {
            false
        }
    }
}
