package com.httpinterceptor.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.httpinterceptor.proxy.ProxyService
import com.httpinterceptor.web.WebServerService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        try {
            WebServerService.start(context)
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start WebServerService", e)
        }

        val prefs = context.getSharedPreferences("proxy_prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("proxy_running", false)) return

        try {
            val svc = Intent(context, ProxyService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc)
            } else {
                context.startService(svc)
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Failed to start ProxyService", e)
        }
    }
}
