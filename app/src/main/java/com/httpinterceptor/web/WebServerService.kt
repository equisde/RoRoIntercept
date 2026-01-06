package com.httpinterceptor.web

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.httpinterceptor.R
import com.httpinterceptor.utils.RulesManager
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException

class WebServerService : Service() {
    private var webServer: WebServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var rulesManager: RulesManager
    
    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "web_server_channel"
        private const val PREFS = "web_ui_prefs"
        private const val KEY_SHOULD_RUN = "web_ui_should_run"
        const val WEB_PORT = 8888

        fun start(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SHOULD_RUN, true)
                .apply()

            val intent = Intent(context, WebServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_SHOULD_RUN, false)
                .apply()
            context.stopService(Intent(context, WebServerService::class.java))
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        rulesManager = RulesManager(this)
        acquireWakeLock()
        try {
            startForeground(NOTIFICATION_ID, createNotification("Web UI iniciando"))
        } catch (_: Exception) {
            // If notifications are blocked, avoid crashing; the OS may still stop the service.
        }
        startWebServer()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (_: Exception) {
        }
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        stopWebServer()
        releaseWakeLock()
        serviceScope.cancel()
        scheduleRestartIfNeeded("onDestroy")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        scheduleRestartIfNeeded("onTaskRemoved")
    }

    private fun scheduleRestartIfNeeded(reason: String) {
        val shouldRun = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SHOULD_RUN, true)
        if (!shouldRun) return

        try {
            val alarm = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(this, WebServerService::class.java)
            val pi = PendingIntent.getService(
                this,
                2001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 2000L, pi)
        } catch (_: Exception) {
        }
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RoRoIntercept::WebServerWakeLock"
        ).apply {
            acquire()
        }
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
    
    private fun startWebServer() {
        try {
            webServer = WebServer(WEB_PORT, this, rulesManager)
            webServer?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            updateNotification("Web UI running on port $WEB_PORT")
        } catch (e: IOException) {
            e.printStackTrace()
            updateNotification("Failed to start Web UI")
        }
    }
    
    private fun stopWebServer() {
        webServer?.stop()
        webServer = null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Web Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "RoRo Interceptor Web UI Server"
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(message: String = "Web UI is running"): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, com.httpinterceptor.ui.MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val batteryPi = PendingIntent.getActivity(
            this,
            11,
            Intent(this, com.httpinterceptor.ui.BatteryOptimizationActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val certPi = PendingIntent.getActivity(
            this,
            13,
            Intent(this, com.httpinterceptor.ui.CertInstallActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoRo Interceptor - Web UI")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(openApp)
            .addAction(android.R.drawable.ic_menu_manage, "Batería", batteryPi)
            .addAction(android.R.drawable.ic_secure, "Instalar CA", certPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(message: String) {
        try {
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.notify(NOTIFICATION_ID, createNotification(message))
        } catch (_: Exception) {
        }
    }
}

class WebServer(
    port: Int,
    private val context: Context,
    private val rulesManager: RulesManager,
) : NanoHTTPD(port) {

    private val gson = Gson()
    private val certManager by lazy { com.httpinterceptor.utils.CertificateManager(context) }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri

        return when {
            uri == "/" || uri == "/index.html" -> serveWebUi()
            uri == "/api/proxy/status" -> handleProxyStatus()
            uri == "/api/proxy/start" && session.method == Method.POST -> handleProxyStart()
            uri == "/api/proxy/stop" && session.method == Method.POST -> handleProxyStop()
            uri == "/api/requests" -> handleGetRequests()
            uri == "/api/logs" -> handleGetLogs()
            uri == "/api/rules" && session.method == Method.GET -> handleGetRules()
            uri == "/api/rules" && session.method == Method.POST -> handleAddRule(session)
            uri.startsWith("/api/rules/") && session.method == Method.PUT -> handleUpdateRule(session)
            uri.startsWith("/api/rules/") && session.method == Method.DELETE -> handleDeleteRule(session)
            uri == "/api/clear" && session.method == Method.POST -> handleClear()
            uri == "/api/cert/status" -> handleCertStatus()
            uri.startsWith("/api/cert/") -> handleCertDownload(uri)
            uri == "/api/system/status" -> handleSystemStatus()
            uri.startsWith("/static/") -> serveStaticFile(uri)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
        }
    }
    
    private fun handleProxyStart(): Response {
        return try {
            val intent = Intent(context, com.httpinterceptor.proxy.ProxyService::class.java).apply {
                action = "START_PROXY"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            val response = JSONObject().apply {
                put("success", true)
                put("running", true)
                put("message", "Proxy starting...")
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            val response = JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", response.toString())
        }
    }
    
    private fun handleProxyStop(): Response {
        return try {
            val intent = Intent(context, com.httpinterceptor.proxy.ProxyService::class.java).apply {
                action = "STOP_PROXY"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }

            val response = JSONObject().apply {
                put("success", true)
                put("running", false)
                put("message", "Proxy stopping...")
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
        } catch (e: Exception) {
            val response = JSONObject().apply {
                put("success", false)
                put("error", e.message)
            }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", response.toString())
        }
    }
    
    private fun handleProxyStatus(): Response {
        val sharedPrefs = context.getSharedPreferences("proxy_prefs", Context.MODE_PRIVATE)
        val isRunning = sharedPrefs.getBoolean("proxy_running", false)
        
        val response = JSONObject().apply {
            put("running", isRunning)
            put("port", 2580)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }
    
    private fun handleGetLogs(): Response {
        val sharedPrefs = context.getSharedPreferences("proxy_logs", Context.MODE_PRIVATE)
        val logsJson = sharedPrefs.getString("logs", "[]") ?: "[]"
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", logsJson)
    }
    
    private fun handleGetRules(): Response {
        return try {
            val rules = rulesManager.getRules()
            val json = gson.toJson(rules)
            newFixedLengthResponse(Response.Status.OK, "application/json", json)
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", "[]")
        }
    }

    private fun handleAddRule(session: IHTTPSession): Response {
        return try {
            val body = readJsonBody(session)
            val rule = gson.fromJson(body, com.httpinterceptor.model.ProxyRule::class.java)
            rulesManager.addRule(rule)
            notifyProxyRulesChanged()
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}")
        }
    }

    private fun handleUpdateRule(session: IHTTPSession): Response {
        return try {
            val ruleId = session.uri.substringAfterLast('/').toLong()
            val body = readJsonBody(session)
            val rule = gson.fromJson(body, com.httpinterceptor.model.ProxyRule::class.java)
            rulesManager.updateRule(ruleId, rule.copy(id = ruleId))
            notifyProxyRulesChanged()
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}")
        }
    }

    private fun handleDeleteRule(session: IHTTPSession): Response {
        return try {
            val ruleId = session.uri.substringAfterLast('/').toLong()
            rulesManager.deleteRule(ruleId)
            notifyProxyRulesChanged()
            newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
        } catch (e: Exception) {
            newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", "{\"success\":false}")
        }
    }

    private fun handleGetRequests(): Response {
        val json = context.getSharedPreferences("proxy_sessions", Context.MODE_PRIVATE)
            .getString("requests", "[]") ?: "[]"
        return newFixedLengthResponse(Response.Status.OK, "application/json", json)
    }

    private fun handleCertStatus(): Response {
        val fp = try {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            val digest = md.digest(certManager.getCACertificate().encoded)
            digest.joinToString(":") { b -> "%02X".format(b) }
        } catch (_: Exception) {
            ""
        }

        val ca = certManager.getCACertificate()
        val response = JSONObject().apply {
            put("installed", certManager.isCertificateInstalled())
            put("shouldReinstall", certManager.shouldReinstallCertificate())
            put("name", "RoRo Interceptor Root CA")
            put("sha256", fp)
            put("subject", ca.subjectX500Principal?.name ?: "")
            put("notAfter", ca.notAfter?.time ?: 0)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }

    private fun handleCertDownload(uri: String): Response {
        return try {
            when (uri) {
                "/api/cert/ca.pem" -> download(certManager.exportCACertificatePEM(), "application/x-pem-file", "RoRo_Interceptor_CA.pem")
                "/api/cert/ca.der" -> download(certManager.exportCACertificateDER(), "application/x-x509-ca-cert", "RoRo_Interceptor_CA.der")
                "/api/cert/ca.crt" -> download(certManager.exportCACertificateCRT(), "application/x-x509-ca-cert", "RoRo_Interceptor_CA.crt")
                "/api/cert/ca.cer" -> download(certManager.exportCACertificateCER(), "application/x-x509-ca-cert", "RoRo_Interceptor_CA.cer")
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Failed")
        }
    }

    private fun handleSystemStatus(): Response {
        val ignoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                pm.isIgnoringBatteryOptimizations(context.packageName)
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }

        val response = JSONObject().apply {
            put("ignoringBatteryOptimizations", ignoring)
            put("sdkInt", Build.VERSION.SDK_INT)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }

    private fun download(bytes: ByteArray, mime: String, filename: String): Response {
        val resp = newFixedLengthResponse(Response.Status.OK, mime, ByteArrayInputStream(bytes), bytes.size.toLong())
        resp.addHeader("Content-Disposition", "attachment; filename=\"$filename\"")
        resp.addHeader("Cache-Control", "no-store")
        return resp
    }

    private fun handleClear(): Response {
        // Clear in-memory list via service action and clear persisted snapshots/logs.
        try {
            val intent = Intent(context, com.httpinterceptor.proxy.ProxyService::class.java).apply {
                action = "CLEAR_REQUESTS"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {
        }

        context.getSharedPreferences("proxy_sessions", Context.MODE_PRIVATE).edit()
            .putString("requests", "[]")
            .apply()
        context.getSharedPreferences("proxy_logs", Context.MODE_PRIVATE).edit()
            .putString("logs", "[]")
            .apply()

        return newFixedLengthResponse(Response.Status.OK, "application/json", "{\"success\":true}")
    }

    private fun notifyProxyRulesChanged() {
        try {
            val intent = Intent(context, com.httpinterceptor.proxy.ProxyService::class.java).apply {
                action = "SYNC_RULES"
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (_: Exception) {
        }
    }

    private fun readJsonBody(session: IHTTPSession): String {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun serveWebUi(): Response {
        return try {
            val input = context.assets.open("web_ui_bootstrap.html")
            newChunkedResponse(Response.Status.OK, "text/html", input)
        } catch (_: Exception) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Web UI not found")
        }
    }

    private fun serveStaticFile(uri: String): Response {
        return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Static file not found")
    }
    
    private fun serveIndexHtml(): Response {
        val html = """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>RoRo Interceptor - Web UI</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        
        :root {
            --primary: #6366f1;
            --primary-dark: #4f46e5;
            --success: #10b981;
            --danger: #ef4444;
            --warning: #f59e0b;
            --bg-dark: #0f172a;
            --bg-card: #1e293b;
            --bg-hover: #334155;
            --text-primary: #f1f5f9;
            --text-secondary: #94a3b8;
            --border: #334155;
        }
        
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
            background: var(--bg-dark);
            color: var(--text-primary);
            line-height: 1.6;
        }
        
        .container {
            max-width: 1400px;
            margin: 0 auto;
            padding: 20px;
        }
        
        header {
            background: var(--bg-card);
            padding: 20px;
            border-radius: 12px;
            margin-bottom: 20px;
            border: 1px solid var(--border);
        }
        
        h1 {
            font-size: 24px;
            margin-bottom: 10px;
            background: linear-gradient(135deg, var(--primary), var(--success));
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }
        
        .status-bar {
            display: flex;
            gap: 20px;
            flex-wrap: wrap;
            align-items: center;
        }
        
        .status-indicator {
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 8px 16px;
            background: var(--bg-dark);
            border-radius: 8px;
            border: 1px solid var(--border);
        }
        
        .status-dot {
            width: 10px;
            height: 10px;
            border-radius: 50%;
            background: var(--danger);
        }
        
        .status-dot.running {
            background: var(--success);
            animation: pulse 2s infinite;
        }
        
        @keyframes pulse {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.5; }
        }
        
        .controls {
            display: flex;
            gap: 10px;
            flex-wrap: wrap;
        }
        
        button {
            padding: 10px 20px;
            border: none;
            border-radius: 8px;
            font-size: 14px;
            font-weight: 500;
            cursor: pointer;
            transition: all 0.2s;
            display: flex;
            align-items: center;
            gap: 8px;
        }
        
        button:disabled {
            opacity: 0.5;
            cursor: not-allowed;
        }
        
        .btn-primary {
            background: var(--primary);
            color: white;
        }
        
        .btn-primary:hover:not(:disabled) {
            background: var(--primary-dark);
        }
        
        .btn-danger {
            background: var(--danger);
            color: white;
        }
        
        .btn-danger:hover:not(:disabled) {
            background: #dc2626;
        }
        
        .btn-success {
            background: var(--success);
            color: white;
        }
        
        .grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(300px, 1fr));
            gap: 20px;
            margin-bottom: 20px;
        }
        
        .card {
            background: var(--bg-card);
            padding: 20px;
            border-radius: 12px;
            border: 1px solid var(--border);
        }
        
        .card h2 {
            font-size: 18px;
            margin-bottom: 15px;
            color: var(--text-primary);
        }
        
        .logs-container {
            background: var(--bg-dark);
            border-radius: 8px;
            padding: 15px;
            max-height: 400px;
            overflow-y: auto;
            font-family: 'Courier New', monospace;
            font-size: 12px;
            border: 1px solid var(--border);
        }
        
        .log-entry {
            padding: 8px;
            margin-bottom: 5px;
            border-radius: 4px;
            border-left: 3px solid var(--primary);
            background: rgba(99, 102, 241, 0.1);
        }
        
        .log-entry.error {
            border-left-color: var(--danger);
            background: rgba(239, 68, 68, 0.1);
        }
        
        .log-entry.success {
            border-left-color: var(--success);
            background: rgba(16, 185, 129, 0.1);
        }
        
        .log-time {
            color: var(--text-secondary);
            font-size: 11px;
        }
        
        .stat {
            display: flex;
            justify-content: space-between;
            padding: 12px;
            background: var(--bg-dark);
            border-radius: 8px;
            margin-bottom: 10px;
        }
        
        .stat-value {
            font-weight: 600;
            color: var(--primary);
        }
        
        .loading {
            display: inline-block;
            width: 14px;
            height: 14px;
            border: 2px solid rgba(255,255,255,0.3);
            border-top-color: white;
            border-radius: 50%;
            animation: spin 0.6s linear infinite;
        }
        
        @keyframes spin {
            to { transform: rotate(360deg); }
        }
        
        @media (max-width: 768px) {
            .grid {
                grid-template-columns: 1fr;
            }
            
            .status-bar {
                flex-direction: column;
                align-items: stretch;
            }
            
            .controls {
                width: 100%;
            }
            
            button {
                width: 100%;
                justify-content: center;
            }
        }
        
        ::-webkit-scrollbar {
            width: 8px;
        }
        
        ::-webkit-scrollbar-track {
            background: var(--bg-dark);
        }
        
        ::-webkit-scrollbar-thumb {
            background: var(--border);
            border-radius: 4px;
        }
        
        ::-webkit-scrollbar-thumb:hover {
            background: var(--bg-hover);
        }
    </style>
</head>
<body>
    <div class="container">
        <header>
            <h1>🚀 RoRo Interceptor</h1>
            <div class="status-bar">
                <div class="status-indicator">
                    <div class="status-dot" id="statusDot"></div>
                    <span id="statusText">Proxy Stopped</span>
                </div>
                <div class="controls">
                    <button class="btn-success" id="startBtn" onclick="startProxy()">
                        <span>▶</span> Start Proxy
                    </button>
                    <button class="btn-danger" id="stopBtn" onclick="stopProxy()" disabled>
                        <span>■</span> Stop Proxy
                    </button>
                    <button class="btn-primary" onclick="refreshData()">
                        <span>↻</span> Refresh
                    </button>
                </div>
            </div>
        </header>
        
        <div class="grid">
            <div class="card">
                <h2>📊 Statistics</h2>
                <div class="stat">
                    <span>Requests Captured</span>
                    <span class="stat-value" id="requestCount">0</span>
                </div>
                <div class="stat">
                    <span>Active Rules</span>
                    <span class="stat-value" id="ruleCount">0</span>
                </div>
                <div class="stat">
                    <span>Proxy Port</span>
                    <span class="stat-value">2580</span>
                </div>
                <div class="stat">
                    <span>Web UI Port</span>
                    <span class="stat-value">8888</span>
                </div>
            </div>
            
            <div class="card" style="grid-column: span 2;">
                <h2>📝 Live Logs</h2>
                <div class="logs-container" id="logsContainer">
                    <div class="log-entry">
                        <div class="log-time">Waiting for proxy to start...</div>
                    </div>
                </div>
            </div>
        </div>
    </div>
    
    <script>
        let updateInterval;
        
        async function updateStatus() {
            try {
                const response = await fetch('/api/proxy/status');
                const data = await response.json();
                
                const statusDot = document.getElementById('statusDot');
                const statusText = document.getElementById('statusText');
                const startBtn = document.getElementById('startBtn');
                const stopBtn = document.getElementById('stopBtn');
                
                if (data.running) {
                    statusDot.classList.add('running');
                    statusText.textContent = 'Proxy Running';
                    startBtn.disabled = true;
                    stopBtn.disabled = false;
                } else {
                    statusDot.classList.remove('running');
                    statusText.textContent = 'Proxy Stopped';
                    startBtn.disabled = false;
                    stopBtn.disabled = true;
                }
            } catch (error) {
                console.error('Failed to update status:', error);
            }
        }
        
        async function updateLogs() {
            try {
                const response = await fetch('/api/logs');
                const logs = await response.json();
                
                const container = document.getElementById('logsContainer');
                container.innerHTML = '';
                
                if (logs.length === 0) {
                    container.innerHTML = '<div class="log-entry"><div class="log-time">No logs yet...</div></div>';
                    return;
                }
                
                logs.slice(-50).reverse().forEach(log => {
                    const entry = document.createElement('div');
                    entry.className = 'log-entry ' + (log.type || '');
                    entry.innerHTML = `
                        <div class="log-time">${'$'}{log.timestamp}</div>
                        <div>${'$'}{log.message}</div>
                    `;
                    container.appendChild(entry);
                });
                
                document.getElementById('requestCount').textContent = logs.length;
            } catch (error) {
                console.error('Failed to update logs:', error);
            }
        }
        
        async function updateRules() {
            try {
                const response = await fetch('/api/rules');
                const rules = await response.json();
                document.getElementById('ruleCount').textContent = rules.length;
            } catch (error) {
                console.error('Failed to update rules:', error);
            }
        }
        
        async function startProxy() {
            const btn = document.getElementById('startBtn');
            const originalText = btn.innerHTML;
            btn.innerHTML = '<div class="loading"></div> Starting...';
            btn.disabled = true;
            
            try {
                const response = await fetch('/api/proxy/start', { method: 'POST' });
                const data = await response.json();
                
                if (data.success) {
                    setTimeout(updateStatus, 1000);
                } else {
                    alert('Failed to start proxy: ' + data.error);
                }
            } catch (error) {
                alert('Error starting proxy: ' + error.message);
            } finally {
                btn.innerHTML = originalText;
            }
        }
        
        async function stopProxy() {
            const btn = document.getElementById('stopBtn');
            const originalText = btn.innerHTML;
            btn.innerHTML = '<div class="loading"></div> Stopping...';
            btn.disabled = true;
            
            try {
                const response = await fetch('/api/proxy/stop', { method: 'POST' });
                const data = await response.json();
                
                if (data.success) {
                    setTimeout(updateStatus, 1000);
                } else {
                    alert('Failed to stop proxy: ' + data.error);
                }
            } catch (error) {
                alert('Error stopping proxy: ' + error.message);
            } finally {
                btn.innerHTML = originalText;
            }
        }
        
        function refreshData() {
            updateStatus();
            updateLogs();
            updateRules();
        }
        
        // Initial load
        refreshData();
        
        // Auto-refresh every 2 seconds
        updateInterval = setInterval(refreshData, 2000);
    </script>
</body>
</html>
        """.trimIndent()
        
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }
}
