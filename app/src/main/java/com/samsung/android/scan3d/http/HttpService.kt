package com.samsung.android.scan3d.http

import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import java.io.OutputStream

class HttpService {
    lateinit var engine: NettyApplicationEngine
    var channel = Channel<ByteArray>(10) // 增加缓冲区大小
    
    fun producer(): suspend OutputStream.() -> Unit = {
        val outputStream = this
        try {
            Log.i("HttpService", "启动MJPEG流生产者")
            
            // 处理图像帧
            channel.consumeEach { frameData ->
                try {
                    Log.d("HttpService", "处理帧数据: ${frameData.size} 字节")
                    
                    // 发送帧边界
                    outputStream.write("--FRAME\r\n".toByteArray())
                    outputStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                    outputStream.write("Content-Length: ${frameData.size}\r\n".toByteArray())
                    outputStream.write("\r\n".toByteArray())
                    
                    // 发送图像数据
                    outputStream.write(frameData)
                    outputStream.write("\r\n".toByteArray())
                    outputStream.flush()
                    
                    Log.d("HttpService", "帧已发送: ${frameData.size} 字节")
                    
                } catch (e: Exception) {
                    Log.e("HttpService", "发送帧时出错", e)
                    return@consumeEach
                }
            }
            
        } catch (e: Exception) {
            Log.e("HttpService", "生产者出错", e)
        } finally {
            Log.i("HttpService", "生产者结束")
        }
    }
    
    public fun main() {
        try {
            Log.i("HttpService", "Starting HTTP server on port 8080")
            
            engine = embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
                routing {
                    get("/cam") {
                        Log.i("HttpService", "Status request received")
                        call.respondText("RemoteCam Server Running", ContentType.Text.Plain)
                    }
                    
                    get("/cam.mjpeg") {
                        Log.i("HttpService", "收到MJPEG请求来自: ${call.request.local.remoteHost}")
                        try {
                            call.respondOutputStream(
                                ContentType.parse("multipart/x-mixed-replace; boundary=FRAME"),
                                HttpStatusCode.OK
                            ) {
                                Log.i("HttpService", "开始MJPEG流传输")
                                producer().invoke(this)
                            }
                        } catch (e: Exception) {
                            Log.e("HttpService", "处理MJPEG请求时出错", e)
                            try {
                                call.respondText("Error: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                            } catch (e2: Exception) {
                                Log.e("HttpService", "发送错误响应失败", e2)
                            }
                        }
                    }
                    
                    get("/") {
                        Log.i("HttpService", "Root request received")
                        call.respondText(
                            """
                            <html>
                            <head><title>RemoteCam</title></head>
                            <body>
                                <h1>RemoteCam Server</h1>
                                <p>Status: Server is running</p>
                                <p>Stream URL: <a href="/cam.mjpeg">http://[YOUR_IP]:8080/cam.mjpeg</a></p>
                                <p>Test URL: <a href="/cam">http://[YOUR_IP]:8080/cam</a></p>
                                <hr>
                                <h2>Live Stream</h2>
                                <img src="/cam.mjpeg" alt="Camera Stream" style="max-width: 100%; border: 1px solid #ccc;">
                                <p><small>If no image appears, make sure streaming is enabled in the app.</small></p>
                            </body>
                            </html>
                            """.trimIndent(),
                            ContentType.Text.Html
                        )
                    }
                }
            }
            
            engine.start(wait = false)
            Log.i("HttpService", "HTTP server started successfully on 0.0.0.0:8080")
            
        } catch (e: Exception) {
            Log.e("HttpService", "Error starting HTTP server", e)
            throw e // 重新抛出异常，让调用者知道启动失败
        }
    }
    
    fun stop() {
        try {
            Log.i("HttpService", "Stopping HTTP server...")
            
            // 先关闭channel，停止新数据流入
            if (!channel.isClosedForSend) {
                channel.close()
                Log.i("HttpService", "Channel closed")
            }
            
            // 停止HTTP服务器
            if (::engine.isInitialized) {
                engine.stop(1000, 2000)
                Log.i("HttpService", "HTTP server stopped successfully")
            } else {
                Log.w("HttpService", "Engine not initialized, nothing to stop")
            }
            
        } catch (e: Exception) {
            Log.e("HttpService", "Error stopping HTTP server", e)
        }
    }
}