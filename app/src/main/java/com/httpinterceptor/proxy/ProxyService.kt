package com.httpinterceptor.proxy

import android.app.*
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.httpinterceptor.model.HttpRequest
import com.httpinterceptor.model.HttpResponse
import com.httpinterceptor.model.ProxyRule
import com.httpinterceptor.ui.MainActivity
import com.httpinterceptor.utils.CertificateManager
import com.httpinterceptor.utils.RulesManager
import java.util.concurrent.CopyOnWriteArrayList

class ProxyService : Service() {
    
    private val binder = LocalBinder()
    private var proxyServer: MitmProxyServer? = null
    private val listeners = CopyOnWriteArrayList<ProxyServiceListener>()
    private val requests = mutableListOf<HttpRequest>()
    
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
        
        // Start foreground immediately to keep service alive
        startForeground(NOTIFICATION_ID, createNotification("Servicio iniciado"))
        
        Log.d(TAG, "ProxyService created")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle action-based commands
        when (intent?.action) {
            "START_PROXY" -> startProxy()
            "STOP_PROXY" -> stopProxy()
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
            proxyServer = MitmProxyServer(port, certManager, object : MitmProxyServer.ProxyListener {
                override fun onRequestReceived(request: HttpRequest) {
                    synchronized(requests) {
                        requests.add(0, request)
                        if (requests.size > 1000) {
                            requests.removeAt(requests.size - 1)
                        }
                    }
                    listeners.forEach { it.onRequestReceived(request) }
                }
                
                override fun onRequestModified(request: HttpRequest) {
                    // Optional callback
                }
                
                override fun onResponseReceived(request: HttpRequest) {
                    listeners.forEach { 
                        if (request.response != null) {
                            it.onResponseReceived(request.id, request.response!!)
                        }
                    }
                }
                
                override fun onError(error: String) {
                    Log.e(TAG, "Proxy error: $error")
                }
                
                override fun onLog(message: String, level: String) {
                    when (level) {
                        "ERROR" -> Log.e(TAG, message)
                        "WARN" -> Log.w(TAG, message)
                        else -> Log.d(TAG, message)
                    }
                }
            })
            
            proxyServer?.start()
            
            // Update shared preferences
            getSharedPreferences("proxy_prefs", MODE_PRIVATE).edit()
                .putBoolean("proxy_running", true)
                .apply()
            
            // Update notification with running status
            val notification = createNotification("Proxy activo en puerto $port")
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.notify(NOTIFICATION_ID, notification)
            
            listeners.forEach { it.onProxyStateChanged(true) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start proxy", e)
            throw e
        }
    }
    
    fun stopProxy() {
        proxyServer?.stop()
        proxyServer = null
        
        // Update shared preferences
        getSharedPreferences("proxy_prefs", MODE_PRIVATE).edit()
            .putBoolean("proxy_running", false)
            .apply()
        
        // Don't stop Web UI, keep it running
        // Update notification to show proxy stopped
        val notification = createNotification("Proxy detenido | Web UI: http://localhost:8080")
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, notification)
        
        listeners.forEach { it.onProxyStateChanged(false) }
    }
    
    fun isProxyRunning(): Boolean = proxyServer != null
    
    fun getRequests(): List<HttpRequest> = requests.toList()
    
    fun clearRequests() {
        synchronized(requests) {
            requests.clear()
        }
    }
    
    fun addListener(listener: ProxyServiceListener) {
        listeners.add(listener)
    }
    
    fun removeListener(listener: ProxyServiceListener) {
        listeners.remove(listener)
    }
    
    fun getRules(): List<ProxyRule> = rulesManager.getRules()
    
    fun addRule(rule: ProxyRule) = rulesManager.addRule(rule)
    
    fun updateRule(id: Long, rule: ProxyRule) = rulesManager.updateRule(id, rule)
    
    fun deleteRule(id: Long) = rulesManager.deleteRule(id)
    
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
            .build()
    }
    
    override fun onDestroy() {
        stopProxy()
        super.onDestroy()
    }
    
    companion object {
        private const val TAG = "ProxyService"
        private const val CHANNEL_ID = "ProxyServiceChannel"
        private const val NOTIFICATION_ID = 1
    }
}
