package com.httpinterceptor.proxy

import android.util.Log
import com.httpinterceptor.data.HttpRequest
import com.httpinterceptor.data.ProxyRule
import com.httpinterceptor.utils.CertificateManager
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.timeout.IdleStateHandler
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

class MitmProxyServer(
    private val port: Int,
    private val certificateManager: CertificateManager,
    private val listener: ProxyListener
) {
    private val TAG = "MitmProxyServer"
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null
    
    private val requestCounter = AtomicLong(0)
    private val activeConnections = ConcurrentHashMap<String, HttpRequest>()
    
    @Volatile
    private var rules = listOf<ProxyRule>()
    
    interface ProxyListener {
        fun onRequestReceived(request: HttpRequest)
        fun onRequestModified(request: HttpRequest)
        fun onResponseReceived(request: HttpRequest)
        fun onError(error: String)
        fun onLog(message: String, level: String = "INFO")
    }
    
    fun start() {
        try {
            listener.onLog("Starting MITM Proxy on port $port", "INFO")
            
            bossGroup = NioEventLoopGroup(1)
            workerGroup = NioEventLoopGroup()
            
            val bootstrap = ServerBootstrap()
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(ProxyInitializer())
                .option(ChannelOption.SO_BACKLOG, 128)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
            
            val channelFuture = bootstrap.bind(port).sync()
            channel = channelFuture.channel()
            
            listener.onLog("MITM Proxy started successfully on 0.0.0.0:$port", "SUCCESS")
            Log.i(TAG, "Proxy server started on port $port")
            
        } catch (e: Exception) {
            val error = "Failed to start proxy: ${e.message}"
            listener.onError(error)
            listener.onLog(error, "ERROR")
            Log.e(TAG, error, e)
            throw e
        }
    }
    
    fun stop() {
        try {
            listener.onLog("Stopping MITM Proxy...", "INFO")
            
            channel?.close()?.sync()
            workerGroup?.shutdownGracefully()
            bossGroup?.shutdownGracefully()
            
            activeConnections.clear()
            
            listener.onLog("MITM Proxy stopped", "INFO")
            Log.i(TAG, "Proxy server stopped")
            
        } catch (e: Exception) {
            val error = "Error stopping proxy: ${e.message}"
            listener.onError(error)
            Log.e(TAG, error, e)
        }
    }
    
    fun updateRules(newRules: List<ProxyRule>) {
        rules = newRules
        listener.onLog("Rules updated: ${newRules.size} rules active", "INFO")
    }
    
    private inner class ProxyInitializer : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            ch.pipeline().addLast(
                IdleStateHandler(60, 30, 0),
                HttpServerCodec(),
                HttpObjectAggregator(10 * 1024 * 1024), // 10MB max
                ProxyHandler()
            )
        }
    }
    
    private inner class ProxyHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
        
        private var isConnectRequest = false
        private var targetHost: String? = null
        private var targetPort: Int = 80
        
        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
            val requestId = requestCounter.incrementAndGet().toString()
            
            try {
                // Log incoming request
                val clientAddress = (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "unknown"
                listener.onLog("[$requestId] ${request.method()} ${request.uri()} from $clientAddress", "REQUEST")
                
                when {
                    request.method() == HttpMethod.CONNECT -> {
                        handleConnectRequest(ctx, request, requestId)
                    }
                    else -> {
                        handleHttpRequest(ctx, request, requestId)
                    }
                }
                
            } catch (e: Exception) {
                val error = "[$requestId] Error handling request: ${e.message}"
                listener.onError(error)
                listener.onLog(error, "ERROR")
                Log.e(TAG, error, e)
                
                sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY)
            }
        }
        
        private fun handleConnectRequest(ctx: ChannelHandlerContext, request: FullHttpRequest, requestId: String) {
            isConnectRequest = true
            
            // Parse target host and port
            val authority = request.uri()
            val parts = authority.split(":")
            targetHost = parts[0]
            targetPort = parts.getOrNull(1)?.toIntOrNull() ?: 443
            
            listener.onLog("[$requestId] CONNECT to $targetHost:$targetPort", "CONNECT")
            
            // Send 200 Connection Established
            val response = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus(200, "Connection Established")
            )
            
            ctx.writeAndFlush(response).addListener { future ->
                if (future.isSuccess) {
                    listener.onLog("[$requestId] SSL connection established to $targetHost:$targetPort", "SUCCESS")
                    
                    // Upgrade to SSL
                    try {
                        val sslContext = createSslContext(targetHost!!)
                        
                        // Remove HTTP handlers and add SSL handler
                        ctx.pipeline().remove(HttpServerCodec::class.java)
                        ctx.pipeline().remove(HttpObjectAggregator::class.java)
                        ctx.pipeline().addFirst("ssl", sslContext.newHandler(ctx.alloc()))
                        ctx.pipeline().addAfter("ssl", "http-codec", HttpServerCodec())
                        ctx.pipeline().addAfter("http-codec", "http-aggregator", HttpObjectAggregator(10 * 1024 * 1024))
                        
                    } catch (e: Exception) {
                        val error = "[$requestId] Failed to setup SSL: ${e.message}"
                        listener.onError(error)
                        listener.onLog(error, "ERROR")
                        ctx.close()
                    }
                } else {
                    listener.onLog("[$requestId] Failed to establish connection", "ERROR")
                    ctx.close()
                }
            }
        }
        
        private fun handleHttpRequest(ctx: ChannelHandlerContext, request: FullHttpRequest, requestId: String) {
            // Create request object
            val httpRequest = HttpRequest(
                id = requestId,
                method = request.method().name(),
                url = if (isConnectRequest && targetHost != null) {
                    "https://$targetHost${request.uri()}"
                } else {
                    request.uri()
                },
                headers = request.headers().associate { it.key to it.value }.toMutableMap(),
                body = request.content().toString(Charsets.UTF_8),
                timestamp = System.currentTimeMillis(),
                isHttps = isConnectRequest,
                statusCode = 0,
                responseHeaders = mutableMapOf(),
                responseBody = ""
            )
            
            activeConnections[requestId] = httpRequest
            listener.onRequestReceived(httpRequest)
            
            // Apply rules
            val modifiedRequest = applyRequestRules(httpRequest)
            if (modifiedRequest.modified) {
                listener.onRequestModified(modifiedRequest)
                listener.onLog("[$requestId] Request modified by rules", "MODIFY")
            }
            
            // Forward request to target server
            forwardRequest(ctx, modifiedRequest, requestId)
        }
        
        private fun applyRequestRules(request: HttpRequest): HttpRequest {
            var modified = request.copy()
            var wasModified = false
            
            for (rule in rules) {
                if (!rule.enabled) continue
                
                val urlMatches = rule.urlPattern.isEmpty() || 
                    request.url.contains(rule.urlPattern, ignoreCase = true)
                
                if (!urlMatches) continue
                
                when (rule.target) {
                    "request" -> {
                        when (rule.action) {
                            "modify_headers" -> {
                                rule.modifyHeaders.forEach { (key, value) ->
                                    modified.headers[key] = value
                                    wasModified = true
                                }
                            }
                            "modify_body" -> {
                                if (rule.searchPattern.isNotEmpty()) {
                                    val newBody = if (rule.useRegex) {
                                        modified.body.replace(Regex(rule.searchPattern), rule.replaceValue)
                                    } else {
                                        modified.body.replace(rule.searchPattern, rule.replaceValue)
                                    }
                                    if (newBody != modified.body) {
                                        modified = modified.copy(body = newBody)
                                        wasModified = true
                                    }
                                }
                            }
                            "block" -> {
                                modified = modified.copy(blocked = true)
                                wasModified = true
                            }
                        }
                    }
                }
            }
            
            return modified.copy(modified = wasModified)
        }
        
        private fun forwardRequest(ctx: ChannelHandlerContext, request: HttpRequest, requestId: String) {
            // For now, send a simple response
            // In production, this should forward to the actual target server
            
            val responseBody = """
                <!DOCTYPE html>
                <html>
                <head><title>RoRo Interceptor</title></head>
                <body>
                    <h1>Request Intercepted</h1>
                    <p>Method: ${request.method}</p>
                    <p>URL: ${request.url}</p>
                    <p>Request ID: $requestId</p>
                    <p>This is a placeholder response. Full forwarding will be implemented.</p>
                </body>
                </html>
            """.trimIndent()
            
            val response = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK,
                ctx.alloc().buffer().writeBytes(responseBody.toByteArray())
            )
            
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8")
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, responseBody.length)
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            
            request.statusCode = 200
            request.responseHeaders["Content-Type"] = "text/html; charset=UTF-8"
            request.responseBody = responseBody
            
            listener.onResponseReceived(request)
            listener.onLog("[$requestId] Response sent: 200 OK", "RESPONSE")
            
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
            activeConnections.remove(requestId)
        }
        
        private fun sendErrorResponse(ctx: ChannelHandlerContext, status: HttpResponseStatus) {
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status)
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        }
        
        private fun createSslContext(hostname: String): SslContext {
            // Generate certificate for this specific hostname
            val cert = certificateManager.generateCertificate(hostname)
            val privateKey = certificateManager.getCaPrivateKey()
            
            return SslContextBuilder.forServer(privateKey, cert)
                .build()
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            val error = "Connection error: ${cause.message}"
            listener.onError(error)
            listener.onLog(error, "ERROR")
            Log.e(TAG, error, cause)
            ctx.close()
        }
    }
}
