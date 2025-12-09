package com.httpinterceptor.proxy

import android.util.Log
import com.httpinterceptor.model.*
import com.httpinterceptor.utils.CertificateManager
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.handler.timeout.IdleStateHandler
import io.netty.util.ReferenceCountUtil
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLException

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
    private val activeConnections = ConcurrentHashMap<String, com.httpinterceptor.model.HttpRequest>()
    
    @Volatile
    private var rules = listOf<ProxyRule>()
    
    interface ProxyListener {
        fun onRequestReceived(request: com.httpinterceptor.model.HttpRequest)
        fun onRequestModified(request: com.httpinterceptor.model.HttpRequest)
        fun onResponseReceived(request: com.httpinterceptor.model.HttpRequest)
        fun onError(error: String)
        fun onLog(message: String, level: String = "INFO")
    }
    
    fun start() {
        try {
            listener.onLog("Starting MITM Proxy on port $port", "INFO")
            
            bossGroup = NioEventLoopGroup(2)
            workerGroup = NioEventLoopGroup(8)
            
            val bootstrap = ServerBootstrap()
            bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(ProxyInitializer())
                .option(ChannelOption.SO_BACKLOG, 256)
                .option(ChannelOption.SO_REUSEADDR, true)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true)
                .childOption(ChannelOption.AUTO_READ, false)
            
            val channelFuture = bootstrap.bind("0.0.0.0", port).sync()
            channel = channelFuture.channel()
            
            listener.onLog("MITM Proxy started successfully on 0.0.0.0:$port", "SUCCESS")
            Log.i(TAG, "Proxy server started on 0.0.0.0:$port")
            
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
                "idle-handler", IdleStateHandler(60, 30, 0),
                "http-codec", HttpServerCodec(),
                "http-aggregator", HttpObjectAggregator(50 * 1024 * 1024),
                "proxy-handler", ProxyFrontendHandler()
            )
        }
    }
    
    private inner class ProxyFrontendHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
        
        private var targetHost: String? = null
        private var targetPort: Int = 443
        private var outboundChannel: Channel? = null
        
        override fun channelActive(ctx: ChannelHandlerContext) {
            ctx.read()
        }
        
        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
            val requestId = requestCounter.incrementAndGet().toString()
            
            try {
                val clientAddress = (ctx.channel().remoteAddress() as? InetSocketAddress)?.address?.hostAddress ?: "unknown"
                listener.onLog("[$requestId] ${request.method()} ${request.uri()} from $clientAddress", "REQUEST")
                
                when {
                    request.method() == HttpMethod.CONNECT -> {
                        handleConnect(ctx, request, requestId)
                    }
                    request.uri().startsWith("http://") -> {
                        handleHttpRequest(ctx, request, requestId)
                    }
                    else -> {
                        handleHttpsRequest(ctx, request, requestId)
                    }
                }
                
            } catch (e: Exception) {
                val error = "[$requestId] Error: ${e.message}"
                listener.onLog(error, "ERROR")
                Log.e(TAG, error, e)
                sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY)
            }
        }
        
        private fun handleConnect(ctx: ChannelHandlerContext, request: FullHttpRequest, requestId: String) {
            val authority = request.uri()
            val parts = authority.split(":")
            targetHost = parts[0]
            targetPort = parts.getOrNull(1)?.toIntOrNull() ?: 443
            
            listener.onLog("[$requestId] CONNECT $targetHost:$targetPort", "CONNECT")
            
            val response = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus(200, "Connection Established")
            )
            response.headers().set(HttpHeaderNames.CONNECTION, "keep-alive")
            
            ctx.writeAndFlush(response).addListener(ChannelFutureListener { future ->
                if (future.isSuccess) {
                    try {
                        val (cert, privateKey) = certificateManager.generateServerCertificate(targetHost!!)
                        val sslContext = SslContextBuilder.forServer(privateKey, cert).build()
                        
                        ctx.pipeline().remove("http-codec")
                        ctx.pipeline().remove("http-aggregator")
                        ctx.pipeline().addFirst("ssl", sslContext.newHandler(ctx.alloc()))
                        ctx.pipeline().addLast("http-codec-2", HttpServerCodec())
                        ctx.pipeline().addLast("http-aggregator-2", HttpObjectAggregator(50 * 1024 * 1024))
                        
                        listener.onLog("[$requestId] SSL tunnel ready for $targetHost", "SUCCESS")
                        ctx.read()
                        
                    } catch (e: Exception) {
                        listener.onLog("[$requestId] SSL setup failed: ${e.message}", "ERROR")
                        ctx.close()
                    }
                } else {
                    ctx.close()
                }
            })
        }
        
        private fun handleHttpRequest(ctx: ChannelHandlerContext, fullRequest: FullHttpRequest, requestId: String) {
            val url = fullRequest.uri()
            val headers = mutableMapOf<String, String>()
            fullRequest.headers().forEach { headers[it.key] = it.value }
            val body = if (fullRequest.content().isReadable) fullRequest.content().toString(StandardCharsets.UTF_8) else ""
            
            val httpRequest = com.httpinterceptor.model.HttpRequest(
                id = requestCounter.incrementAndGet(),
                timestamp = System.currentTimeMillis(),
                method = fullRequest.method().name(),
                url = url,
                headers = headers,
                body = body,
                protocol = "HTTP/1.1",
                isHttps = false
            )
            
            listener.onRequestReceived(httpRequest)
            forwardHttpRequest(ctx, fullRequest, httpRequest, requestId)
        }
        
        private fun handleHttpsRequest(ctx: ChannelHandlerContext, fullRequest: FullHttpRequest, requestId: String) {
            val url = "https://${targetHost}${fullRequest.uri()}"
            val headers = mutableMapOf<String, String>()
            fullRequest.headers().forEach { headers[it.key] = it.value }
            val body = if (fullRequest.content().isReadable) fullRequest.content().toString(StandardCharsets.UTF_8) else ""
            
            val httpRequest = com.httpinterceptor.model.HttpRequest(
                id = requestCounter.incrementAndGet(),
                timestamp = System.currentTimeMillis(),
                method = fullRequest.method().name(),
                url = url,
                headers = headers,
                body = body,
                protocol = "HTTP/1.1",
                isHttps = true
            )
            
            listener.onRequestReceived(httpRequest)
            val modifiedRequest = applyRequestRules(httpRequest, fullRequest)
            forwardHttpsRequest(ctx, fullRequest, modifiedRequest, requestId)
        }
        
        private fun applyRequestRules(request: com.httpinterceptor.model.HttpRequest, nettyRequest: FullHttpRequest): com.httpinterceptor.model.HttpRequest {
            var modified = request
            var wasModified = false
            
            for (rule in rules) {
                if (!rule.enabled) continue
                
                val urlMatches = when {
                    rule.urlPattern.isEmpty() -> true
                    rule.useRegex -> request.url.contains(Regex(rule.urlPattern))
                    else -> request.url.contains(rule.urlPattern, ignoreCase = true)
                }
                
                if (!urlMatches) continue
                
                when (rule.action) {
                    RuleAction.MODIFY_REQUEST -> {
                        rule.modifications?.let { mods ->
                            val newHeaders = modified.headers.toMutableMap()
                            mods.modifyHeaders.forEach { (key, value) ->
                                newHeaders[key] = value
                                nettyRequest.headers().set(key, value)
                                wasModified = true
                            }
                            
                            var newBody = modified.body
                            mods.modifyBody.forEach { (search, replace) ->
                                newBody = if (mods.useRegex) {
                                    newBody.replace(Regex(search), replace)
                                } else {
                                    newBody.replace(search, replace)
                                }
                                wasModified = true
                            }
                            
                            if (wasModified) {
                                modified = modified.copy(headers = newHeaders, body = newBody, modified = true)
                                if (newBody != request.body) {
                                    nettyRequest.content().clear()
                                    nettyRequest.content().writeBytes(newBody.toByteArray(StandardCharsets.UTF_8))
                                    nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, newBody.length)
                                }
                            }
                        }
                    }
                    else -> {}
                }
            }
            
            if (wasModified) {
                listener.onRequestModified(modified)
            }
            
            return modified
        }
        
        private fun forwardHttpRequest(ctx: ChannelHandlerContext, request: FullHttpRequest, httpRequest: com.httpinterceptor.model.HttpRequest, requestId: String) {
            val url = try {
                java.net.URL(request.uri())
            } catch (e: Exception) {
                listener.onLog("[$requestId] Invalid URL", "ERROR")
                sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST)
                return
            }
            
            val targetHost = url.host
            val targetPort = if (url.port > 0) url.port else 80
            
            val b = Bootstrap()
            b.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().addLast(
                            HttpClientCodec(),
                            HttpObjectAggregator(50 * 1024 * 1024),
                            HttpBackendHandler(ctx, httpRequest, requestId)
                        )
                    }
                })
            
            val f = b.connect(targetHost, targetPort)
            outboundChannel = f.channel()
            
            f.addListener(ChannelFutureListener { future ->
                if (future.isSuccess) {
                    listener.onLog("[$requestId] Connected to $targetHost:$targetPort", "SUCCESS")
                    
                    val forwardRequest = DefaultFullHttpRequest(
                        request.protocolVersion(),
                        request.method(),
                        url.file.ifEmpty { "/" },
                        request.content().retain()
                    )
                    
                    request.headers().forEach { forwardRequest.headers().set(it.key, it.value) }
                    forwardRequest.headers().set(HttpHeaderNames.HOST, targetHost)
                    forwardRequest.headers().remove(HttpHeaderNames.PROXY_CONNECTION)
                    
                    outboundChannel?.writeAndFlush(forwardRequest)
                    ctx.read()
                } else {
                    listener.onLog("[$requestId] Connection failed", "ERROR")
                    sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY)
                }
            })
        }
        
        private fun forwardHttpsRequest(ctx: ChannelHandlerContext, request: FullHttpRequest, httpRequest: com.httpinterceptor.model.HttpRequest, requestId: String) {
            val host = targetHost ?: run {
                sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST)
                return
            }
            
            val b = Bootstrap()
            b.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel::class.java)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        try {
                            val clientSslContext = SslContextBuilder.forClient()
                                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                .build()
                            
                            ch.pipeline().addLast(
                                "ssl-client", clientSslContext.newHandler(ch.alloc(), host, targetPort),
                                "http-codec-client", HttpClientCodec(),
                                "http-aggregator-client", HttpObjectAggregator(50 * 1024 * 1024),
                                "backend-handler", HttpBackendHandler(ctx, httpRequest, requestId)
                            )
                        } catch (e: SSLException) {
                            listener.onLog("[$requestId] SSL error: ${e.message}", "ERROR")
                        }
                    }
                })
            
            val f = b.connect(host, targetPort)
            outboundChannel = f.channel()
            
            f.addListener(ChannelFutureListener { future ->
                if (future.isSuccess) {
                    listener.onLog("[$requestId] SSL connected to $host:$targetPort", "SUCCESS")
                    
                    val forwardRequest = DefaultFullHttpRequest(
                        request.protocolVersion(),
                        request.method(),
                        request.uri(),
                        request.content().retain()
                    )
                    
                    request.headers().forEach { forwardRequest.headers().set(it.key, it.value) }
                    forwardRequest.headers().set(HttpHeaderNames.HOST, host)
                    
                    outboundChannel?.writeAndFlush(forwardRequest)
                    ctx.read()
                } else {
                    listener.onLog("[$requestId] SSL connection failed", "ERROR")
                    sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY)
                }
            })
        }
        
        private fun sendErrorResponse(ctx: ChannelHandlerContext, status: HttpResponseStatus) {
            val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status)
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        }
        
        override fun channelInactive(ctx: ChannelHandlerContext) {
            outboundChannel?.close()
            super.channelInactive(ctx)
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            listener.onLog("Error: ${cause.message}", "ERROR")
            Log.e(TAG, "Error", cause)
            ctx.close()
        }
    }
    
    private inner class HttpBackendHandler(
        private val inboundChannel: ChannelHandlerContext,
        private val httpRequest: com.httpinterceptor.model.HttpRequest,
        private val requestId: String
    ) : SimpleChannelInboundHandler<FullHttpResponse>() {
        
        override fun channelRead0(ctx: ChannelHandlerContext, response: FullHttpResponse) {
            listener.onLog("[$requestId] Response: ${response.status().code()}", "RESPONSE")
            
            val headers = mutableMapOf<String, String>()
            response.headers().forEach { headers[it.key] = it.value }
            val body = if (response.content().isReadable) response.content().toString(StandardCharsets.UTF_8) else ""
            
            val httpResponse = com.httpinterceptor.model.HttpResponse(
                requestId = httpRequest.id,
                statusCode = response.status().code(),
                statusMessage = response.status().reasonPhrase(),
                headers = headers,
                body = body.toByteArray(),
                timestamp = System.currentTimeMillis()
            )
            
            listener.onResponseReceived(httpRequest.copy(response = httpResponse))
            
            val clientResponse = DefaultFullHttpResponse(
                response.protocolVersion(),
                response.status(),
                response.content().retain()
            )
            response.headers().forEach { clientResponse.headers().set(it.key, it.value) }
            
            inboundChannel.writeAndFlush(clientResponse).addListener(ChannelFutureListener {
                inboundChannel.read()
            })
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            listener.onLog("[$requestId] Backend error: ${cause.message}", "ERROR")
            ctx.close()
            inboundChannel.close()
        }
    }
}
