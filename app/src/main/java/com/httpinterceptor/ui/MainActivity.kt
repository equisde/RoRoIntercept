package com.httpinterceptor.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.httpinterceptor.R
import com.httpinterceptor.model.HttpRequest
import com.httpinterceptor.model.HttpResponse
import com.httpinterceptor.proxy.ProxyService

class MainActivity : AppCompatActivity() {
    
    private var proxyService: ProxyService? = null
    private var bound = false
    
    private lateinit var btnStartStop: MaterialButton
    private lateinit var tvStatus: MaterialTextView
    private lateinit var tvProxyInfo: MaterialTextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RequestAdapter
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ProxyService.LocalBinder
            proxyService = binder.getService()
            bound = true
            
            proxyService?.addListener(serviceListener)
            updateUI()
            loadRequests()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService?.removeListener(serviceListener)
            proxyService = null
            bound = false
        }
    }
    
    private val serviceListener = object : ProxyService.ProxyServiceListener {
        override fun onRequestReceived(request: HttpRequest) {
            runOnUiThread {
                adapter.addRequest(request)
            }
        }
        
        override fun onResponseReceived(requestId: Long, response: HttpResponse) {
            runOnUiThread {
                adapter.updateResponse(requestId, response)
            }
        }
        
        override fun onProxyStateChanged(isRunning: Boolean) {
            runOnUiThread {
                updateUI()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        btnStartStop = findViewById(R.id.btnStartStop)
        tvStatus = findViewById(R.id.tvStatus)
        tvProxyInfo = findViewById(R.id.tvProxyInfo)
        recyclerView = findViewById(R.id.recyclerView)
        
        adapter = RequestAdapter { request ->
            val intent = Intent(this, RequestDetailActivity::class.java)
            intent.putExtra("REQUEST_ID", request.id)
            startActivity(intent)
        }
        
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        btnStartStop.setOnClickListener {
            toggleProxy()
        }
        
        val intent = Intent(this, ProxyService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
    
    private fun toggleProxy() {
        proxyService?.let { service ->
            if (service.isProxyRunning()) {
                service.stopProxy()
            } else {
                service.startProxy(2580)
                updateProxyInfo()
            }
        }
    }
    
    private fun updateUI() {
        val isRunning = proxyService?.isProxyRunning() ?: false
        
        btnStartStop.text = if (isRunning) "Stop Proxy" else "Start Proxy"
        tvStatus.text = if (isRunning) "Status: Running" else "Status: Stopped"
        
        if (isRunning) {
            updateProxyInfo()
        } else {
            tvProxyInfo.text = ""
        }
    }
    
    private fun updateProxyInfo() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        tvProxyInfo.text = """
            Proxy: $ipAddress:2580
            Web UI: http://$ipAddress:8080
            
            Configura tu dispositivo/app para usar este proxy.
            Instala el certificado CA para HTTPS.
        """.trimIndent()
    }
    
    private fun loadRequests() {
        proxyService?.getRequests()?.let { requests ->
            adapter.setRequests(requests)
        }
    }
    
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_clear -> {
                proxyService?.clearRequests()
                adapter.clearRequests()
                true
            }
            R.id.action_export_cert -> {
                exportCertificate()
                true
            }
            R.id.action_rules -> {
                openRulesActivity()
                true
            }
            R.id.action_web_ui -> {
                openWebUI()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun exportCertificate() {
        proxyService?.let { service ->
            try {
                val certFile = service.exportCertificate()
                android.widget.Toast.makeText(
                    this,
                    "Certificado exportado a: ${certFile.absolutePath}",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                
                // Show instructions dialog
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Certificado CA Exportado")
                    .setMessage("""
                        El certificado se guardó en:
                        ${certFile.absolutePath}
                        
                        Para interceptar HTTPS:
                        1. Ve a Ajustes > Seguridad > Instalar desde almacenamiento
                        2. Selecciona el archivo: http_interceptor_ca.crt
                        3. Nombra el certificado
                        4. Reinicia tu navegador/app
                    """.trimIndent())
                    .setPositiveButton("OK", null)
                    .show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this,
                    "Error exportando certificado: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
    
    private fun openRulesActivity() {
        // TODO: Implement rules activity
        android.widget.Toast.makeText(this, "Usa la Web UI para gestionar reglas", android.widget.Toast.LENGTH_SHORT).show()
    }
    
    private fun openWebUI() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        val url = "http://$ipAddress:8080"
        
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
        try {
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(this, "No se pudo abrir el navegador", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    override fun onDestroy() {
        if (bound) {
            proxyService?.removeListener(serviceListener)
            unbindService(connection)
            bound = false
        }
        super.onDestroy()
    }
}
