package com.httpinterceptor.ui

import android.content.Context
import android.util.Log
import com.httpinterceptor.model.HttpRequest
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class WebUIServer(
    private val context: Context,
    private val port: Int = 8888,
    private val onToggleProxy: (Boolean) -> Unit
) : NanoHTTPD(port) {

    private val TAG = "WebUIServer"
    private val requests = mutableListOf<HttpRequest>()
    private val logs = mutableListOf<Pair<String, String>>()
    
    @Volatile
    private var isProxyRunning = false
    
    fun setProxyStatus(running: Boolean) {
        isProxyRunning = running
    }
    
    fun broadcastRequest(request: HttpRequest) {
        synchronized(requests) {
            requests.add(request)
            // Keep only last 100 requests
            if (requests.size > 100) {
                requests.removeAt(0)
            }
        }
    }
    
    fun broadcastLog(message: String, level: String = "INFO") {
        synchronized(logs) {
            logs.add(Pair(message, level))
            // Keep only last 100 logs
            if (logs.size > 100) {
                logs.removeAt(0)
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Log.d(TAG, "Request: $method $uri")

        return when {
            uri == "/" || uri == "/index.html" -> serveFile("webui/index.html", "text/html")
            uri == "/api/proxy/status" -> handleProxyStatus()
            uri == "/api/proxy/toggle" && method == Method.POST -> handleProxyToggle()
            uri == "/api/requests" -> handleGetRequests()
            uri == "/api/logs" -> handleGetLogs()
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
        }
    }

    private fun serveFile(assetPath: String, mimeType: String): Response {
        return try {
            val inputStream = context.assets.open(assetPath)
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: IOException) {
            Log.e(TAG, "Error serving file: $assetPath", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Error loading file"
            )
        }
    }

    private fun handleProxyStatus(): Response {
        val json = JSONObject().apply {
            put("running", isProxyRunning)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun handleProxyToggle(): Response {
        isProxyRunning = !isProxyRunning
        onToggleProxy(isProxyRunning)
        
        val json = JSONObject().apply {
            put("running", isProxyRunning)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }
    
    private fun handleGetRequests(): Response {
        val jsonArray = JSONArray()
        synchronized(requests) {
            requests.forEach { request ->
                val json = JSONObject().apply {
                    put("id", request.id)
                    put("method", request.method)
                    put("url", request.url)
                    put("timestamp", request.timestamp)
                    put("statusCode", request.statusCode)
                    put("protocol", request.protocol)
                }
                jsonArray.put(json)
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
    }
    
    private fun handleGetLogs(): Response {
        val jsonArray = JSONArray()
        synchronized(logs) {
            logs.forEach { (message, level) ->
                val json = JSONObject().apply {
                    put("message", message)
                    put("level", level)
                    put("timestamp", System.currentTimeMillis())
                }
                jsonArray.put(json)
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", jsonArray.toString())
    }

    fun startServer() {
        try {
            start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.i(TAG, "Web UI Server started on port $port")
            broadcastLog("Web UI Server started on port $port", "INFO")
        } catch (e: IOException) {
            Log.e(TAG, "Error starting Web UI Server", e)
        }
    }

    fun stopServer() {
        stop()
        Log.i(TAG, "Web UI Server stopped")
    }
}
