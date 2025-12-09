package com.httpinterceptor.ui

import android.content.Context
import android.util.Log
import com.httpinterceptor.model.HttpRequest
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import org.json.JSONObject
import java.io.IOException

class WebUIServer(
    private val context: Context,
    private val port: Int = 8888,
    private val onToggleProxy: (Boolean) -> Unit
) : NanoWSD(port) {

    private val TAG = "WebUIServer"
    private val connections = mutableSetOf<WebSocket>()
    
    @Volatile
    private var isProxyRunning = false
    
    fun setProxyStatus(running: Boolean) {
        isProxyRunning = running
        broadcastProxyStatus()
    }
    
    fun broadcastRequest(request: HttpRequest) {
        val json = JSONObject().apply {
            put("type", "request")
            put("data", JSONObject().apply {
                put("id", request.id)
                put("timestamp", request.timestamp)
                put("method", request.method)
                put("url", request.url)
                put("host", request.host)
                put("path", request.path)
                put("headers", JSONObject(request.headers))
                request.body?.let { put("body", it) }
            })
        }
        broadcast(json.toString())
    }
    
    fun broadcastResponse(request: HttpRequest) {
        request.response?.let { response ->
            val json = JSONObject().apply {
                put("type", "response")
                put("data", JSONObject().apply {
                    put("requestId", request.id)
                    put("statusCode", response.statusCode)
                    put("statusMessage", response.statusMessage)
                    put("headers", JSONObject(response.headers))
                    response.body?.let { put("body", it) }
                    put("timestamp", response.timestamp)
                })
            }
            broadcast(json.toString())
        }
    }
    
    fun broadcastLog(message: String, level: String = "INFO") {
        val json = JSONObject().apply {
            put("type", "log")
            put("message", message)
            put("level", level)
            put("timestamp", System.currentTimeMillis())
        }
        broadcast(json.toString())
    }
    
    private fun broadcastProxyStatus() {
        val json = JSONObject().apply {
            put("type", "status")
            put("running", isProxyRunning)
        }
        broadcast(json.toString())
    }
    
    private fun broadcast(message: String) {
        synchronized(connections) {
            connections.forEach { ws ->
                try {
                    ws.send(message)
                } catch (e: IOException) {
                    Log.e(TAG, "Error broadcasting to WebSocket", e)
                }
            }
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        Log.d(TAG, "Request: $uri")

        return when {
            uri == "/" || uri == "/index.html" -> serveFile("webui/index.html", "text/html")
            uri.startsWith("/api/proxy/status") -> handleProxyStatus()
            uri.startsWith("/api/proxy/toggle") -> handleProxyToggle()
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
        broadcastProxyStatus()
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket {
        return object : WebSocket(handshake) {
            override fun onOpen() {
                Log.d(TAG, "WebSocket opened")
                synchronized(connections) {
                    connections.add(this)
                }
                // Send current proxy status
                try {
                    val json = JSONObject().apply {
                        put("type", "status")
                        put("running", isProxyRunning)
                    }
                    send(json.toString())
                } catch (e: IOException) {
                    Log.e(TAG, "Error sending initial status", e)
                }
            }

            override fun onClose(
                code: WebSocketFrame.CloseCode,
                reason: String,
                initiatedByRemote: Boolean
            ) {
                Log.d(TAG, "WebSocket closed: $reason")
                synchronized(connections) {
                    connections.remove(this)
                }
            }

            override fun onMessage(message: WebSocketFrame) {
                Log.d(TAG, "WebSocket message: ${message.textPayload}")
            }

            override fun onPong(pong: WebSocketFrame) {
                Log.d(TAG, "WebSocket pong")
            }

            override fun onException(exception: IOException) {
                Log.e(TAG, "WebSocket exception", exception)
                synchronized(connections) {
                    connections.remove(this)
                }
            }
        }
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
