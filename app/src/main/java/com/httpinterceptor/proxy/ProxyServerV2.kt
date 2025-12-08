package com.httpinterceptor.proxy

import android.util.Log
import com.httpinterceptor.model.*
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
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
    private val requestCache = ConcurrentHashMap<Long, HttpRequest>()
    
    interface ProxyListener {
        fun onRequestReceived(request: HttpRequest)
        fun onResponseReceived(requestId: Long, response: HttpResponse)
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
        Log.d(TAG, "Proxy stopped")
    }
    
    inner class ProxyInitializer : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            ch.pipeline().addLast(
                HttpServerCodec(),
                HttpObjectAggregator(10 * 1024 * 1024),
                ProxyFrontendHandler()
            )
        }
    }
    
    inner class ProxyFrontendHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
        
        private var outboundChannel: Channel? = null
        
        override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            if (msg.method() == HttpMethod.CONNECT) {
                handleConnect(ctx, msg)
            } else {
                handleRequest(ctx, msg)
            }
        }
        
        private fun handleConnect(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            val hostAndPort = msg.uri().split(":")
            val host = hostAndPort[0]
            val port = hostAndPort.getOrNull(1)?.toIntOrNull() ?: 443
            
            Log.d(TAG, "CONNECT to $host:$port")
            
            // Send 200 Connection Established
            val response = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus(200, "Connection Established")
            )
            ctx.writeAndFlush(response).addListener { future ->
                if (future.isSuccess) {
                    // Remove HTTP handlers
                    ctx.pipeline().remove(HttpServerCodec::class.java)
                    ctx.pipeline().remove(HttpObjectAggregator::class.java)
                    ctx.pipeline().remove(this)
                    
                    // Add SSL handler with generated certificate
                    try {
                        val (cert, privateKey) = certManager.generateServerCertificate(host)
                        val sslContext = SslContextBuilder
                            .forServer(privateKey, cert)
                            .build()
                        
                        ctx.pipeline().addFirst("ssl", sslContext.newHandler(ctx.alloc()))
                        
                        // Add HTTP handlers again for HTTPS
                        ctx.pipeline().addLast(HttpServerCodec())
                        ctx.pipeline().addLast(HttpObjectAggregator(10 * 1024 * 1024))
                        ctx.pipeline().addLast(ProxyFrontendHandler())
                        
                        Log.d(TAG, "SSL handshake setup for $host")
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting up SSL", e)
                        ctx.close()
                    }
                } else {
                    ctx.close()
                }
            }
        }
        
        private fun handleRequest(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            val requestId = System.currentTimeMillis() + (0..999).random()
            
            val headers = mutableMapOf<String, String>()
            msg.headers().forEach { headers[it.key] = it.value }
            
            val bodyBytes = if (msg.content().readableBytes() > 0) {
                ByteArray(msg.content().readableBytes()).also {
                    msg.content().getBytes(0, it)
                }
            } else null
            
            val uri = msg.uri()
            val host = headers["Host"] ?: "unknown"
            val isHttps = ctx.pipeline().get("ssl") != null
            val scheme = if (isHttps) "https" else "http"
            
            var request = HttpRequest(
                id = requestId,
                timestamp = System.currentTimeMillis(),
                method = msg.method().name(),
                url = "$scheme://$host$uri",
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
                            HttpResponseStatus.FORBIDDEN
                        )
                        ctx.writeAndFlush(blockedResponse).addListener(ChannelFutureListener.CLOSE)
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
            
            // Forward request
            forwardRequest(ctx, request, msg, matchingRules)
        }
        
        private fun applyRequestModifications(request: HttpRequest, modifyAction: ModifyAction): HttpRequest {
            val newHeaders = request.headers.toMutableMap()
            
            // Add/Replace headers
            modifyAction.modifyHeaders?.forEach { (key, value) ->
                newHeaders[key] = value
            }
            
            // Remove specific headers
            modifyAction.removeHeaders?.forEach { headerName ->
                newHeaders.remove(headerName)
            }
            
            // Search & Replace in headers
            modifyAction.searchReplaceHeaders?.forEach { sr ->
                newHeaders.entries.forEach { entry ->
                    val newValue = applySearchReplace(entry.value, sr)
                    if (newValue != entry.value) {
                        newHeaders[entry.key] = newValue
                    }
                }
            }
            
            // Process body modifications
            val newBody = when {
                modifyAction.replaceBody != null -> {
                    // Complete replacement
                    modifyAction.replaceBody.toByteArray()
                }
                modifyAction.searchReplaceBody != null && request.body != null -> {
                    // Search & Replace in body
                    var bodyText = request.body.toString(Charsets.UTF_8)
                    modifyAction.searchReplaceBody.forEach { sr ->
                        bodyText = applySearchReplace(bodyText, sr)
                    }
                    bodyText.toByteArray()
                }
                modifyAction.modifyBody != null && request.body != null -> {
                    // Legacy: Remove by regex
                    val originalBody = request.body.toString(Charsets.UTF_8)
                    originalBody.replace(Regex(modifyAction.modifyBody), "").toByteArray()
                }
                else -> request.body
            }
            
            return request.copy(headers = newHeaders, body = newBody)
        }
        
        private fun applySearchReplace(text: String, sr: SearchReplace): String {
            return if (sr.useRegex) {
                // Use regex
                val options = if (sr.caseSensitive) setOf<RegexOption>() else setOf(RegexOption.IGNORE_CASE)
                val regex = Regex(sr.search, options)
                if (sr.replaceAll) {
                    regex.replace(text, sr.replace)
                } else {
                    regex.replaceFirst(text, sr.replace)
                }
            } else {
                // Simple text replacement
                if (sr.replaceAll) {
                    text.replace(sr.search, sr.replace, ignoreCase = !sr.caseSensitive)
                } else {
                    text.replaceFirst(sr.search, sr.replace, ignoreCase = !sr.caseSensitive)
                }
            }
        }
        
        private fun forwardRequest(
            ctx: ChannelHandlerContext,
            request: HttpRequest,
            originalMsg: FullHttpRequest,
            matchingRules: List<ProxyRule>
        ) {
            val bootstrap = Bootstrap()
            bootstrap.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.AUTO_READ, false)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        val isHttps = request.url.startsWith("https")
                        
                        if (isHttps) {
                            val sslContext = SslContextBuilder.forClient()
                                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                .build()
                            ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), request.host, 443))
                        }
                        
                        ch.pipeline().addLast(HttpClientCodec())
                        ch.pipeline().addLast(HttpObjectAggregator(10 * 1024 * 1024))
                        ch.pipeline().addLast(ProxyBackendHandler(ctx, request, matchingRules))
                    }
                })
            
            val port = if (request.url.startsWith("https")) 443 else 80
            val connectFuture = bootstrap.connect(request.host, port)
            
            outboundChannel = connectFuture.channel()
            connectFuture.addListener { future: ChannelFuture ->
                if (future.isSuccess) {
                    // Build modified request
                    val modifiedRequest = DefaultFullHttpRequest(
                        originalMsg.protocolVersion(),
                        originalMsg.method(),
                        request.path
                    )
                    
                    request.headers.forEach { (key, value) ->
                        modifiedRequest.headers().set(key, value)
                    }
                    
                    request.body?.let {
                        modifiedRequest.content().writeBytes(it)
                        modifiedRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, it.size)
                    }
                    
                    future.channel().writeAndFlush(modifiedRequest)
                    future.channel().read()
                } else {
                    Log.e(TAG, "Failed to connect to ${request.host}", future.cause())
                    ctx.close()
                }
            }
        }
        
        override fun channelInactive(ctx: ChannelHandlerContext) {
            outboundChannel?.close()
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Log.e(TAG, "Exception in frontend handler", cause)
            ctx.close()
        }
    }
    
    inner class ProxyBackendHandler(
        private val inboundChannel: ChannelHandlerContext,
        private val request: HttpRequest,
        private val matchingRules: List<ProxyRule>
    ) : SimpleChannelInboundHandler<FullHttpResponse>() {
        
        override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpResponse) {
            val headers = mutableMapOf<String, String>()
            msg.headers().forEach { headers[it.key] = it.value }
            
            val bodyBytes = if (msg.content().readableBytes() > 0) {
                ByteArray(msg.content().readableBytes()).also {
                    msg.content().getBytes(0, it)
                }
            } else null
            
            var response = HttpResponse(
                statusCode = msg.status().code(),
                statusMessage = msg.status().reasonPhrase(),
                headers = headers,
                body = bodyBytes,
                timestamp = System.currentTimeMillis()
            )
            
            // Apply response modification rules
            for (rule in matchingRules) {
                if (rule.action == RuleAction.MODIFY) {
                    rule.modifyResponse?.let { modifyAction ->
                        response = applyResponseModifications(response, modifyAction)
                    }
                }
            }
            
            listener.onResponseReceived(request.id, response)
            
            // Send response back to client
            val clientResponse = DefaultFullHttpResponse(
                msg.protocolVersion(),
                msg.status()
            )
            
            response.headers.forEach { (key, value) ->
                clientResponse.headers().set(key, value)
            }
            
            response.body?.let {
                clientResponse.content().writeBytes(it)
                clientResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, it.size)
            }
            
            inboundChannel.writeAndFlush(clientResponse).addListener(ChannelFutureListener.CLOSE)
        }
        
        private fun applyResponseModifications(response: HttpResponse, modifyAction: ModifyAction): HttpResponse {
            val newHeaders = response.headers.toMutableMap()
            
            // Add/Replace headers
            modifyAction.modifyHeaders?.forEach { (key, value) ->
                newHeaders[key] = value
            }
            
            // Remove specific headers
            modifyAction.removeHeaders?.forEach { headerName ->
                newHeaders.remove(headerName)
            }
            
            // Search & Replace in headers
            modifyAction.searchReplaceHeaders?.forEach { sr ->
                newHeaders.entries.forEach { entry ->
                    val newValue = applySearchReplace(entry.value, sr)
                    if (newValue != entry.value) {
                        newHeaders[entry.key] = newValue
                    }
                }
            }
            
            // Process body modifications
            val newBody = when {
                modifyAction.replaceBody != null -> {
                    // Complete replacement
                    modifyAction.replaceBody.toByteArray()
                }
                modifyAction.searchReplaceBody != null && response.body != null -> {
                    // Search & Replace in body
                    var bodyText = response.body.toString(Charsets.UTF_8)
                    modifyAction.searchReplaceBody.forEach { sr ->
                        bodyText = applySearchReplace(bodyText, sr)
                    }
                    bodyText.toByteArray()
                }
                modifyAction.modifyBody != null && response.body != null -> {
                    // Legacy: Remove by regex
                    val originalBody = response.body.toString(Charsets.UTF_8)
                    originalBody.replace(Regex(modifyAction.modifyBody), "").toByteArray()
                }
                else -> response.body
            }
            
            return response.copy(headers = newHeaders, body = newBody)
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Log.e(TAG, "Exception in backend handler", cause)
            ctx.close()
        }
    }
    
    companion object {
        private const val TAG = "ProxyServerV2"
    }
}
