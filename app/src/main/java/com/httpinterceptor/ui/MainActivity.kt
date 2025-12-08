package com.httpinterceptor.ui

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.IBinder
import android.provider.Settings
import android.text.format.Formatter
import android.view.Menu
import android.view.MenuItem
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
    private var pendingProxyStart = false
    
    private lateinit var btnStartStop: MaterialButton
    private lateinit var tvStatus: MaterialTextView
    private lateinit var tvProxyInfo: MaterialTextView
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: RequestAdapter
    
    // Permission launchers
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted && pendingProxyStart) {
            checkStoragePermissionsAndStart()
        } else if (!isGranted) {
            showPermissionDeniedDialog("Notificaciones")
        }
    }
    
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted && pendingProxyStart) {
            actuallyStartProxy()
        } else if (!allGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                showManageStorageDialog()
            } else {
                showPermissionDeniedDialog("Almacenamiento")
            }
        }
    }
    
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager() && pendingProxyStart) {
                actuallyStartProxy()
            } else if (!Environment.isExternalStorageManager()) {
                showPermissionDeniedDialog("Administrar almacenamiento")
            }
        }
    }
    
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
        
        // Check permissions on first launch
        checkInitialPermissions()
    }
    
    private fun checkInitialPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // Show rationale if needed
                if (ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                ) {
                    AlertDialog.Builder(this)
                        .setTitle("Permiso necesario")
                        .setMessage("RoRo Interceptor necesita permiso de notificaciones para mantener el proxy activo en segundo plano.")
                        .setPositiveButton("Aceptar") { _, _ ->
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }
        }
    }
    
    private fun toggleProxy() {
        proxyService?.let { service ->
            if (service.isProxyRunning()) {
                service.stopProxy()
                pendingProxyStart = false
            } else {
                pendingProxyStart = true
                checkPermissionsAndStartProxy()
            }
        }
    }
    
    private fun checkPermissionsAndStartProxy() {
        // Check notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        
        checkStoragePermissionsAndStart()
    }
    
    private fun checkStoragePermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ - Check media permissions
            val permissionsToRequest = mutableListOf<String>()
            
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_IMAGES
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            
            if (permissionsToRequest.isNotEmpty()) {
                storagePermissionLauncher.launch(permissionsToRequest.toTypedArray())
                return
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12 - Check if has manage storage
            if (!Environment.isExternalStorageManager()) {
                showManageStorageDialog()
                return
            }
        } else {
            // Android 10 and below
            val permissionsToRequest = mutableListOf<String>()
            
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
            
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            
            if (permissionsToRequest.isNotEmpty()) {
                storagePermissionLauncher.launch(permissionsToRequest.toTypedArray())
                return
            }
        }
        
        actuallyStartProxy()
    }
    
    private fun showManageStorageDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("Permiso de almacenamiento")
                .setMessage("RoRo Interceptor necesita acceso completo al almacenamiento para guardar certificados y logs.\n\nPor favor, habilita 'Permitir administración de todos los archivos' en la siguiente pantalla.")
                .setPositiveButton("Configurar") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        manageStorageLauncher.launch(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        manageStorageLauncher.launch(intent)
                    }
                }
                .setNegativeButton("Cancelar") { _, _ ->
                    pendingProxyStart = false
                }
                .show()
        }
    }
    
    private fun showPermissionDeniedDialog(permissionName: String) {
        AlertDialog.Builder(this)
            .setTitle("Permiso denegado")
            .setMessage("El permiso de $permissionName es necesario para que RoRo Interceptor funcione correctamente.\n\nPuedes habilitarlo manualmente en Ajustes > Aplicaciones > RoRo Interceptor > Permisos")
            .setPositiveButton("Ir a Ajustes") { _, _ ->
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            .setNegativeButton("Cancelar") { _, _ ->
                pendingProxyStart = false
            }
            .show()
    }
    
    private fun actuallyStartProxy() {
        proxyService?.let { service ->
            service.startProxy(2580)
            updateProxyInfo()
            pendingProxyStart = false
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
            R.id.action_crash_logs -> {
                openCrashLogs()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
    
    private fun exportCertificate() {
        proxyService?.let { service ->
            try {
                val certFile = service.exportCertificate()
                
                // Use Android's built-in certificate installer
                val intent = Intent(android.security.KeyChain.ACTION_INSTALL)
                intent.putExtra(android.security.KeyChain.EXTRA_CERTIFICATE, certFile.readBytes())
                intent.putExtra(android.security.KeyChain.EXTRA_NAME, "RoRo Interceptor CA")
                
                try {
                    startActivity(intent)
                    android.widget.Toast.makeText(
                        this,
                        "Instalando certificado CA...",
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    // Fallback: Show manual installation instructions
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Certificado CA Exportado")
                        .setMessage("""
                            No se pudo abrir el instalador automático.
                            El certificado se guardó en:
                            ${certFile.absolutePath}
                            
                            Instalación manual:
                            1. Ve a Ajustes > Seguridad > Credenciales de confianza
                            2. Toca "Instalar desde almacenamiento del dispositivo"
                            3. Selecciona el archivo certificado
                            4. Nombra el certificado como "RoRo Interceptor CA"
                            
                            NOTA: Android requiere un PIN/patrón/contraseña de bloqueo de pantalla para instalar certificados CA. Si no tienes uno, configúralo primero en Ajustes > Seguridad.
                        """.trimIndent())
                        .setPositiveButton("OK", null)
                        .show()
                }
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
    
    private fun openCrashLogs() {
        val intent = Intent(this, CrashLogsActivity::class.java)
        startActivity(intent)
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
