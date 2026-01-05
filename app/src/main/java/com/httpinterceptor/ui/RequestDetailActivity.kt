package com.httpinterceptor.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.format.DateFormat
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayout
import com.httpinterceptor.R
import com.httpinterceptor.model.HttpRequest
import com.httpinterceptor.model.HttpResponse
import com.httpinterceptor.proxy.ProxyService
import java.util.Date

class RequestDetailActivity : AppCompatActivity() {
    
    private lateinit var tabLayout: TabLayout
    private lateinit var tvContent: TextView
    private var proxyService: ProxyService? = null
    private var bound = false
    private var requestId: Long = -1L
    private var currentRequest: HttpRequest? = null
    
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ProxyService.LocalBinder
            proxyService = binder.getService()
            bound = true
            proxyService?.addListener(serviceListener)
            loadRequest()
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            proxyService?.removeListener(serviceListener)
            proxyService = null
            bound = false
        }
    }
    
    private val serviceListener = object : ProxyService.ProxyServiceListener {
        override fun onRequestReceived(request: HttpRequest) {
            if (request.id == requestId) {
                currentRequest = request
                runOnUiThread { updateContent(tabLayout.selectedTabPosition) }
            }
        }
        
        override fun onResponseReceived(requestId: Long, response: HttpResponse) {
            if (requestId == this@RequestDetailActivity.requestId) {
                val req = currentRequest?.copy(response = response)
                currentRequest = req
                runOnUiThread { updateContent(tabLayout.selectedTabPosition) }
            }
        }
        
        override fun onProxyStateChanged(isRunning: Boolean) {}
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_request_detail)
        
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        
        tabLayout = findViewById(R.id.tabLayout)
        tvContent = findViewById(R.id.tvContent)
        
        requestId = intent.getLongExtra("REQUEST_ID", -1)
        
        tabLayout.addTab(tabLayout.newTab().setText("Request"))
        tabLayout.addTab(tabLayout.newTab().setText("Response"))
        tabLayout.addTab(tabLayout.newTab().setText("Headers"))
        tabLayout.addTab(tabLayout.newTab().setText("Body"))
        
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                updateContent(tab.position)
            }
            
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })
        
        updateContent(0)
        
        val intent = Intent(this, ProxyService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
    
    private fun loadRequest() {
        if (requestId == -1L) {
            Toast.makeText(this, "Request ID inválido", Toast.LENGTH_SHORT).show()
            return
        }
        currentRequest = proxyService?.getRequestById(requestId)
        updateContent(tabLayout.selectedTabPosition)
    }
    
    private fun updateContent(position: Int) {
        val request = currentRequest ?: run {
            tvContent.text = "No se encontró la solicitud.\nAsegúrate de que el proxy siga activo."
            return
        }
        
        val response = request.response
        val time = DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(request.timestamp))
        
        tvContent.text = when (position) {
            0 -> """
                Método: ${request.method}
                URL: ${request.url}
                Host: ${request.host}
                Ruta: ${request.path}
                Enviado: $time
                Modificado: ${if (request.modified) "Sí" else "No"}
                Estado: ${response?.statusCode ?: "Pendiente"}
            """.trimIndent()
            1 -> response?.let {
                """
                Código: ${it.statusCode} ${it.statusMessage}
                Recibido: ${DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(it.timestamp))}
                Modificado: ${if (it.modified) "Sí" else "No"}

                Headers:
                ${formatHeaders(it.headers)}

                Body:
                ${formatBody(it.body)}
                """.trimIndent()
            } ?: "Respuesta pendiente..."
            2 -> buildString {
                appendLine("Headers de Request:")
                appendLine(formatHeaders(request.headers))
                appendLine()
                appendLine("Headers de Response:")
                appendLine(response?.let { formatHeaders(it.headers) } ?: "Sin respuesta")
            }
            3 -> buildString {
                appendLine("Cuerpo de Request:")
                appendLine(formatBody(request.body))
                appendLine()
                appendLine("Cuerpo de Response:")
                appendLine(formatBody(response?.body))
            }
            else -> ""
        }
    }
    
    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
    
    override fun onDestroy() {
        if (bound) {
            proxyService?.removeListener(serviceListener)
            unbindService(connection)
        }
        super.onDestroy()
    }
    
    private fun formatHeaders(headers: Map<String, String>): String {
        if (headers.isEmpty()) return "Sin headers"
        return headers.entries.joinToString("\n") { "${it.key}: ${it.value}" }
    }
    
    private fun formatBody(body: ByteArray?): String {
        return body?.toString(Charsets.UTF_8)?.ifBlank { "(sin cuerpo)" } ?: "(sin cuerpo)"
    }
}
