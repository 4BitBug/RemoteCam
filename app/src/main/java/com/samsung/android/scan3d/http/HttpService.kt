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
    var channel = Channel<ByteArray>(10) // Increased buffer size
    
    fun producer(): suspend OutputStream.() -> Unit = {
        val outputStream = this
        try {
            Log.i("HTTP_SERVICE_DEBUG", "Producer: Starting. Waiting for frames on channel: $channel. OutputStream: $outputStream")
            channel.consumeEach { frameData ->
                Log.d("HTTP_SERVICE_DEBUG", "Producer: Received frame from channel. Size: ${frameData.size} bytes. Writing to OutputStream.")
                try {
                    outputStream.write("--FRAME\r\n".toByteArray())
                    outputStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                    outputStream.write("Content-Length: ${frameData.size}\r\n".toByteArray())
                    outputStream.write("\r\n".toByteArray())
                    outputStream.write(frameData)
                    outputStream.write("\r\n".toByteArray())
                    outputStream.flush()
                    Log.d("HTTP_SERVICE_DEBUG", "Producer: Frame sent to client. Size: ${frameData.size}")
                } catch (e: Exception) {
                    Log.e("HTTP_SERVICE_DEBUG", "Producer: Error writing frame to outputStream for client: $outputStream", e)
                    return@consumeEach // Stop processing for this client if write fails
                }
            }
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            Log.w("HTTP_SERVICE_DEBUG", "Producer: Channel was closed while waiting for or receiving data. Client: $outputStream", e)
        } catch (e: Exception) {
            Log.e("HTTP_SERVICE_DEBUG", "Producer: Error in producer loop for client: $outputStream", e)
        } finally {
            Log.i("HTTP_SERVICE_DEBUG", "Producer: Finished for client: $outputStream. Closing its OutputStream.")
            try { outputStream.close() } catch (e: Exception) { 
                Log.w("HTTP_SERVICE_DEBUG", "Producer: Error closing outputStream for client: $outputStream", e)
            }
        }
    }
    
    public fun main() {
        try {
            Log.i("HTTP_SERVICE_DEBUG", "main: Starting HTTP server on port 8080. Current channel: $channel")
            
            engine = embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
                routing {
                    get("/cam") {
                        Log.i("HTTP_SERVICE_DEBUG", "/cam: Status request received from ${call.request.local.remoteHost}")
                        call.respondText("RemoteCam Server Running", ContentType.Text.Plain)
                    }
                    
                    get("/cam.mjpeg") {
                        Log.i("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Client connected from ${call.request.local.remoteHost}. Using channel: $channel")
                        try {
                            call.respondOutputStream(
                                ContentType.parse("multipart/x-mixed-replace; boundary=FRAME"),
                                HttpStatusCode.OK
                            ) {
                                // 'this' is the OutputStream for the specific client connection
                                Log.i("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Starting MJPEG stream transmission for client: $this. Using channel: $channel")
                                producer().invoke(this) 
                            }
                        } catch (e: Exception) {
                            Log.e("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Error processing request for ${call.request.local.remoteHost}", e)
                            try {
                                if (call.response.status() == null) { // Avoid responding if already responded
                                    call.respondText("Error: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError)
                                }
                            } catch (e2: Exception) {
                                Log.e("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Failed to send error response to ${call.request.local.remoteHost}", e2)
                            }
                        }
                    }
                    
                    get("/") {
                        Log.i("HTTP_SERVICE_DEBUG", "/: Root request received from ${call.request.local.remoteHost}")
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
            Log.i("HTTP_SERVICE_DEBUG", "main: HTTP server started successfully on 0.0.0.0:8080. Engine: $engine")
            
        } catch (e: Exception) {
            Log.e("HTTP_SERVICE_DEBUG", "main: Error starting HTTP server", e)
            throw e 
        }
    }
    
    fun stop() {
        try {
            Log.i("HTTP_SERVICE_DEBUG", "stop: Stopping HTTP server. Current channel: $channel, Engine initialized: ${::engine.isInitialized}")
            
            if (!channel.isClosedForSend) {
                channel.close() 
                Log.i("HTTP_SERVICE_DEBUG", "stop: Channel closed. Cause: null (normal closure)")
            }
            
            if (::engine.isInitialized) { // Corrected this line
                engine.stop(1000, 2000) // Ktor's grace periods
                Log.i("HTTP_SERVICE_DEBUG", "stop: HTTP server stopped successfully.")
            } else {
                Log.w("HTTP_SERVICE_DEBUG", "stop: Engine not initialized, nothing to stop.")
            }
            
        } catch (e: Exception) {
            Log.e("HTTP_SERVICE_DEBUG", "stop: Error stopping HTTP server", e)
        }
    }
}