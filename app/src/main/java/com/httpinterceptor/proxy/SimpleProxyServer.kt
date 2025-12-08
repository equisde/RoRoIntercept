package com.httpinterceptor.proxy

import android.util.Log
import com.httpinterceptor.model.HttpRequest as AppHttpRequest
import com.httpinterceptor.model.HttpResponse as AppHttpResponse
import com.httpinterceptor.utils.CertificateManager
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
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

class SimpleProxyServer(
    private val port: Int,
    private val listener: ProxyListener,
    private val certManager: CertificateManager
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
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.AUTO_READ, false)
                
                channel = bootstrap.bind("0.0.0.0", port).sync().channel()
                Log.d(TAG, "✅ Proxy started on 0.0.0.0:$port")
                
                channel?.closeFuture()?.sync()
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting proxy", e)
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
        Log.d(TAG, "⏹️ Proxy stopped")
    }
    
    inner class ProxyInitializer : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            ch.pipeline().addLast(HttpServerCodec())
            ch.pipeline().addLast(HttpObjectAggregator(10 * 1024 * 1024))
            ch.pipeline().addLast(ProxyHandler())
        }
    }
    
    inner class ProxyHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
        
        private var targetHost: String? = null
        private var targetPort: Int = 80
        
        override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
            when {
                request.method() == HttpMethod.CONNECT -> handleConnect(ctx, request)
                else -> handleHttpRequest(ctx, request)
            }
        }
        
        override fun channelReadComplete(ctx: ChannelHandlerContext) {
            ctx.flush()
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Log.e(TAG, "Exception: ${cause.message}")
            ctx.close()
        }
        
        private fun handleConnect(ctx: ChannelHandlerContext, request: FullHttpRequest) {
            val uri = request.uri()
            val parts = uri.split(":")
            val host = parts[0]
            val port = parts.getOrNull(1)?.toIntOrNull() ?: 443
            
            targetHost = host
            targetPort = port
            
            Log.d(TAG, "🔐 CONNECT: $host:$port")
            
            // Send Connection Established
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
                    
                    // Add SSL
                    setupSSL(ctx, host)
                } else {
                    ctx.close()
                }
            }
        }
        
        private fun setupSSL(ctx: ChannelHandlerContext, host: String) {
            try {
                val (cert, key) = certManager.generateServerCertificate(host)
                val sslContext = SslContextBuilder.forServer(key, cert).build()
                val sslHandler = sslContext.newHandler(ctx.alloc())
                
                ctx.pipeline().addFirst("ssl", sslHandler)
                
                sslHandler.handshakeFuture().addListener { future ->
                    if (future.isSuccess) {
                        Log.d(TAG, "✅ SSL handshake OK: $host")
                        ctx.pipeline().addLast(HttpServerCodec())
                        ctx.pipeline().addLast(HttpObjectAggregator(10 * 1024 * 1024))
                        ctx.pipeline().addLast(ProxyHandler())
                        ctx.read()
                    } else {
                        Log.e(TAG, "❌ SSL handshake failed: $host")
                        ctx.close()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ SSL setup error: ${e.message}")
                ctx.close()
            }
        }
        
        private fun handleHttpRequest(ctx: ChannelHandlerContext, request: FullHttpRequest) {
            request.retain()
            
            val requestId = System.currentTimeMillis()
            val isHttps = ctx.pipeline().get("ssl") != null
            
            try {
                // Parse request details
                val headers = mutableMapOf<String, String>()
                request.headers().forEach { headers[it.key] = it.value }
                
                val body = if (request.content().readableBytes() > 0) {
                    ByteArray(request.content().readableBytes()).also {
                        request.content().getBytes(0, it)
                    }
                } else null
                
                val host = targetHost ?: headers["Host"] ?: extractHost(request.uri())
                val uri = if (request.uri().startsWith("http")) {
                    URI(request.uri())
                } else {
                    URI("${if (isHttps) "https" else "http"}://$host${request.uri()}")
                }
                
                val appRequest = AppHttpRequest(
                    id = requestId,
                    timestamp = System.currentTimeMillis(),
                    method = request.method().name(),
                    url = uri.toString(),
                    host = host,
                    path = request.uri(),
                    headers = headers,
                    body = body
                )
                
                Log.d(TAG, "📤 ${appRequest.method} ${appRequest.url}")
                listener.onRequestReceived(appRequest)
                requestCache[requestId] = appRequest
                
                // Forward to target
                forwardToTarget(ctx, request, uri, requestId)
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Request error: ${e.message}", e)
                sendErrorResponse(ctx, HttpResponseStatus.BAD_REQUEST, e.message ?: "Error")
                request.release()
            }
        }
        
        private fun forwardToTarget(
            clientCtx: ChannelHandlerContext,
            clientRequest: FullHttpRequest,
            uri: URI,
            requestId: Long
        ) {
            val targetHost = uri.host
            val targetPort = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
            val isHttps = uri.scheme == "https"
            
            val bootstrap = Bootstrap()
            bootstrap.group(clientCtx.channel().eventLoop())
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.AUTO_READ, false)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        if (isHttps) {
                            val sslCtx = SslContextBuilder.forClient()
                                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                .build()
                            ch.pipeline().addLast(sslCtx.newHandler(ch.alloc(), targetHost, targetPort))
                        }
                        ch.pipeline().addLast(HttpClientCodec())
                        ch.pipeline().addLast(HttpObjectAggregator(10 * 1024 * 1024))
                        ch.pipeline().addLast(TargetHandler(clientCtx, requestId))
                    }
                })
            
            val connectFuture = bootstrap.connect(targetHost, targetPort)
            connectFuture.addListener { future: ChannelFuture ->
                if (future.isSuccess) {
                    val targetChannel = future.channel()
                    
                    // Build request to target
                    val path = uri.rawPath + (if (uri.rawQuery != null) "?${uri.rawQuery}" else "")
                    val targetRequest = DefaultFullHttpRequest(
                        HttpVersion.HTTP_1_1,
                        clientRequest.method(),
                        if (path.isEmpty()) "/" else path
                    )
                    
                    // Copy headers
                    clientRequest.headers().forEach { header ->
                        if (!header.key.equals("Proxy-Connection", true) &&
                            !header.key.equals("Proxy-Authorization", true)) {
                            targetRequest.headers().set(header.key, header.value)
                        }
                    }
                    
                    targetRequest.headers().set(HttpHeaderNames.HOST, targetHost)
                    targetRequest.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                    
                    // Copy body
                    if (clientRequest.content().readableBytes() > 0) {
                        targetRequest.content().writeBytes(clientRequest.content().copy())
                        targetRequest.headers().set(HttpHeaderNames.CONTENT_LENGTH, targetRequest.content().readableBytes())
                    }
                    
                    Log.d(TAG, "🔄 Forward: ${targetRequest.method()} $targetHost$path")
                    targetChannel.writeAndFlush(targetRequest).addListener {
                        targetChannel.read()
                    }
                    clientRequest.release()
                    
                } else {
                    Log.e(TAG, "❌ Connect failed: $targetHost:$targetPort - ${future.cause().message}")
                    sendErrorResponse(clientCtx, HttpResponseStatus.BAD_GATEWAY, "Cannot connect to target")
                    clientRequest.release()
                }
            }
        }
        
        private fun sendErrorResponse(ctx: ChannelHandlerContext, status: HttpResponseStatus, message: String) {
            val response = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                status,
                Unpooled.copiedBuffer(message, StandardCharsets.UTF_8)
            )
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain")
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        }
        
        private fun extractHost(uri: String): String {
            return try {
                when {
                    uri.startsWith("http://") || uri.startsWith("https://") -> URI(uri).host
                    else -> uri.substringBefore("/").substringBefore(":")
                }
            } catch (e: Exception) {
                "unknown"
            }
        }
    }
    
    inner class TargetHandler(
        private val clientCtx: ChannelHandlerContext,
        private val requestId: Long
    ) : SimpleChannelInboundHandler<FullHttpResponse>() {
        
        override fun channelRead0(ctx: ChannelHandlerContext, targetResponse: FullHttpResponse) {
            targetResponse.retain()
            
            try {
                val headers = mutableMapOf<String, String>()
                targetResponse.headers().forEach { headers[it.key] = it.value }
                
                val body = if (targetResponse.content().readableBytes() > 0) {
                    ByteArray(targetResponse.content().readableBytes()).also {
                        targetResponse.content().getBytes(0, it)
                    }
                } else null
                
                val appResponse = AppHttpResponse(
                    requestId = requestId,
                    timestamp = System.currentTimeMillis(),
                    statusCode = targetResponse.status().code(),
                    statusMessage = targetResponse.status().reasonPhrase(),
                    headers = headers,
                    body = body
                )
                
                Log.d(TAG, "📥 ${appResponse.statusCode} (${body?.size ?: 0} bytes)")
                listener.onResponseReceived(requestId, appResponse)
                
                // Send to client
                val clientResponse = DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    targetResponse.status(),
                    if (body != null) Unpooled.wrappedBuffer(body) else Unpooled.EMPTY_BUFFER
                )
                
                targetResponse.headers().forEach { header ->
                    if (!header.key.equals("Transfer-Encoding", true)) {
                        clientResponse.headers().set(header.key, header.value)
                    }
                }
                
                clientResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, clientResponse.content().readableBytes())
                clientResponse.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE)
                
                clientCtx.writeAndFlush(clientResponse).addListener(ChannelFutureListener.CLOSE)
                ctx.close()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Response error: ${e.message}")
                clientCtx.close()
                ctx.close()
            } finally {
                targetResponse.release()
            }
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Log.e(TAG, "❌ Target exception: ${cause.message}")
            clientCtx.close()
            ctx.close()
        }
    }
    
    companion object {
        private const val TAG = "SimpleProxyServer"
    }
}
