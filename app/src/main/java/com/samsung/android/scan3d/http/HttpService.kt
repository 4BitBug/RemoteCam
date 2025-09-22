package com.samsung.android.scan3d.http

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders // Changed to single import
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import java.io.OutputStream

class HttpService(private val context: Context) {
    lateinit var engine: NettyApplicationEngine
    var channel = Channel<ByteArray>(10) // Increased buffer size
    private lateinit var prefs: SharedPreferences
    private var currentPassword = "password" // Default password

    companion object {
        const val PREFS_NAME = "RemoteCamHttpPrefs"
        const val KEY_HTTP_PASSWORD = "http_password"
    }

    init {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentPassword = prefs.getString(KEY_HTTP_PASSWORD, "password") ?: "password"
        Log.i("HTTP_SERVICE_DEBUG", "HttpService initialized. Loaded password: '$currentPassword'")
    }

    fun changePassword(newPasswordValue: String): Boolean {
        if (newPasswordValue.isBlank()) {
            Log.w("HTTP_SERVICE_DEBUG", "changePassword: Attempted to set an empty or blank password.")
            return false
        }
        prefs.edit().putString(KEY_HTTP_PASSWORD, newPasswordValue).apply()
        currentPassword = newPasswordValue
        Log.i("HTTP_SERVICE_DEBUG", "changePassword: Password changed successfully to '$newPasswordValue'")
        return true
    }

    fun getCurrentPassword(): String {
        return currentPassword
    }

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
            Log.i("HTTP_SERVICE_DEBUG", "main: Starting HTTP server on port 59713. Current channel: $channel")

            engine = embeddedServer(Netty, port = 59713, host = "0.0.0.0") {
                install(DefaultHeaders) {
                    header("X-Content-Type-Options", "nosniff")      // Use string literal
                    header("X-Frame-Options", "DENY")                 // Use string literal
                    header("Referrer-Policy", "no-referrer")          // Use string literal
                    header("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self'; form-action 'self';") // Use string literal
                }
                routing {
                    get("/cam.mjpeg") {
                        val submittedPassword = call.request.queryParameters["password"]
                        if (submittedPassword == currentPassword) {
                            Log.i("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Client connected from ${call.request.local.remoteHost} with correct password. Using channel: $channel")
                            try {
                                call.respondOutputStream(
                                    ContentType.parse("multipart/x-mixed-replace; boundary=FRAME"),
                                    HttpStatusCode.OK
                                ) {
                                    Log.i("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Starting MJPEG stream transmission for client: $this. Using channel: $channel")
                                    producer().invoke(this)
                                }
                            } catch (e: Exception) {
                                Log.e("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Error processing request for ${call.request.local.remoteHost}", e)
                                if (call.response.status() == null) {
                                    try { call.respondText("Error: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError) } catch (e2: Exception) { Log.e("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Failed to send error response", e2) }
                                }
                            }
                        } else {
                            Log.w("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Unauthorized access attempt from ${call.request.local.remoteHost}.")
                            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing password.")
                        }
                    }

                    get("/") {
                        val loginFailed = call.request.queryParameters["loginFailed"] == "true"
                        Log.i("HTTP_SERVICE_DEBUG", "/: GET request for login page. Login failed: $loginFailed")
                        var htmlResponse = """
                            <html>
                            <head><title>Login - RemoteCam</title>
                            <style>
                                body { font-family: sans-serif; display: flex; flex-direction: column; justify-content: center; align-items: center; height: 100vh; margin: 0; background-color: #f0f0f0; }
                                form { background-color: #fff; padding: 20px; border-radius: 8px; box-shadow: 0 0 10px rgba(0,0,0,0.1); }
                                input[type="password"], input[type="submit"] { padding: 10px; margin-top: 5px; margin-bottom: 10px; border-radius: 4px; border: 1px solid #ccc; }
                                input[type="submit"] { background-color: #007bff; color: white; cursor: pointer; }
                                .error { color: red; margin-top: 10px; }
                            </style>
                            </head>
                            <body>
                                <form method="POST" action="/">
                                    Password: <input type="password" name="password" autofocus autocomplete="current-password">
                                    <input type="submit" value="Login">
                                </form>
                        """
                        if (loginFailed) {
                            htmlResponse += """<p class="error">Incorrect password. Please try again.</p>"""
                        }
                        htmlResponse += """
                            </body>
                            </html>
                         """
                        call.respondText(htmlResponse.trimIndent(), ContentType.Text.Html, HttpStatusCode.OK)
                    }

                    post("/") {
                        val parameters = call.receiveParameters()
                        val submittedPassword = parameters["password"]
                        Log.i("HTTP_SERVICE_DEBUG", "/: POST request received from ${call.request.local.remoteHost}.")

                        if (submittedPassword == currentPassword) {
                            Log.i("HTTP_SERVICE_DEBUG", "/: Correct password submitted. Serving stream page.")
                            call.respondText(
                                """
                                <html>
                                <head>
                                    <title>RemoteCam Stream</title>
                                    <style>
                                        body { margin:0; background-color:#000; display: flex; justify-content: center; align-items: center; height: 100vh; }
                                        img { max-width: 100%; max-height: 100%; object-fit: contain; }
                                    </style>
                                </head>
                                <body>
                                    <img src="/cam.mjpeg?password=$currentPassword" alt="Camera Stream">
                                </body>
                                </html>
                                """.trimIndent(),
                                ContentType.Text.Html
                            )
                        } else {
                            Log.w("HTTP_SERVICE_DEBUG", "/: Incorrect password submitted. Redirecting to login page with error.")
                            call.respondRedirect("/?loginFailed=true", permanent = false)
                        }
                    }
                }
            }

            engine.start(wait = false)
            Log.i("HTTP_SERVICE_DEBUG", "main: HTTP server started successfully on 0.0.0.0:59713. Engine: $engine")

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

            if (::engine.isInitialized) {
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
