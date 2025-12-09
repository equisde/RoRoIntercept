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
    private var proxyServer: SimpleProxyServer? = null
    private var webServer: WebServerUI? = null
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
        startForeground(NOTIFICATION_ID, createNotification("Web UI disponible en http://localhost:8080"))
        
        // Start Web UI immediately when service is created
        // It stays available even when proxy is stopped
        startWebUI()
        
        Log.d(TAG, "ProxyService created")
    }
    
    private fun startWebUI() {
        if (webServer == null) {
            try {
                webServer = WebServerUI(8080, this)
                webServer?.start()
                Log.d(TAG, "Web UI started on port 8080")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start Web UI", e)
            }
        }
    }
    
    fun startProxy(port: Int = 2580) {
        if (proxyServer != null) return
        
        try {
            proxyServer = SimpleProxyServer(port, object : SimpleProxyServer.ProxyListener {
                override fun onRequestReceived(request: HttpRequest) {
                    synchronized(requests) {
                        requests.add(0, request)
                        if (requests.size > 1000) {
                            requests.removeAt(requests.size - 1)
                        }
                    }
                    listeners.forEach { it.onRequestReceived(request) }
                }
                
                override fun onResponseReceived(requestId: Long, response: HttpResponse) {
                    synchronized(requests) {
                        val request = requests.find { it.id == requestId }
                        request?.response = response
                    }
                    listeners.forEach { it.onResponseReceived(requestId, response) }
                }
                
                override fun onError(error: String) {
                    Log.e(TAG, "Proxy error: $error")
                }
            }, certManager)
            
            proxyServer?.start()
            
            // Ensure Web UI is still running
            startWebUI()
            
            // Update notification with running status
            val notification = createNotification("Proxy: 0.0.0.0:$port | Web UI: http://localhost:8080")
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
        webServer?.stop()
        webServer = null
        super.onDestroy()
    }
    
    companion object {
        private const val TAG = "ProxyService"
        private const val CHANNEL_ID = "ProxyServiceChannel"
        private const val NOTIFICATION_ID = 1
    }
}
