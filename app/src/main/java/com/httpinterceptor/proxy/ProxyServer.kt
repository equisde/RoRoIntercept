package com.httpinterceptor.proxy

import android.util.Log
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.*
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

class ProxyServer(private val port: Int, private val listener: ProxyListener) {
    
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channel: Channel? = null
    private val requestCache = ConcurrentHashMap<Long, com.httpinterceptor.model.HttpRequest>()
    
    interface ProxyListener {
        fun onRequestReceived(request: com.httpinterceptor.model.HttpRequest)
        fun onResponseReceived(requestId: Long, response: com.httpinterceptor.model.HttpResponse)
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
                
                channel = bootstrap.bind(port).sync().channel()
                Log.d("ProxyServer", "Proxy started on port $port")
                
                channel?.closeFuture()?.sync()
            } catch (e: Exception) {
                Log.e("ProxyServer", "Error starting proxy", e)
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
        Log.d("ProxyServer", "Proxy stopped")
    }
    
    inner class ProxyInitializer : ChannelInitializer<SocketChannel>() {
        override fun initChannel(ch: SocketChannel) {
            ch.pipeline().addLast(
                HttpServerCodec(),
                HttpObjectAggregator(10 * 1024 * 1024),
                ProxyHandler()
            )
        }
    }
    
    inner class ProxyHandler : SimpleChannelInboundHandler<FullHttpRequest>() {
        
        override fun channelRead0(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            if (msg.method() == HttpMethod.CONNECT) {
                handleConnect(ctx, msg)
            } else {
                handleRequest(ctx, msg)
            }
        }
        
        private fun handleConnect(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            val response = DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1,
                HttpResponseStatus(200, "Connection Established")
            )
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
        }
        
        private fun handleRequest(ctx: ChannelHandlerContext, msg: FullHttpRequest) {
            val requestId = System.currentTimeMillis()
            
            val headers = mutableMapOf<String, String>()
            msg.headers().forEach { headers[it.key] = it.value }
            
            val body = if (msg.content().readableBytes() > 0) {
                val bytes = ByteArray(msg.content().readableBytes())
                msg.content().readBytes(bytes)
                bytes
            } else null
            
            val uri = msg.uri()
            val host = headers["Host"] ?: "unknown"
            
            val request = com.httpinterceptor.model.HttpRequest(
                id = requestId,
                timestamp = System.currentTimeMillis(),
                method = msg.method().name(),
                url = "http://$host$uri",
                host = host,
                path = uri,
                headers = headers,
                body = body
            )
            
            requestCache[requestId] = request
            listener.onRequestReceived(request)
            
            // Forward request (simplified - in production use full HTTP client)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = forwardRequest(request)
                    request.response = response
                    listener.onResponseReceived(requestId, response)
                    
                    val httpResponse = DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.valueOf(response.statusCode)
                    )
                    
                    response.headers.forEach { (k, v) ->
                        httpResponse.headers().set(k, v)
                    }
                    
                    response.body?.let {
                        httpResponse.content().writeBytes(it)
                    }
                    
                    ctx.writeAndFlush(httpResponse).addListener(ChannelFutureListener.CLOSE)
                } catch (e: Exception) {
                    Log.e("ProxyHandler", "Error forwarding request", e)
                    val errorResponse = DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1,
                        HttpResponseStatus.BAD_GATEWAY
                    )
                    ctx.writeAndFlush(errorResponse).addListener(ChannelFutureListener.CLOSE)
                }
            }
        }
        
        private suspend fun forwardRequest(request: com.httpinterceptor.model.HttpRequest): com.httpinterceptor.model.HttpResponse {
            // Simplified - real implementation would use HttpClient
            return com.httpinterceptor.model.HttpResponse(
                statusCode = 200,
                statusMessage = "OK",
                headers = mapOf("Content-Type" to "text/plain"),
                body = "Response intercepted by proxy".toByteArray(),
                timestamp = System.currentTimeMillis()
            )
        }
        
        override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
            Log.e("ProxyHandler", "Exception in proxy handler", cause)
            ctx.close()
        }
    }
}
