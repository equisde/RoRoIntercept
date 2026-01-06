package com.httpinterceptor.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import com.httpinterceptor.R
import com.httpinterceptor.model.HttpRequest
import com.httpinterceptor.model.HttpResponse
import java.io.File
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

    private var pendingExportBytes: ByteArray? = null
    private var pendingExportMimeType: String = "application/octet-stream"
    private var pendingExportFileName: String = "RoRoInterceptorCA.crt"
    private var pendingExportFormatLabel: String = "CRT"

    private val createDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        val bytes = pendingExportBytes

        if (result.resultCode == RESULT_OK && uri != null && bytes != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                showExportSuccessDialog(uri, pendingExportFileName, pendingExportFormatLabel, pendingExportMimeType)
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this,
                    "Error exportando certificado: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        pendingExportBytes = null
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
        
        // Start Web Server Service immediately to make Web UI available
        com.httpinterceptor.web.WebServerService.start(this)
        
        // Start service immediately to make Web UI available
        val intent = Intent(this, ProxyService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
        
        // Check permissions and battery optimization
        checkBatteryOptimization()
        checkInitialPermissions()
    }
    
    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Optimización de batería")
                    .setMessage("Para que RoRo Interceptor funcione correctamente en segundo plano, es necesario desactivar la optimización de batería.\n\n¿Desea desactivarla ahora?")
                    .setPositiveButton("Sí") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:$packageName")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback to general battery settings
                            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                            startActivity(intent)
                        }
                    }
                    .setNegativeButton("Ahora no", null)
                    .show()
            }
        }
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
        // Storage permissions are not required to run the proxy.
        // Certificate export uses SAF (Storage Access Framework), which works on Android 10–15.
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
        // Check battery optimization first
        checkBatteryOptimizationBeforeProxy()
    }
    
    private fun checkBatteryOptimizationBeforeProxy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val packageName = packageName
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                AlertDialog.Builder(this)
                    .setTitle("Optimización de batería")
                    .setMessage("Para mantener RoRo Interceptor activo en segundo plano, necesitamos excluirlo de la optimización de batería.\n\nEsto permitirá que el proxy funcione continuamente sin ser detenido por el sistema.")
                    .setPositiveButton("Configurar") { _, _ ->
                        try {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            intent.data = Uri.parse("package:$packageName")
                            startActivity(intent)
                            // Wait a bit then start proxy
                            btnStartStop.postDelayed({
                                startProxyNow()
                            }, 1000)
                        } catch (e: Exception) {
                            // If that fails, open battery settings
                            try {
                                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                startActivity(intent)
                                btnStartStop.postDelayed({
                                    startProxyNow()
                                }, 1000)
                            } catch (e2: Exception) {
                                startProxyNow()
                            }
                        }
                    }
                    .setNegativeButton("Omitir") { _, _ ->
                        startProxyNow()
                    }
                    .show()
            } else {
                startProxyNow()
            }
        } else {
            startProxyNow()
        }
    }
    
    private fun startProxyNow() {
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
            Web UI: http://$ipAddress:8888
            
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
        // Check certificate status first
        proxyService?.let { service ->
            val isInstalled = service.isCertificateInstalled()
            val shouldReinstall = service.shouldReinstallCertificate()
            
            var statusMessage = if (isInstalled) {
                "✓ Certificado CA ya está instalado en el sistema."
            } else {
                "⚠ Certificado CA NO está instalado en el sistema."
            }
            
            if (shouldReinstall && isInstalled) {
                statusMessage += "\n⚠ El certificado está por expirar. Se recomienda reinstalar."
            }
            
            // Show status first
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Estado del Certificado")
                .setMessage(statusMessage)
                .setPositiveButton("Exportar Certificado") { _, _ ->
                    showFormatSelectionDialog()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        } ?: run {
            android.widget.Toast.makeText(this, "Servicio de proxy no disponible", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showFormatSelectionDialog() {
        val formats = arrayOf("PEM (.pem)", "DER (.der)", "CRT (.crt)", "CER (.cer)", "Todos los formatos")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Selecciona el Formato")
            .setItems(formats) { _, which ->
                when (which) {
                    0 -> exportCertificateFormat("PEM")
                    1 -> exportCertificateFormat("DER")
                    2 -> exportCertificateFormat("CRT")
                    3 -> exportCertificateFormat("CER")
                    4 -> exportAllFormats()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    
    private fun exportCertificateFormat(format: String) {
        proxyService?.let { service ->
            val (certBytes, fileName, mimeType) = when (format) {
                "PEM" -> Triple(service.getCertificateBytesPEM(), "RoRoInterceptorCA.pem", "text/plain")
                "DER" -> Triple(service.getCertificateBytesDER(), "RoRoInterceptorCA.der", "application/octet-stream")
                "CRT" -> Triple(service.getCertificateBytesCRT(), "RoRoInterceptorCA.crt", "application/x-x509-ca-cert")
                "CER" -> Triple(service.getCertificateBytesCER(), "RoRoInterceptorCA.cer", "application/x-x509-ca-cert")
                else -> return@let
            }

            launchCreateDocument(certBytes, fileName, mimeType, format)
        }
    }

    private fun exportAllFormats() {
        proxyService?.let { service ->
            try {
                val zipBytes = java.io.ByteArrayOutputStream().use { bos ->
                    java.util.zip.ZipOutputStream(bos).use { zos ->
                        fun add(name: String, bytes: ByteArray) {
                            zos.putNextEntry(java.util.zip.ZipEntry(name))
                            zos.write(bytes)
                            zos.closeEntry()
                        }

                        add("RoRoInterceptorCA.pem", service.getCertificateBytesPEM())
                        add("RoRoInterceptorCA.der", service.getCertificateBytesDER())
                        add("RoRoInterceptorCA.crt", service.getCertificateBytesCRT())
                        add("RoRoInterceptorCA.cer", service.getCertificateBytesCER())
                    }
                    bos.toByteArray()
                }

                launchCreateDocument(zipBytes, "RoRoInterceptorCA.zip", "application/zip", "ZIP")
            } catch (e: Exception) {
                android.widget.Toast.makeText(
                    this,
                    "Error exportando certificados: ${e.message}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun launchCreateDocument(bytes: ByteArray, fileName: String, mimeType: String, formatLabel: String) {
        pendingExportBytes = bytes
        pendingExportMimeType = mimeType
        pendingExportFileName = fileName
        pendingExportFormatLabel = formatLabel

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        createDocumentLauncher.launch(intent)
    }

    private fun showExportSuccessDialog(uri: Uri, fileName: String, formatLabel: String, mimeType: String) {
        val formatInfo = when (formatLabel) {
            "PEM" -> "Formato estándar (texto)."
            "DER" -> "Formato binario (DER)."
            "CRT", "CER" -> "Recomendado para Android (CA)."
            "ZIP" -> "Archivo ZIP con todos los formatos."
            else -> ""
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Exportación completada")
            .setMessage("""
                ✓ Guardado como:
                $fileName

                $formatInfo

                Para interceptar tráfico HTTPS, instala este certificado en:
                Configuración → Seguridad → Cifrado y credenciales → Instalar un certificado → Certificado de CA
            """.trimIndent())
            .setPositiveButton("Abrir") { _, _ ->
                openCertificateUri(uri, mimeType)
            }
            .setNeutralButton("Probar otro formato") { _, _ ->
                exportCertificate()
            }
            .setNegativeButton("Instrucciones") { _, _ ->
                showManualInstructions(fileName)
            }
            .show()
    }

    private fun openCertificateUri(uri: Uri, mimeType: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                "No se pudo abrir el archivo. Usa las instrucciones manuales.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    
    private fun openCertificateFile(certFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                certFile
            )
            
            val intent = Intent(Intent.ACTION_VIEW)
            intent.setDataAndType(uri, "application/x-x509-ca-cert")
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            
            startActivity(intent)
        } catch (e: Exception) {
            android.widget.Toast.makeText(
                this,
                "No se pudo abrir el archivo. Usa las instrucciones manuales.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }
    
    private fun showManualInstructions(fileName: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("📱 Instalación Manual del Certificado")
            .setMessage("""
                Guarda/ubica el archivo:
                $fileName

                📋 PASOS PARA INSTALAR:

                1️⃣ Abre Ajustes de tu dispositivo
                2️⃣ Ve a Seguridad y privacidad
                3️⃣ Busca "Credenciales" o "Certificados"
                4️⃣ Toca "Instalar" / "Instalar desde almacenamiento"
                5️⃣ Abre la app Archivos y navega a la ubicación donde lo guardaste
                6️⃣ Selecciona "$fileName" (recomendado: .crt o .cer)
                7️⃣ Elige "CA" / "Certificado de CA" cuando lo pregunte
                8️⃣ Confirma con tu PIN/huella

                ⚠️ IMPORTANTE:
                • Android requiere bloqueo de pantalla para instalar certificados
                • Si pide contraseña, déjala vacía y presiona OK
                • El certificado debe instalarse como "CA de usuario"

                ℹ️ Para Android 14+, algunos dispositivos requieren:
                Ajustes > Seguridad > Más ajustes de seguridad > Cifrado y credenciales > Instalar un certificado
            """.trimIndent())
            .setPositiveButton("Entendido", null)
            .show()
    }
    
    private fun openRulesActivity() {
        val intent = Intent(this, RulesActivity::class.java)
        startActivity(intent)
    }
    
    private fun openWebUI() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ipAddress = Formatter.formatIpAddress(wifiManager.connectionInfo.ipAddress)
        val url = "http://$ipAddress:8888"
        
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
