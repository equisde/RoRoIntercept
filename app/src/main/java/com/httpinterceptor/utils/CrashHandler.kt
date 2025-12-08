package com.httpinterceptor.utils

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlin.system.exitProcess

class CrashHandler private constructor(private val context: Context) : Thread.UncaughtExceptionHandler {
    
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    
    companion object {
        private const val TAG = "CrashHandler"
        
        fun init(context: Context) {
            val crashHandler = CrashHandler(context.applicationContext)
            Thread.setDefaultUncaughtExceptionHandler(crashHandler)
        }
    }
    
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            val crashLog = generateCrashLog(thread, throwable)
            saveToCrashFile(crashLog)
            copyToClipboard(crashLog)
            Log.e(TAG, "App crashed. Log copied to clipboard:\n$crashLog")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling crash", e)
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
            exitProcess(1)
        }
    }
    
    private fun generateCrashLog(thread: Thread, throwable: Throwable): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val stackTrace = getStackTrace(throwable)
        
        return buildString {
            appendLine("═══════════════════════════════════════════")
            appendLine("RoRo Interceptor - Crash Report")
            appendLine("═══════════════════════════════════════════")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("───────────────────────────────────────────")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message ?: "No message"}")
            appendLine("───────────────────────────────────────────")
            appendLine("Stack Trace:")
            appendLine(stackTrace)
            appendLine("═══════════════════════════════════════════")
            
            // Add device info
            appendLine("\nDevice Information:")
            appendLine("Android Version: ${android.os.Build.VERSION.RELEASE}")
            appendLine("SDK: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Brand: ${android.os.Build.BRAND}")
            appendLine("Model: ${android.os.Build.MODEL}")
            appendLine("Manufacturer: ${android.os.Build.MANUFACTURER}")
            
            // Add app info
            try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                appendLine("\nApp Information:")
                appendLine("Version: ${packageInfo.versionName}")
                appendLine("Version Code: ${packageInfo.versionCode}")
                appendLine("Package: ${context.packageName}")
            } catch (e: Exception) {
                appendLine("\nApp Information: Error getting package info")
            }
            
            // Add memory info
            appendLine("\nMemory Information:")
            val runtime = Runtime.getRuntime()
            val maxMemory = runtime.maxMemory() / 1024 / 1024
            val totalMemory = runtime.totalMemory() / 1024 / 1024
            val freeMemory = runtime.freeMemory() / 1024 / 1024
            val usedMemory = totalMemory - freeMemory
            appendLine("Max Memory: ${maxMemory}MB")
            appendLine("Total Memory: ${totalMemory}MB")
            appendLine("Used Memory: ${usedMemory}MB")
            appendLine("Free Memory: ${freeMemory}MB")
            
            appendLine("═══════════════════════════════════════════")
        }
    }
    
    private fun getStackTrace(throwable: Throwable): String {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        
        // Also include cause chain
        var cause = throwable.cause
        while (cause != null) {
            pw.println("\nCaused by:")
            cause.printStackTrace(pw)
            cause = cause.cause
        }
        
        return sw.toString()
    }
    
    private fun copyToClipboard(crashLog: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("RoRo Interceptor Crash Log", crashLog)
            clipboard.setPrimaryClip(clip)
            Log.i(TAG, "Crash log copied to clipboard")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy crash log to clipboard", e)
        }
    }
    
    private fun saveToCrashFile(crashLog: String) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val filename = "crash_$timestamp.txt"
            val file = context.getFileStreamPath(filename)
            file.writeText(crashLog)
            Log.i(TAG, "Crash log saved to: ${file.absolutePath}")
            
            // Keep only last 10 crash files
            val crashFiles = context.filesDir.listFiles { _, name -> 
                name.startsWith("crash_") && name.endsWith(".txt")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
            
            crashFiles.drop(10).forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save crash log to file", e)
        }
    }
}
