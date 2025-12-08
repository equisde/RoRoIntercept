package com.httpinterceptor.proxy

import android.util.Log
import com.httpinterceptor.model.HttpRequest as AppHttpRequest
import com.httpinterceptor.model.HttpResponse as AppHttpResponse
import com.httpinterceptor.model.ProxyRule
import com.httpinterceptor.model.RuleAction
import com.httpinterceptor.model.ModifyAction
import com.httpinterceptor.model.SearchReplace
import com.httpinterceptor.utils.CertificateManager
import com.httpinterceptor.utils.RulesManager
import io.netty.bootstrap.Bootstrap
import io.netty.bootstrap.ServerBootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import io.netty.util.ReferenceCountUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap

class ProxyServerV2(
    private val port: Int,
    private val listener: ProxyListener,
    private val certManager: CertificateManager,
    private val rulesManager: RulesManager
) {
    
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null
    private val requestCache = ConcurrentHashMap<Long, AppHttpRequest>()
    
    interface ProxyListener {
        fun onRequestReceived(request: AppHttpRequest)
        fun onResponseReceived(requestId: Long, response: AppHttpResponse)
        fun onError(error: String)
    }
    
    fun start() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
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
                
                channel = bootstrap.bind("0.0.0.0", port).sync().channel()
                Log.d(TAG, "Proxy started on 0.0.0.0:$port (accepting external connections)")
                
                channel?.closeFuture()?.sync()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting proxy", e)
                listener.onError("Failed to start proxy: ${e.message}")
            } finally {
                stop()
            }
        }
    }
    
    fun stop() {
        channel?.close()
        workerGroup?.shutdownGracefully()
        bossGroup?.shutdownGracefully()
        requestCache.clear()
        Log.d(TAG, "Proxy stopped")
    }
    
    inner class ProxyInitializer : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            ch.pipeline().addLast(
                HttpServerCodec(8192, 65536, 65536),
                HttpObjectAggregator(10 * 1024 * 1024),
                ProxyFrontendHandler()
            )
        }
    }
    
    inner class ProxyFrontendHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
        
        private var outboundChannel: Channel? = null
        private var targetHost: String? = null
        private var targetPort: Int = 80
        
        override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            msg.retain() // Retain for async processing
            
            if (msg.method() == HttpMethod.CONNECT) {
                handleConnect(ctx, msg)
            } else {
                handleRequest(ctx, msg)
            }
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Log.e(TAG, "Exception in frontend handler", cause)
            ctx.close()
        }
        
        override fun channelInactive(ctx: ChannelHandlerContext) {
            outboundChannel?.close()
            super.channelInactive(ctx)
        }
        
        private fun handleConnect(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            val hostAndPort = msg.uri().split(":")
            val host = hostAndPort[0]
            val port = hostAndPort.getOrNull(1)?.toIntOrNull() ?: 443
            
            targetHost = host
            targetPort = port
            
            Log.d(TAG, "CONNECT to $host:$port")
            
            // Send 200 Connection Established
            val response = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus(200, "Connection Established")
            )
            
            ctx.writeAndFlush(response).addListener { future ->
                if (future.isSuccess) {
                    // Remove HTTP codec
                    ctx.pipeline().remove(HttpServerCodec::class.java)
                    ctx.pipeline().remove(HttpObjectAggregator::class.java)
                    ctx.pipeline().remove(this)
                    
                    // Add SSL handler with generated certificate
                    try {
                        val (cert, privateKey) = certManager.generateServerCertificate(host)
                        val sslContext = SslContextBuilder
                            .forServer(privateKey, cert)
                            .build()
                        
                        val sslHandler = sslContext.newHandler(ctx.alloc())
                        ctx.pipeline().addFirst("ssl", sslHandler)
                        
                        // Wait for SSL handshake, then add HTTP codecs
                        sslHandler.handshakeFuture().addListener { sslFuture ->
                            if (sslFuture.isSuccess) {
                                ctx.pipeline().addLast(HttpServerCodec())
                                ctx.pipeline().addLast(HttpObjectAggregator(10 * 1024 * 1024))
                                ctx.pipeline().addLast(ProxyFrontendHandler())
                                Log.d(TAG, "SSL handshake complete for $host")
                            } else {
                                Log.e(TAG, "SSL handshake failed for $host", sslFuture.cause())
                                ctx.close()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting up SSL for $host", e)
                        ctx.close()
                    }
                } else {
                    Log.e(TAG, "Failed to send CONNECT response", future.cause())
                    ctx.close()
                }
                ReferenceCountUtil.release(msg)
            }
        }
        
        private fun handleRequest(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            val requestId = System.currentTimeMillis() + (0..9999).random()
            
            try {
                val headers = mutableMapOf<String, String>()
                for (header in msg.headers()) {
                    headers[header.key] = header.value
                }
                
                val bodyBytes = if (msg.content().readableBytes() > 0) {
                    ByteArray(msg.content().readableBytes()).also {
                        msg.content().getBytes(0, it)
                    }
                } else null
                
                val uri = msg.uri()
                val host = targetHost ?: headers["Host"] ?: parseHostFromUri(uri)
                val isHttps = ctx.pipeline().get("ssl") != null
                val scheme = if (isHttps) "https" else "http"
                val port = if (targetPort != 0 && targetPort != 80 && targetPort != 443) ":$targetPort" else ""
                
                var fullUrl = if (uri.startsWith("http://") || uri.startsWith("https://")) {
                    uri
                } else {
                    "$scheme://$host$port$uri"
                }
                
                var request = AppHttpRequest(
                    id = requestId,
                    timestamp = System.currentTimeMillis(),
                    method = msg.method().name(),
                    url = fullUrl,
                    host = host,
                    path = uri,
                    headers = headers,
                    body = bodyBytes
                )
                
                // Apply request modification rules
                val matchingRules = rulesManager.findMatchingRules(request.url)
                for (rule in matchingRules) {
                    when (rule.action) {
                        RuleAction.BLOCK -> {
                            Log.d(TAG, "Blocked request: ${request.url}")
                            val blockedResponse = DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1,
                                HttpResponseStatus.FORBIDDEN,
                                Unpooled.copiedBuffer("Blocked by proxy rule", StandardCharsets.UTF_8)
                            )
                            blockedResponse.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
                            blockedResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, blockedResponse.content().readableBytes())
                            ctx.writeAndFlush(blockedResponse).addListener(ChannelFutureListener.CLOSE)
                            ReferenceCountUtil.release(msg)
                            return
                        }
                        RuleAction.MODIFY -> {
                            rule.modifyRequest?.let { modifyAction ->
                                request = applyRequestModifications(request, modifyAction)
                                request.modified = true
                            }
                        }
                        else -> {}
                    }
                }
                
                requestCache[requestId] = request
                listener.onRequestReceived(request)
                
                // Forward request to target server
                forwardRequest(ctx, request, msg, matchingRules)
            } catch (e: Exception) {
                Log.e(TAG, "Error handling request", e)
                val errorResponse = DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Unpooled.copiedBuffer("Proxy Error: ${e.message}", StandardCharsets.UTF_8)
                )
                errorResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, errorResponse.content().readableBytes())
                ctx.writeAndFlush(errorResponse).addListener(ChannelFutureListener.CLOSE)
                ReferenceCountUtil.release(msg)
            }
        }
        
        private fun parseHostFromUri(uri: String): String {
            return try {
                val url = URI(uri)
                url.host ?: "unknown"
            } catch (e: Exception) {
                "unknown"
            }
        }
        
        private fun forwardRequest(
            ctx: ChannelHandlerContext,
            request: AppHttpRequest,
            originalMsg: FullHttpRequest,
            matchingRules: List<ProxyRule>
        ) {
            try {
                val uri = URI(request.url)
                val targetHost = uri.host ?: "unknown"
                val targetPort = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
                val isHttps = uri.scheme == "https"
                
                Log.d(TAG, "Forwarding ${request.method} to $targetHost:$targetPort (HTTPS: $isHttps)")
                
                val bootstrap = Bootstrap()
                bootstrap.group(ctx.channel().eventLoop())
                    .channel(NioSocketChannel::class.java)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 15000)
                    .handler(object : ChannelInitializer<SocketChannel>() {
                        override fun initChannel(ch: SocketChannel) {
                            if (isHttps) {
                                // SSL for backend connection
                                val sslContext = SslContextBuilder
                                    .forClient()
                                    .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                    .build()
                                ch.pipeline().addLast("ssl", sslContext.newHandler(ch.alloc(), targetHost, targetPort))
                            }
                            ch.pipeline().addLast("codec", HttpClientCodec(8192, 65536, 65536))
                            ch.pipeline().addLast("aggregator", HttpObjectAggregator(10 * 1024 * 1024))
                            ch.pipeline().addLast("handler", BackendHandler(ctx, request.id, matchingRules))
                        }
                    })
                
                val connectFuture = bootstrap.connect(targetHost, targetPort)
                connectFuture.addListener(ChannelFutureListener { future ->
                    if (future.isSuccess) {
                        val outboundCh = future.channel()
                        
                        // Build outbound request
                        val path = if (uri.rawPath.isNullOrEmpty()) "/" else uri.rawPath +
                                (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
                        
                        val outboundRequest = DefaultFullHttpRequest(
                            HttpVersion.HTTP_1_1,
                            HttpMethod.valueOf(request.method),
                            path
                        )
                        
                        // Copy headers (excluding proxy-specific ones)
                        for ((key, value) in request.headers) {
                            if (!key.equals("Proxy-Connection", ignoreCase = true) &&
                                !key.equals("Proxy-Authorization", ignoreCase = true) &&
                                !key.equals("Connection", ignoreCase = true)) {
                                outboundRequest.headers().set(key, value)
                            }
                        }
                        
                        // Ensure required headers
                        outboundRequest.headers().set(HttpHeaderNames.HOST, targetHost)
                        outboundRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                        
                        // Copy body
                        if (request.body != null && request.body.isNotEmpty()) {
                            outboundRequest.content().writeBytes(request.body)
                            outboundRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, request.body.size)
                        } else {
                            outboundRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0)
                        }
                        
                        Log.d(TAG, "Sending request to $targetHost: ${request.method} $path")
                        outboundCh.writeAndFlush(outboundRequest).addListener(ChannelFutureListener { writeFuture ->
                            if (!writeFuture.isSuccess) {
                                Log.e(TAG, "Failed to write request to $targetHost", writeFuture.cause())
                                ctx.close()
                            }
                        })
                        
                    } else {
                        Log.e(TAG, "Failed to connect to $targetHost:$targetPort", future.cause())
                        val errorResponse = DefaultFullHttpResponse(
                            HttpVersion.HTTP_1_1,
                            HttpResponseStatus.BAD_GATEWAY,
                            Unpooled.copiedBuffer("Failed to connect to target server", StandardCharsets.UTF_8)
                        )
                        errorResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, errorResponse.content().readableBytes())
                        ctx.writeAndFlush(errorResponse).addListener(ChannelFutureListener.CLOSE)
                        ReferenceCountUtil.release(originalMsg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error forwarding request", e)
                val errorResponse = DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    Unpooled.copiedBuffer("Error: ${e.message}", StandardCharsets.UTF_8)
                )
                errorResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, errorResponse.content().readableBytes())
                ctx.writeAndFlush(errorResponse).addListener(ChannelFutureListener.CLOSE)
                ReferenceCountUtil.release(originalMsg)
            }
        }
        
        private fun applyRequestModifications(request: AppHttpRequest, modifyAction: ModifyAction): AppHttpRequest {
            val newHeaders = request.headers.toMutableMap()
            
            // Add/Replace headers
            modifyAction.modifyHeaders?.let { headers ->
                for ((key, value) in headers) {
                    newHeaders[key] = value
                }
            }
            
            // Remove specific headers
            modifyAction.removeHeaders?.let { headerNames ->
                for (headerName in headerNames) {
                    newHeaders.remove(headerName)
                }
            }
            
            // Search & Replace in headers
            modifyAction.searchReplaceHeaders?.forEach { sr ->
                for ((key, value) in newHeaders.entries.toList()) {
                    val newValue = applySearchReplace(value, sr)
                    if (newValue != value) {
                        newHeaders[key] = newValue
                    }
                }
            }
            
            // Process body modifications
            val newBody = when {
                modifyAction.replaceBody != null -> {
                    modifyAction.replaceBody.toByteArray()
                }
                modifyAction.searchReplaceBody != null && request.body != null -> {
                    var bodyText = request.body.toString(Charsets.UTF_8)
                    modifyAction.searchReplaceBody.forEach { sr ->
                        bodyText = applySearchReplace(bodyText, sr)
                    }
                    bodyText.toByteArray()
                }
                else -> request.body
            }
            
            return request.copy(headers = newHeaders, body = newBody)
        }
    }
    
    inner class BackendHandler(
        private val frontendCtx: ChannelHandlerContext,
        private val requestId: Long,
        private val matchingRules: List<ProxyRule>
    ) : SimpleChannelInboundHandler<FullHttpResponse>() {
        
        override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse) {
            msg.retain()
            
            try {
                val headers = mutableMapOf<String, String>()
                for (header in msg.headers()) {
                    headers[header.key] = header.value
                }
                
                val bodyBytes = if (msg.content().readableBytes() > 0) {
                    ByteArray(msg.content().readableBytes()).also {
                        msg.content().getBytes(0, it)
                    }
                } else null
                
                var response = AppHttpResponse(
                    requestId = requestId,
                    timestamp = System.currentTimeMillis(),
                    statusCode = msg.status().code(),
                    statusMessage = msg.status().reasonPhrase(),
                    headers = headers,
                    body = bodyBytes
                )
                
                // Apply response modification rules
                for (rule in matchingRules) {
                    if (rule.action == RuleAction.MODIFY) {
                        rule.modifyResponse?.let { modifyAction ->
                            val modifiedResponse = applyResponseModifications(response, modifyAction)
                            response = modifiedResponse.copy(modified = true)
                        }
                    }
                }
                
                listener.onResponseReceived(requestId, response)
                
                // Build response to client
                val clientResponse = DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(response.statusCode),
                    if (response.body != null) Unpooled.copiedBuffer(response.body) else Unpooled.EMPTY_BUFFER
                )
                
                // Copy response headers (excluding certain headers)
                for ((key, value) in response.headers) {
                    if (!key.equals("Transfer-Encoding", ignoreCase = true)) {
                        clientResponse.headers().set(key, value)
                    }
                }
                
                // Set content length
                clientResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, clientResponse.content().readableBytes())
                clientResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                
                Log.d(TAG, "Sending response to client: ${response.statusCode} (${clientResponse.content().readableBytes()} bytes)")
                
                frontendCtx.writeAndFlush(clientResponse).addListener(ChannelFutureListener.CLOSE)
                ctx.close()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error processing response", e)
                frontendCtx.close()
                ctx.close()
            } finally {
                ReferenceCountUtil.release(msg)
            }
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Log.e(TAG, "Exception in backend handler", cause)
            
            val errorResponse = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus.BAD_GATEWAY,
                Unpooled.copiedBuffer("Backend Error: ${cause.message}", StandardCharsets.UTF_8)
            )
            errorResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, errorResponse.content().readableBytes())
            
            frontendCtx.writeAndFlush(errorResponse).addListener(ChannelFutureListener.CLOSE)
            ctx.close()
        }
        
        private fun applyResponseModifications(response: AppHttpResponse, modifyAction: ModifyAction): AppHttpResponse {
            val newHeaders = response.headers.toMutableMap()
            
            // Add/Replace headers
            modifyAction.modifyHeaders?.let { headers ->
                for ((key, value) in headers) {
                    newHeaders[key] = value
                }
            }
            
            // Remove specific headers
            modifyAction.removeHeaders?.let { headerNames ->
                for (headerName in headerNames) {
                    newHeaders.remove(headerName)
                }
            }
            
            // Search & Replace in headers
            modifyAction.searchReplaceHeaders?.forEach { sr ->
                for ((key, value) in newHeaders.entries.toList()) {
                    val newValue = applySearchReplace(value, sr)
                    if (newValue != value) {
                        newHeaders[key] = newValue
                    }
                }
            }
            
            // Process body modifications
            val newBody = when {
                modifyAction.replaceBody != null -> {
                    modifyAction.replaceBody.toByteArray()
                }
                modifyAction.searchReplaceBody != null && response.body != null -> {
                    var bodyText = response.body.toString(Charsets.UTF_8)
                    modifyAction.searchReplaceBody.forEach { sr ->
                        bodyText = applySearchReplace(bodyText, sr)
                    }
                    bodyText.toByteArray()
                }
                else -> response.body
            }
            
            return response.copy(headers = newHeaders, body = newBody)
        }
    }
    
    private fun applySearchReplace(text: String, sr: SearchReplace): String {
        return if (sr.useRegex) {
            try {
                text.replace(Regex(sr.search), sr.replace)
            } catch (e: Exception) {
                Log.e(TAG, "Invalid regex: ${sr.search}", e)
                text
            }
        } else {
            text.replace(sr.search, sr.replace)
        }
    }
    
    companion object {
        private const val TAG = "ProxyServerV2"
    }
}
