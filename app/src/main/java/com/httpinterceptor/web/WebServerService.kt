package com.httpinterceptor.web

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.httpinterceptor.R
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class WebServerService : Service() {
    private var webServer: WebServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "web_server_channel"
        const val WEB_PORT = 8081
        
        fun start(context: Context) {
            val intent = Intent(context, WebServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, WebServerService::class.java))
        }
    }
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        startWebServer()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        super.onDestroy()
        stopWebServer()
        releaseWakeLock()
        serviceScope.cancel()
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
            webServer = WebServer(WEB_PORT, this)
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
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("RoRo Interceptor - Web UI")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }
    
    private fun updateNotification(message: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(message))
    }
}

class WebServer(port: Int, private val context: Context) : NanoHTTPD(port) {
    
    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        
        return when {
            uri == "/" || uri == "/index.html" -> serveIndexHtml()
            uri == "/api/proxy/status" -> handleProxyStatus()
            uri == "/api/proxy/start" && session.method == Method.POST -> handleProxyStart()
            uri == "/api/proxy/stop" && session.method == Method.POST -> handleProxyStop()
            uri == "/api/logs" -> handleGetLogs()
            uri == "/api/rules" -> handleGetRules()
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
            context.startService(intent)
            
            val response = JSONObject().apply {
                put("success", true)
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
        val sharedPrefs = context.getSharedPreferences("proxy_rules", Context.MODE_PRIVATE)
        val rulesJson = sharedPrefs.getString("rules", "[]") ?: "[]"
        
        return newFixedLengthResponse(Response.Status.OK, "application/json", rulesJson)
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
                    <span class="stat-value">8081</span>
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
