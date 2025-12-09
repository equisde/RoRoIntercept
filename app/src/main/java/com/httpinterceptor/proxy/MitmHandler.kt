package com.httpinterceptor.proxy

import android.util.Log
import com.httpinterceptor.model.HttpRequest as AppHttpRequest
import com.httpinterceptor.model.HttpResponse as AppHttpResponse
import com.httpinterceptor.utils.CertificateManager
import io.netty.bootstrap.Bootstrap
import io.netty.buffer.Unpooled
import io.netty.channel.*
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.handler.codec.http.*
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.nio.charset.StandardCharsets

/**
 * Handler for MITM (Man-in-the-Middle) HTTPS interception
 * This handler:
 * 1. Receives decrypted HTTPS requests from the client
 * 2. Forwards them to the real server over SSL
 * 3. Returns the response back to the client
 */
class MitmHandler(
    private val targetHost: String,
    private val targetPort: Int,
    private val listener: SimpleProxyServer.ProxyListener
) : SimpleChannelInboundHandler<FullHttpRequest>() {
    
    companion object {
        private const val TAG = "MitmHandler"
    }
    
    override fun channelRead0(ctx: ChannelHandlerContext, request: FullHttpRequest) {
        val requestId = System.currentTimeMillis()
        
        Log.d(TAG, "🔓 Decrypted HTTPS: ${request.method()} https://$targetHost${request.uri()}")
        listener.onError("🔓 MITM: ${request.method()} https://$targetHost${request.uri()}")
        
        try {
            // Create app request object for logging
            val appRequest = AppHttpRequest(
                id = requestId,
                timestamp = requestId,
                method = request.method().name(),
                url = "https://$targetHost${request.uri()}",
                host = targetHost,
                path = request.uri(),
                headers = request.headers().associate { it.key to it.value },
                body = if (request.content().isReadable) {
                    ByteArray(request.content().readableBytes()).also {
                        request.content().getBytes(request.content().readerIndex(), it)
                    }
                } else null
            )
            
            listener.onRequestReceived(appRequest)
            
            // Forward to real server
            forwardToServer(ctx, request, requestId)
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error processing request: ${e.message}", e)
            listener.onError("❌ Error: ${e.message}")
            sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.message)
        }
    }
    
    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        Log.e(TAG, "❌ MITM error: ${cause.message}", cause)
        listener.onError("❌ MITM error: ${cause.message}")
        ctx.close()
    }
    
    private fun forwardToServer(ctx: ChannelHandlerContext, clientRequest: FullHttpRequest, requestId: Long) {
        try {
            // Create SSL context for connecting to real server
            val clientSslContext = SslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build()
            
            val bootstrap = Bootstrap()
            bootstrap.group(ctx.channel().eventLoop())
                .channel(NioSocketChannel::class.java)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 30000)
                .handler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(ch: SocketChannel) {
                        val pipeline = ch.pipeline()
                        
                        // Add SSL handler for outgoing connection
                        pipeline.addLast("client-ssl", clientSslContext.newHandler(ch.alloc(), targetHost, targetPort))
                        
                        // Add HTTP codecs
                        pipeline.addLast("client-http-codec", HttpClientCodec())
                        pipeline.addLast("client-aggregator", HttpObjectAggregator(50 * 1024 * 1024))
                        
                        // Add response handler
                        pipeline.addLast("client-handler", object : SimpleChannelInboundHandler<FullHttpResponse>() {
                            override fun channelRead0(clientCtx: ChannelHandlerContext, response: FullHttpResponse) {
                                Log.d(TAG, "📩 Response: ${response.status()} from $targetHost")
                                listener.onError("📩 ${response.status()} from $targetHost")
                                
                                try {
                                    // Create app response for logging
                                    val appResponse = AppHttpResponse(
                                        requestId = requestId,
                                        statusCode = response.status().code(),
                                        statusMessage = response.status().reasonPhrase(),
                                        headers = response.headers().associate { it.key to it.value },
                                        body = if (response.content().isReadable) {
                                            ByteArray(response.content().readableBytes()).also {
                                                response.content().getBytes(response.content().readerIndex(), it)
                                            }
                                        } else null,
                                        timestamp = System.currentTimeMillis()
                                    )
                                    
                                    listener.onResponseReceived(requestId, appResponse)
                                    
                                    // Forward response back to client
                                    ctx.writeAndFlush(response.retain()).addListener(ChannelFutureListener { future ->
                                        if (!future.isSuccess) {
                                            Log.e(TAG, "❌ Failed to send response to client")
                                            ctx.close()
                                        }
                                    })
                                    
                                } catch (e: Exception) {
                                    Log.e(TAG, "❌ Error handling response: ${e.message}", e)
                                    ctx.close()
                                }
                            }
                            
                            override fun exceptionCaught(clientCtx: ChannelHandlerContext, cause: Throwable) {
                                Log.e(TAG, "❌ Server connection error: ${cause.message}", cause)
                                listener.onError("❌ Server error: ${cause.message}")
                                clientCtx.close()
                                sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, "Server error: ${cause.message}")
                            }
                        })
                    }
                })
            
            // Connect to real server
            bootstrap.connect(targetHost, targetPort).addListener { connectFuture: io.netty.util.concurrent.Future<in Void?> ->
                if (connectFuture.isSuccess) {
                    val serverChannel = (connectFuture as ChannelFuture).channel()
                    
                    Log.d(TAG, "⏩ Connected to $targetHost:$targetPort, forwarding request")
                    
                    // Prepare request for forwarding
                    clientRequest.retain()
                    clientRequest.headers().set(HttpHeaderNames.HOST, targetHost)
                    clientRequest.headers().remove("Proxy-Connection")
                    
                    // Send request to server
                    serverChannel.writeAndFlush(clientRequest).addListener { writeFuture: io.netty.util.concurrent.Future<in Void?> ->
                        if (!writeFuture.isSuccess) {
                            Log.e(TAG, "❌ Failed to forward request to server")
                            serverChannel.close()
                            sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, "Failed to forward request")
                        }
                    }
                } else {
                    Log.e(TAG, "❌ Failed to connect to $targetHost:$targetPort")
                    listener.onError("❌ Connection failed to $targetHost:$targetPort")
                    sendErrorResponse(ctx, HttpResponseStatus.BAD_GATEWAY, "Cannot connect to $targetHost")
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Forward error: ${e.message}", e)
            listener.onError("❌ Forward error: ${e.message}")
            sendErrorResponse(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, e.message)
        }
    }
    
    private fun sendErrorResponse(ctx: ChannelHandlerContext, status: HttpResponseStatus, message: String?) {
        val content = message ?: status.reasonPhrase()
        val response = DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            status,
            Unpooled.copiedBuffer("$status: $content", StandardCharsets.UTF_8)
        )
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }
}
