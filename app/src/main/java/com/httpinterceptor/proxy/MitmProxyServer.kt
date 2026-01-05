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
import java.util.concurrent.TimeUnit
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
    
    private data class RequestRuleResult(
        val request: com.httpinterceptor.model.HttpRequest,
        val blockedResponse: FullHttpResponse? = null
    )
    
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
            ch.pipeline().addLast("idle-handler", IdleStateHandler(60, 30, 0, TimeUnit.SECONDS))
            ch.pipeline().addLast("http-codec", HttpServerCodec())
            ch.pipeline().addLast("http-aggregator", HttpObjectAggregator(50 * 1024 * 1024))
            ch.pipeline().addLast("proxy-handler", ProxyFrontendHandler())
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
            // Parse URL from absolute URI
            val uri = fullRequest.uri()
            val parsedHost: String
            val parsedPort: Int
            val path: String
            
            if (uri.startsWith("http://")) {
                val url = try {
                    java.net.URL(uri)
                } catch (e: Exception) {
                    listener.onLog("[$requestId] Invalid URL: $uri", "ERROR")
                    sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST)
                    return
                }
                parsedHost = url.host
                parsedPort = if (url.port > 0) url.port else 80
                path = url.file.ifEmpty { "/" }
            } else {
                // Relative URI - get host from headers
                parsedHost = fullRequest.headers().get(HttpHeaderNames.HOST)?.split(":")?.get(0) ?: run {
                    listener.onLog("[$requestId] No Host header", "ERROR")
                    sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST)
                    return
                }
                parsedPort = fullRequest.headers().get(HttpHeaderNames.HOST)?.split(":")?.getOrNull(1)?.toIntOrNull() ?: 80
                path = uri
            }
            
            val fullUrl = "http://$parsedHost:$parsedPort$path"
            val headers = mutableMapOf<String, String>()
            fullRequest.headers().forEach { headers[it.key] = it.value }
            val body = if (fullRequest.content().isReadable) {
                val bytes = ByteArray(fullRequest.content().readableBytes())
                fullRequest.content().getBytes(fullRequest.content().readerIndex(), bytes)
                bytes
            } else null
            
            val httpRequest = com.httpinterceptor.model.HttpRequest(
                id = requestCounter.incrementAndGet(),
                timestamp = System.currentTimeMillis(),
                method = fullRequest.method().name(),
                url = fullUrl,
                host = parsedHost,
                path = path,
                headers = headers,
                body = body
            )
            
            val ruleResult = applyRequestRules(httpRequest, fullRequest)
            if (ruleResult.blockedResponse != null) {
                listener.onLog("[$requestId] Request blocked by rule", "WARN")
                val blockedResponse = com.httpinterceptor.model.HttpResponse(
                    requestId = httpRequest.id,
                    statusCode = HttpResponseStatus.FORBIDDEN.code(),
                    statusMessage = "Blocked",
                    headers = mapOf(HttpHeaderNames.CONTENT_LENGTH.toString() to "0"),
                    body = ByteArray(0),
                    timestamp = System.currentTimeMillis(),
                    modified = true
                )
                listener.onRequestReceived(ruleResult.request.copy(response = blockedResponse))
                listener.onResponseReceived(ruleResult.request.copy(response = blockedResponse))
                ctx.writeAndFlush(ruleResult.blockedResponse.retain()).addListener(ChannelFutureListener.CLOSE)
                return
            }
            
            listener.onRequestReceived(ruleResult.request)
            forwardHttpRequest(ctx, fullRequest, ruleResult.request, requestId, parsedHost, parsedPort, path)
        }
        
        private fun handleHttpsRequest(ctx: ChannelHandlerContext, fullRequest: FullHttpRequest, requestId: String) {
            val url = "https://${targetHost}${fullRequest.uri()}"
            val headers = mutableMapOf<String, String>()
            fullRequest.headers().forEach { headers[it.key] = it.value }
            val body = if (fullRequest.content().isReadable) {
                val bytes = ByteArray(fullRequest.content().readableBytes())
                fullRequest.content().getBytes(fullRequest.content().readerIndex(), bytes)
                bytes
            } else null
            
            val httpRequest = com.httpinterceptor.model.HttpRequest(
                id = requestCounter.incrementAndGet(),
                timestamp = System.currentTimeMillis(),
                method = fullRequest.method().name(),
                url = url,
                host = targetHost ?: "",
                path = fullRequest.uri(),
                headers = headers,
                body = body
            )
            
            val ruleResult = applyRequestRules(httpRequest, fullRequest)
            if (ruleResult.blockedResponse != null) {
                listener.onLog("[$requestId] Request blocked by rule", "WARN")
                val blockedResponse = com.httpinterceptor.model.HttpResponse(
                    requestId = httpRequest.id,
                    statusCode = HttpResponseStatus.FORBIDDEN.code(),
                    statusMessage = "Blocked",
                    headers = mapOf(HttpHeaderNames.CONTENT_LENGTH.toString() to "0"),
                    body = ByteArray(0),
                    timestamp = System.currentTimeMillis(),
                    modified = true
                )
                listener.onRequestReceived(ruleResult.request.copy(response = blockedResponse))
                listener.onResponseReceived(ruleResult.request.copy(response = blockedResponse))
                ctx.writeAndFlush(ruleResult.blockedResponse.retain()).addListener(ChannelFutureListener.CLOSE)
                return
            }
            
            listener.onRequestReceived(ruleResult.request)
            forwardHttpsRequest(ctx, fullRequest, ruleResult.request, requestId)
        }
        
        private fun applyRequestRules(request: com.httpinterceptor.model.HttpRequest, nettyRequest: FullHttpRequest): RequestRuleResult {
            var modified = request
            var wasModified = false
            
            for (rule in rules) {
                if (!rule.enabled) continue
                if (!matchesRule(rule, request.url)) continue
                
                when (rule.action) {
                    RuleAction.MODIFY -> {
                        rule.modifyRequest?.let { mods ->
                            val newHeaders = modified.headers.toMutableMap()
                            
                            // Add/modify headers
                            mods.modifyHeaders?.forEach { (key, value) ->
                                newHeaders[key] = value
                                nettyRequest.headers().set(key, value)
                                wasModified = true
                            }
                            
                            // Remove headers
                            mods.removeHeaders?.forEach { key ->
                                newHeaders.remove(key)
                                nettyRequest.headers().remove(key)
                                wasModified = true
                            }
                            
                            // Search & replace in headers
                            mods.searchReplaceHeaders?.forEach { sr ->
                                val updatedHeaders = mutableMapOf<String, String>()
                                newHeaders.forEach { (k, v) ->
                                    val newValue = applySearchReplace(v, sr)
                                    updatedHeaders[k] = newValue
                                    if (newValue != v) {
                                        nettyRequest.headers().set(k, newValue)
                                        wasModified = true
                                    }
                                }
                                newHeaders.clear()
                                newHeaders.putAll(updatedHeaders)
                            }
                            
                            // Body modifications
                            var newBody = modified.body
                            
                            // Complete body replacement
                            if (mods.replaceBody != null) {
                                newBody = mods.replaceBody.toByteArray(StandardCharsets.UTF_8)
                                wasModified = true
                            }
                            
                            // Search & replace in body
                            mods.searchReplaceBody?.forEach { sr ->
                                newBody?.let { bodyBytes ->
                                    var bodyStr = bodyBytes.toString(StandardCharsets.UTF_8)
                                    bodyStr = applySearchReplace(bodyStr, sr)
                                    newBody = bodyStr.toByteArray(StandardCharsets.UTF_8)
                                    wasModified = true
                                }
                            }
                            
                            if (wasModified) {
                                modified = modified.copy(headers = newHeaders, body = newBody, modified = true)
                                val bodyToWrite = newBody
                                if (bodyToWrite != null && bodyToWrite != request.body) {
                                    nettyRequest.content().clear()
                                    nettyRequest.content().writeBytes(bodyToWrite)
                                    nettyRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyToWrite.size)
                                }
                            }
                        }
                    }
                    RuleAction.BLOCK -> {
                        val response = DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.FORBIDDEN,
                            Unpooled.wrappedBuffer("Blocked by rule: ${rule.name}".toByteArray(StandardCharsets.UTF_8))
                        )
                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
                        response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                        nettyRequest.release()
                        return RequestRuleResult(modified.copy(modified = wasModified), response)
                    }
                    else -> {}
                }
            }
            
            if (wasModified) {
                listener.onRequestModified(modified)
            }
            
            return RequestRuleResult(modified)
        }
        
        private fun forwardHttpRequest(ctx: ChannelHandlerContext, request: FullHttpRequest, httpRequest: com.httpinterceptor.model.HttpRequest, requestId: String, host: String, port: Int, path: String) {
            val b = Bootstrap()
            b.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        ch.pipeline().addLast("http-codec", HttpClientCodec())
                        ch.pipeline().addLast("http-aggregator", HttpObjectAggregator(50 * 1024 * 1024))
                        ch.pipeline().addLast("backend-handler", HttpBackendHandler(ctx, httpRequest, requestId))
                    }
                })
            
            listener.onLog("[$requestId] Connecting to $host:$port", "INFO")
            val f = b.connect(host, port)
            outboundChannel = f.channel()
            
            f.addListener(ChannelFutureListener { future ->
                if (future.isSuccess) {
                    listener.onLog("[$requestId] Connected to $host:$port", "SUCCESS")
                    
                    // Create request with relative path
                    val forwardRequest = DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        request.method(),
                        path,
                        request.content().retain()
                    )
                    
                    // Copy headers
                    request.headers().forEach { (key, value) ->
                        if (key.toLowerCase() !in listOf("proxy-connection", "proxy-authorization")) {
                            forwardRequest.headers().set(key, value)
                        }
                    }
                    
                    // Ensure proper Host header
                    forwardRequest.headers().set(HttpHeaderNames.HOST, if (port == 80) host else "$host:$port")
                    forwardRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE)
                    
                    listener.onLog("[$requestId] Forwarding request: ${request.method()} $path", "INFO")
                    outboundChannel?.writeAndFlush(forwardRequest)?.addListener(ChannelFutureListener { writeFuture ->
                        if (writeFuture.isSuccess) {
                            ctx.read()
                        } else {
                            listener.onLog("[$requestId] Failed to forward request", "ERROR")
                            sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY)
                        }
                    })
                } else {
                    listener.onLog("[$requestId] Connection to $host:$port failed: ${future.cause()?.message}", "ERROR")
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
                            
                            ch.pipeline().addLast("ssl-client", clientSslContext.newHandler(ch.alloc(), host, targetPort))
                            ch.pipeline().addLast("http-codec-client", HttpClientCodec())
                            ch.pipeline().addLast("http-aggregator-client", HttpObjectAggregator(50 * 1024 * 1024))
                            ch.pipeline().addLast("backend-handler", HttpBackendHandler(ctx, httpRequest, requestId))
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
            val bodyBytes = if (response.content().isReadable) {
                ByteArray(response.content().readableBytes()).also {
                    response.content().getBytes(response.content().readerIndex(), it)
                }
            } else null
            
            val httpResponse = com.httpinterceptor.model.HttpResponse(
                requestId = httpRequest.id,
                statusCode = response.status().code(),
                statusMessage = response.status().reasonPhrase(),
                headers = headers,
                body = bodyBytes,
                timestamp = System.currentTimeMillis()
            )
            
            val (finalResponse, outboundNettyResponse) = applyResponseRules(httpRequest, httpResponse, response)
            val needsRelease = outboundNettyResponse !== response
            
            listener.onResponseReceived(httpRequest.copy(response = finalResponse))
            
            val clientResponse = DefaultFullHttpResponse(
                outboundNettyResponse.protocolVersion(),
                outboundNettyResponse.status(),
                outboundNettyResponse.content().retain()
            )
            outboundNettyResponse.headers().forEach { clientResponse.headers().set(it.key, it.value) }
            clientResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, clientResponse.content().readableBytes())
            
            inboundChannel.writeAndFlush(clientResponse).addListener(ChannelFutureListener {
                if (needsRelease) {
                    ReferenceCountUtil.release(outboundNettyResponse)
                }
                inboundChannel.read()
            })
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            listener.onLog("[$requestId] Backend error: ${cause.message}", "ERROR")
            ctx.close()
            inboundChannel.close()
        }
    }
    
    private fun matchesRule(rule: ProxyRule, url: String): Boolean {
        return try {
            when (rule.matchType) {
                MatchType.CONTAINS -> url.contains(rule.urlPattern, ignoreCase = true)
                MatchType.REGEX -> Regex(rule.urlPattern).containsMatchIn(url)
                MatchType.EXACT -> url.equals(rule.urlPattern, ignoreCase = true)
                MatchType.STARTS_WITH -> url.startsWith(rule.urlPattern, ignoreCase = true)
                MatchType.ENDS_WITH -> url.endsWith(rule.urlPattern, ignoreCase = true)
            }
        } catch (e: Exception) {
            listener.onLog("Invalid rule pattern: ${rule.urlPattern}", "WARN")
            false
        }
    }
    
    private fun applyResponseRules(
        httpRequest: com.httpinterceptor.model.HttpRequest,
        appResponse: com.httpinterceptor.model.HttpResponse,
        nettyResponse: FullHttpResponse
    ): Pair<com.httpinterceptor.model.HttpResponse, FullHttpResponse> {
        var modifiedResponse = appResponse
        var wasModified = false
        var workingNettyResponse = nettyResponse
        var bodyBytes = appResponse.body
        val newHeaders = appResponse.headers.toMutableMap()
        
        for (rule in rules) {
            if (!rule.enabled) continue
            if (!matchesRule(rule, httpRequest.url)) continue
            
            when (rule.action) {
                RuleAction.BLOCK -> {
                    val body = "Blocked by rule: ${rule.name}".toByteArray(StandardCharsets.UTF_8)
                    val blocked = DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.FORBIDDEN,
                        Unpooled.wrappedBuffer(body)
                    )
                    blocked.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
                    blocked.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.size)
                    return Pair(
                        modifiedResponse.copy(
                            statusCode = HttpResponseStatus.FORBIDDEN.code(),
                            statusMessage = "Blocked",
                            body = body,
                            headers = mapOf(
                                HttpHeaderNames.CONTENT_TYPE.toString() to "text/plain; charset=UTF-8",
                                HttpHeaderNames.CONTENT_LENGTH.toString() to body.size.toString()
                            ),
                            modified = true
                        ),
                        blocked
                    )
                }
                RuleAction.MODIFY -> {
                    val mods = rule.modifyResponse ?: continue
                    
                    mods.modifyHeaders?.forEach { (key, value) ->
                        newHeaders[key] = value
                        workingNettyResponse.headers().set(key, value)
                        wasModified = true
                    }
                    
                    mods.removeHeaders?.forEach { key ->
                        newHeaders.remove(key)
                        workingNettyResponse.headers().remove(key)
                        wasModified = true
                    }
                    
                    mods.searchReplaceHeaders?.forEach { sr ->
                        val updatedHeaders = mutableMapOf<String, String>()
                        newHeaders.forEach { (k, v) ->
                            val newValue = applySearchReplace(v, sr)
                            updatedHeaders[k] = newValue
                            if (newValue != v) {
                                workingNettyResponse.headers().set(k, newValue)
                                wasModified = true
                            }
                        }
                        newHeaders.clear()
                        newHeaders.putAll(updatedHeaders)
                    }
                    
                    mods.replaceBody?.let { replace ->
                        bodyBytes = replace.toByteArray(StandardCharsets.UTF_8)
                        wasModified = true
                    }
                    
                    mods.searchReplaceBody?.forEach { sr ->
                        bodyBytes?.let { bytes ->
                            val current = String(bytes, StandardCharsets.UTF_8)
                            val newBody = applySearchReplace(current, sr)
                            if (newBody != current) {
                                bodyBytes = newBody.toByteArray(StandardCharsets.UTF_8)
                                wasModified = true
                            }
                        }
                    }
                }
                else -> {}
            }
        }
        
        if (wasModified) {
            val finalBody = bodyBytes ?: ByteArray(0)
            workingNettyResponse.content().clear()
            workingNettyResponse.content().writeBytes(finalBody)
            workingNettyResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, finalBody.size)
            newHeaders[HttpHeaderNames.CONTENT_LENGTH.toString()] = finalBody.size.toString()
            
            modifiedResponse = modifiedResponse.copy(
                headers = newHeaders,
                body = if (bodyBytes?.isNotEmpty() == true) finalBody else bodyBytes,
                modified = true
            )
        }
        
        return Pair(modifiedResponse, workingNettyResponse)
    }
    
    private fun applySearchReplace(text: String, sr: SearchReplace): String {
        return if (sr.useRegex) {
            val pattern = if (sr.caseSensitive) {
                Regex(sr.search)
            } else {
                Regex(sr.search, RegexOption.IGNORE_CASE)
            }
            if (sr.replaceAll) {
                text.replace(pattern, sr.replace)
            } else {
                text.replaceFirst(pattern, sr.replace)
            }
        } else {
            if (sr.replaceAll) {
                text.replace(sr.search, sr.replace, ignoreCase = !sr.caseSensitive)
            } else {
                text.replaceFirst(sr.search, sr.replace, ignoreCase = !sr.caseSensitive)
            }
        }
    }
}
