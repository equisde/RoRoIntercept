package com.httpinterceptor.proxy

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.httpinterceptor.model.HttpRequest
import com.httpinterceptor.model.HttpResponse
import com.httpinterceptor.model.ProxyRule
import com.httpinterceptor.ui.MainActivity
import com.httpinterceptor.utils.CertificateManager
import com.httpinterceptor.utils.RulesManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class ProxyService : Service() {
    
    private val binder = LocalBinder()
    private var proxyServer: MitmProxyServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val listeners = CopyOnWriteArrayList<ProxyServiceListener>()
    private val requests = mutableListOf<HttpRequest>()
    private val gson = Gson()
    
    private lateinit var certManager: CertificateManager
    private lateinit var rulesManager: RulesManager
    
    interface ProxyServiceListener {
        fun onRequestReceived(request: HttpRequest)
        fun onResponseReceived(requestId: Long, response: HttpResponse)
        fun onProxyStateChanged(isRunning: Boolean)
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): ProxyService = this@ProxyService
    }
    
    override fun onBind(intent: Intent): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        certManager = CertificateManager(this)
        rulesManager = RulesManager(this)
        syncRulesToPrefs(rulesManager.getRules())

        // Ensure Web UI is available even when the app UI isn't open
        com.httpinterceptor.web.WebServerService.start(this)

        // Start foreground immediately to keep service alive
        startForeground(NOTIFICATION_ID, createNotification("Servicio iniciado"))
        acquireWakeLock()

        // If the OS restarts this sticky service, auto-resume the proxy if it was running.
        if (getSharedPreferences("proxy_prefs", MODE_PRIVATE).getBoolean("proxy_running", false)) {
            try {
                startProxy()
            } catch (e: Exception) {
                Log.e(TAG, "Auto-resume proxy failed", e)
            }
        }

        Log.d(TAG, "ProxyService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle action-based commands
        when (intent?.action) {
            "START_PROXY" -> startProxy()
            "STOP_PROXY" -> stopProxy()
            "SYNC_RULES" -> refreshProxyRules()
            "CLEAR_REQUESTS" -> clearRequests()
        }
        
        // Always ensure we're running in foreground
        startForeground(NOTIFICATION_ID, createNotification(
            if (isProxyRunning()) "Proxy activo en puerto 2580"
            else "Proxy detenido"
        ))
        
        // Return START_STICKY to restart service if killed by system
        return START_STICKY
    }
    
    fun startProxy(port: Int = 2580) {
        if (proxyServer != null) return
        
        try {
            acquireWakeLock()
            proxyServer = MitmProxyServer(port, certManager, object : MitmProxyServer.ProxyListener {
                override fun onRequestReceived(request: HttpRequest) {
                    synchronized(requests) {
                        requests.add(0, request)
                        if (requests.size > 1000) {
                            requests.removeAt(requests.size - 1)
                        }
                    }
                    listeners.forEach { it.onRequestReceived(request) }
                    appendLog("REQUEST ${request.method} ${request.url}", "REQUEST")
                    syncRequestsToPrefs()
                }
                
                override fun onRequestModified(request: HttpRequest) {
                    synchronized(requests) {
                        val index = requests.indexOfFirst { it.id == request.id }
                        if (index >= 0) {
                            requests[index] = request
                        }
                    }
                    appendLog("MODIFIED ${request.method} ${request.url}", "INFO")
                    syncRequestsToPrefs()
                }
                
                override fun onResponseReceived(request: HttpRequest) {
                    synchronized(requests) {
                        val index = requests.indexOfFirst { it.id == request.id }
                        if (index >= 0) {
                            requests[index] = request
                        } else {
                            requests.add(0, request)
                        }
                    }
                    listeners.forEach { 
                        if (request.response != null) {
                            it.onResponseReceived(request.id, request.response!!)
                        }
                    }
                    request.response?.let {
                        appendLog("RESPONSE ${it.statusCode} ${request.url}", "RESPONSE")
                    }
                    syncRequestsToPrefs()
                }
                
                override fun onError(error: String) {
                    Log.e(TAG, "Proxy error: $error")
                    appendLog("ERROR $error", "ERROR")
                }
                
                override fun onLog(message: String, level: String) {
                    when (level) {
                        "ERROR" -> Log.e(TAG, message)
                        "WARN" -> Log.w(TAG, message)
                        else -> Log.d(TAG, message)
                    }
                    appendLog(message, level)
                }
            })
            
            proxyServer?.start()
            proxyServer?.updateRules(rulesManager.getRules())
            
            // Update shared preferences
            getSharedPreferences("proxy_prefs", MODE_PRIVATE).edit()
                .putBoolean("proxy_running", true)
                .apply()
            
            // Update notification with running status
            val notification = createNotification("Proxy activo en puerto $port")
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)
            
            listeners.forEach { it.onProxyStateChanged(true) }
            appendLog("Proxy started on port $port", "SUCCESS")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            appendLog("Failed to start proxy: ${e.message}", "ERROR")
            releaseWakeLock()
            throw e
        }
    }
    
    fun stopProxy() {
        proxyServer?.stop()
        proxyServer = null
        releaseWakeLock()
        
        // Update shared preferences
        getSharedPreferences("proxy_prefs", MODE_PRIVATE).edit()
            .putBoolean("proxy_running", false)
            .apply()
        
        // Don't stop Web UI, keep it running
        // Update notification to show proxy stopped
        val notification = createNotification("Proxy detenido | Web UI puerto 8888")
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
        
        listeners.forEach { it.onProxyStateChanged(false) }
        appendLog("Proxy stopped", "WARN")
    }
    
    fun isProxyRunning(): Boolean = proxyServer != null
    
    fun getRequests(): List<HttpRequest> = requests.toList()
    
    fun getRequestById(id: Long): HttpRequest? = synchronized(requests) {
        requests.firstOrNull { it.id == id }
    }
    
    fun clearRequests() {
        synchronized(requests) {
            requests.clear()
        }
        syncRequestsToPrefs()
    }
    
    fun addListener(listener: ProxyServiceListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: ProxyServiceListener) {
        listeners.remove(listener)
    }
    
    fun getRules(): List<ProxyRule> = rulesManager.getRules()
    
    fun addRule(rule: ProxyRule) {
        rulesManager.addRule(rule)
        refreshProxyRules()
    }
    
    fun updateRule(id: Long, rule: ProxyRule) {
        rulesManager.updateRule(id, rule)
        refreshProxyRules()
    }
    
    fun deleteRule(id: Long) {
        rulesManager.deleteRule(id)
        refreshProxyRules()
    }
    
    fun exportCertificate() = certManager.exportCACertificate()
    
    fun getCertificateBytes() = certManager.getCACertificateBytes()
    
    fun getCertificateBytesPEM() = certManager.exportCACertificatePEM()
    
    fun getCertificateBytesDER() = certManager.exportCACertificateDER()
    
    fun getCertificateBytesCRT() = certManager.exportCACertificateCRT()
    
    fun getCertificateBytesCER() = certManager.exportCACertificateCER()
    
    fun isCertificateInstalled() = certManager.isCertificateInstalled()
    
    fun shouldReinstallCertificate() = certManager.shouldReinstallCertificate()
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Proxy Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("HTTP Interceptor")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        stopProxy()
        releaseWakeLock()
        super.onDestroy()
        scheduleRestartIfNeeded("onDestroy")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleRestartIfNeeded("onTaskRemoved")
    }

    private fun scheduleRestartIfNeeded(reason: String) {
        val prefs = getSharedPreferences("proxy_prefs", MODE_PRIVATE)
        val shouldRun = prefs.getBoolean("proxy_running", false)
        if (!shouldRun) return

        try {
            val alarm = getSystemService(ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, ProxyService::class.java).apply { action = "START_PROXY" }
            val pi = PendingIntent.getService(
                this,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2000L, pi)
        } catch (_: Exception) {
        }
    }
    
    private fun refreshProxyRules() {
        // Rules might be edited from the Web UI service; ensure we reload from disk.
        rulesManager.reload()
        val rules = rulesManager.getRules()
        proxyServer?.updateRules(rules)
        syncRulesToPrefs(rules)
        appendLog("Rules synced (${rules.size})", "INFO")
    }
    
    private fun syncRulesToPrefs(rules: List<ProxyRule>) {
        try {
            val json = gson.toJson(rules)
            getSharedPreferences("proxy_rules", MODE_PRIVATE).edit()
                .putString("rules", json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync rules to prefs", e)
        }
    }

    private data class WebUiResponse(
        val statusCode: Int,
        val statusMessage: String,
        val headers: Map<String, String>,
        val body: String?
    )

    private data class WebUiRequest(
        val id: Long,
        val timestamp: Long,
        val method: String,
        val url: String,
        val host: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String?,
        val modified: Boolean,
        val blocked: Boolean,
        val response: WebUiResponse?
    )

    private fun syncRequestsToPrefs() {
        try {
            val snapshot = synchronized(requests) { requests.take(200) }
            val webUi = snapshot.map { req ->
                val resp = req.response
                val respBody = resp?.body?.toUtf8Preview()
                val blocked = (resp?.statusCode == 403) && (respBody?.contains("Blocked by rule", ignoreCase = true) == true)

                WebUiRequest(
                    id = req.id,
                    timestamp = req.timestamp,
                    method = req.method,
                    url = req.url,
                    host = req.host,
                    path = req.path,
                    headers = req.headers,
                    body = req.body?.toUtf8Preview(),
                    modified = req.modified,
                    blocked = blocked,
                    response = resp?.let {
                        WebUiResponse(
                            statusCode = it.statusCode,
                            statusMessage = it.statusMessage,
                            headers = it.headers,
                            body = respBody
                        )
                    }
                )
            }

            val json = gson.toJson(webUi)
            getSharedPreferences("proxy_sessions", MODE_PRIVATE).edit()
                .putString("requests", json)
                .apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync requests to prefs", e)
        }
    }

    private fun ByteArray.toUtf8Preview(maxChars: Int = 20000): String {
        val s = try {
            String(this, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
        return if (s.length > maxChars) s.substring(0, maxChars) + "\n... (truncated)" else s
    }
    
    private fun appendLog(message: String, level: String = "INFO") {
        try {
            val prefs = getSharedPreferences("proxy_logs", MODE_PRIVATE)
            val existing = prefs.getString("logs", "[]") ?: "[]"
            val logs = JSONArray(existing)
            val logEntry = JSONObject().apply {
                put("timestamp", System.currentTimeMillis())
                put("message", message)
                put("type", level.lowercase())
            }
            logs.put(logEntry)
            // Trim to last 200 entries
            val trimmed = JSONArray()
            val start = (logs.length() - 200).coerceAtLeast(0)
            for (i in start until logs.length()) {
                trimmed.put(logs.getJSONObject(i))
            }
            prefs.edit().putString("logs", trimmed.toString()).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append log", e)
        }
    }
    
    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val pm = getSystemService(POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RoRoInterceptor::ProxyWakeLock").apply {
                    setReferenceCounted(false)
                    acquire()
                }
                appendLog("WakeLock acquired for proxy", "INFO")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock", e)
        }
    }
    
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    appendLog("WakeLock released", "INFO")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock", e)
        }
    }
    
    companion object {
        private const val TAG = "ProxyService"
        private const val CHANNEL_ID = "ProxyServiceChannel"
        private const val NOTIFICATION_ID = 1
    }
}
