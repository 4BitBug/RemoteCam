package com.samsung.android.scan3d.http

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID

class HttpService(private val context: Context) {
    lateinit var engine: NettyApplicationEngine
    var channel = Channel<ByteArray>(10)
    private lateinit var prefs: SharedPreferences
    private var currentPasswordHash = "" // Will store salt:hash
    private var mjpegAccessToken: String? = null // Token for MJPEG stream access

    companion object {
        const val PREFS_NAME = "RemoteCamHttpPrefs"
        const val KEY_HTTP_PASSWORD_OLD = "http_password" // Old key for plain text password
        const val KEY_HTTP_PASSWORD_HASH = "http_password_hash" // New key for salt:hash
        const val KEY_IS_HASHED = "is_password_hashed"
        private const val DEFAULT_PASSWORD = "password"
    }

    // --- Password Hashing ---
    private fun generateSalt(): String {
        return UUID.randomUUID().toString()
    }

    private fun hashPassword(password: String, salt: String): String {
        val saltedPassword = salt + password
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(saltedPassword.toByteArray(Charsets.UTF_8))
        return hashBytes.joinToString("") { "%02x".format(it) } // Hex string
    }

    private fun verifyPassword(plainPassword: String, storedSaltAndHash: String): Boolean {
        if (!storedSaltAndHash.contains(":")) {
            Log.e("HTTP_SERVICE_DEBUG", "verifyPassword: Stored hash format is invalid.")
            return false // Invalid format
        }
        val parts = storedSaltAndHash.split(":", limit = 2)
        val salt = parts[0]
        val storedHash = parts[1]
        val newHash = hashPassword(plainPassword, salt)
        return newHash == storedHash
    }
    // --- End Password Hashing ---

    // --- MJPEG Access Token ---
    private fun generateMjpegAccessToken(): String {
        // Using UUID for simplicity. For higher security, consider a cryptographically secure random string.
        return UUID.randomUUID().toString()
    }
    // --- End MJPEG Access Token ---

    init {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedSaltAndHash = prefs.getString(KEY_HTTP_PASSWORD_HASH, null)
        val isHashed = prefs.getBoolean(KEY_IS_HASHED, false)

        if (storedSaltAndHash == null || !isHashed) {
            val oldPlainTextPassword = prefs.getString(KEY_HTTP_PASSWORD_OLD, null)
            val passwordToHash = oldPlainTextPassword ?: DEFAULT_PASSWORD

            val salt = generateSalt()
            val newHash = hashPassword(passwordToHash, salt)
            currentPasswordHash = "$salt:$newHash"

            prefs.edit {
                putString(KEY_HTTP_PASSWORD_HASH, currentPasswordHash)
                putBoolean(KEY_IS_HASHED, true)
                remove(KEY_HTTP_PASSWORD_OLD)
            }
            Log.i("HTTP_SERVICE_DEBUG", "HttpService initialized. Password (re)hashed and stored.")
        } else {
            currentPasswordHash = storedSaltAndHash
            Log.i("HTTP_SERVICE_DEBUG", "HttpService initialized. Loaded hashed password.")
        }
    }

    fun changePassword(newPasswordValue: String): Boolean {
        if (newPasswordValue.isBlank()) {
            Log.w("HTTP_SERVICE_DEBUG", "changePassword: Attempted to set an empty or blank password.")
            return false
        }
        val salt = generateSalt()
        val newHash = hashPassword(newPasswordValue, salt)
        currentPasswordHash = "$salt:$newHash"
        mjpegAccessToken = null // Invalidate any existing mjpeg token on password change

        prefs.edit {
            putString(KEY_HTTP_PASSWORD_HASH, currentPasswordHash)
            putBoolean(KEY_IS_HASHED, true)
            remove(KEY_HTTP_PASSWORD_OLD)
        }
        Log.i("HTTP_SERVICE_DEBUG", "changePassword: Password changed successfully. New hash stored.")
        return true
    }

    fun producer(): suspend OutputStream.() -> Unit = {
        val outputStream = this
        try {
            channel.consumeEach { frameData ->
                try {
                    outputStream.write("--FRAME\r\n".toByteArray())
                    outputStream.write("Content-Type: image/jpeg\r\n".toByteArray())
                    outputStream.write("Content-Length: ${frameData.size}\r\n".toByteArray())
                    outputStream.write("\r\n".toByteArray())
                    outputStream.write(frameData)
                    outputStream.write("\r\n".toByteArray())
                    outputStream.flush()
                } catch (e: Exception) {
                    Log.e("HTTP_SERVICE_DEBUG", "Producer: Error writing frame for client: $outputStream", e)
                    return@consumeEach
                }
            }
        } catch (e: kotlinx.coroutines.channels.ClosedReceiveChannelException) {
            Log.w("HTTP_SERVICE_DEBUG", "Producer: Channel closed for client: $outputStream", e)
        } catch (e: Exception) {
            Log.e("HTTP_SERVICE_DEBUG", "Producer: Error in producer loop for client: $outputStream", e)
        } finally {
            try { outputStream.close() } catch (e: Exception) { /* ignore */ }
        }
    }

    public fun main() {
        try {
            Log.i("HTTP_SERVICE_DEBUG", "main: Starting HTTP server on port 59713.")

            val CustomSecurityHeaders = createApplicationPlugin(name = "CustomSecurityHeaders") {
                onCallRespond {
                    call ->
                    call.response.headers.append("X-Content-Type-Options", "nosniff")
                    call.response.headers.append("X-Frame-Options", "DENY")
                    call.response.headers.append("Referrer-Policy", "no-referrer")
                    call.response.headers.append("Content-Security-Policy", "default-src 'self'; style-src 'self'; img-src 'self'; form-action 'self';")
                }
            }

            engine = embeddedServer(Netty, port = 59713, host = "0.0.0.0") {
                install(CustomSecurityHeaders)

                routing {
                    get("/cam.mjpeg") {
                        val submittedToken = call.request.queryParameters["token"]
                        if (submittedToken != null && submittedToken == mjpegAccessToken && mjpegAccessToken != null) {
                            // Optionally invalidate token after first use for higher security:
                            // mjpegAccessToken = null 
                            try {
                                call.respondOutputStream(
                                    ContentType.parse("multipart/x-mixed-replace; boundary=FRAME"),
                                    HttpStatusCode.OK
                                ) {
                                    producer().invoke(this)
                                }
                            } catch (e: Exception) {
                                Log.e("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Error processing request", e)
                                if (call.response.status() == null) {
                                    try { call.respondText("Error: ${e.message}", ContentType.Text.Plain, HttpStatusCode.InternalServerError) } catch (e2: Exception) { Log.e("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Failed to send error response", e2) }
                                }
                            }
                        } else {
                            Log.w("HTTP_SERVICE_DEBUG", "/cam.mjpeg: Unauthorized access attempt with token: $submittedToken. Expected: $mjpegAccessToken")
                            call.respond(HttpStatusCode.Unauthorized, "Invalid or missing access token.")
                        }
                    }

                    get("/") {
                        val loginFailed = call.request.queryParameters["loginFailed"] == "true"
                        var htmlResponse = """
                            <html>
                            <head><title>Login - RemoteCam</title></head>
                            <body>
                                <form method="POST" action="/">
                                    Password: <input type="password" name="password" autofocus autocomplete="current-password">
                                    <input type="submit" value="Login">
                                </form>
                        """
                        if (loginFailed) {
                            htmlResponse += """<p style=\"color:red;\">Incorrect password. Please try again.</p>"""
                        }
                        htmlResponse += """</body></html>"""
                        call.response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
                        call.response.header(HttpHeaders.Pragma, "no-cache")
                        call.response.header(HttpHeaders.Expires, "0")
                        call.respondText(htmlResponse.trimIndent(), ContentType.Text.Html, HttpStatusCode.OK)
                    }

                    post("/") {
                        if (call.request.contentType() != ContentType.Application.FormUrlEncoded) {
                            call.respond(HttpStatusCode.UnsupportedMediaType, "Content-Type must be application/x-www-form-urlencoded")
                            return@post
                        }

                        val parameters = call.receiveParameters()
                        val submittedPassword = parameters["password"]

                        if (submittedPassword != null && verifyPassword(submittedPassword, currentPasswordHash)) {
                            // Password is correct, generate and store a new MJPEG access token
                            mjpegAccessToken = generateMjpegAccessToken()
                            Log.i("HTTP_SERVICE_DEBUG", "/: Correct password. Generated MJPEG access token: $mjpegAccessToken")

                            call.response.header(HttpHeaders.CacheControl, "no-store, no-cache, must-revalidate")
                            call.response.header(HttpHeaders.Pragma, "no-cache")
                            call.response.header(HttpHeaders.Expires, "0")
                            call.respondText(
                                """
                                <html>
                                <head><title>RemoteCam Stream</title></head>
                                <body>
                                    <img src="/cam.mjpeg?token=$mjpegAccessToken" alt="Camera Stream">
                                </body>
                                </html>
                                """.trimIndent(),
                                ContentType.Text.Html
                            )
                        } else {
                            Log.w("HTTP_SERVICE_DEBUG", "/: Incorrect password submitted.")
                            mjpegAccessToken = null // Invalidate token on failed login attempt too
                            call.respondRedirect("/?loginFailed=true", permanent = false)
                        }
                    }
                }
            }

            engine.start(wait = false)
            Log.i("HTTP_SERVICE_DEBUG", "main: HTTP server started successfully on 0.0.0.0:59713.")

        } catch (e: Exception) {
            Log.e("HTTP_SERVICE_DEBUG", "main: Error starting HTTP server", e)
            throw e
        }
    }

    fun stop() {
        try {
            if (!channel.isClosedForSend) {
                channel.close()
            }
            if (::engine.isInitialized) {
                engine.stop(1000, 2000)
                Log.i("HTTP_SERVICE_DEBUG", "stop: HTTP server stopped successfully.")
            }
        } catch (e: Exception) {
            Log.e("HTTP_SERVICE_DEBUG", "stop: Error stopping HTTP server", e)
        }
    }
}
