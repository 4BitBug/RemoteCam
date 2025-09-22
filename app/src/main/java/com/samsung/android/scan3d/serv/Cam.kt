package com.samsung.android.scan3d.serv

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.samsung.android.scan3d.CameraActivity
import com.samsung.android.scan3d.R
import com.samsung.android.scan3d.fragments.CameraFragment
import com.samsung.android.scan3d.http.HttpService

class Cam : Service() {
    private var engine: CamEngine? = null
    var http: HttpService? = null // Made public for password change access if needed, or use a specific method
    private val CHANNEL_ID = "REMOTE_CAM"
    private val NOTIFICATION_ID = 123
    private var isForegroundServiceActive = false
    private var isServiceStopping = false // Flag to indicate the service is in the process of stopping

    private val ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK = "com.samsung.android.scan3d.ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK"

    companion object {
        private const val TAG = "CamService"
        const val PREFS_NAME = "RemoteCamServicePrefs"
        const val KEY_SERVICE_MANUALLY_STOPPED = "service_manually_stopped"
        const val ACTION_CHANGE_PASSWORD = "com.samsung.android.scan3d.ACTION_CHANGE_PASSWORD"
        const val EXTRA_NEW_PASSWORD = "com.samsung.android.scan3d.EXTRA_NEW_PASSWORD"
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.i(TAG, "onCreate: Service creating.")

        // Initialize components here, but don't start intensive operations
        if (http == null) {
            http = HttpService(applicationContext) // Pass applicationContext
            Log.i(TAG, "onCreate: HttpService instance CREATED: $http")
        }
        if (engine == null) {
            engine = CamEngine(this)
            engine?.http = http // Link http service to engine
            Log.i(TAG, "onCreate: CamEngine instance CREATED: $engine, linked with http: ${engine?.http}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: "unknown"
        Log.i(TAG, "onStartCommand: Received action = '$action', isServiceStopping = $isServiceStopping, manually_stopped_pref = ${prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)}")

        if (prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false) && action != "start") {
            Log.w(TAG, "onStartCommand: Service was manually stopped and action is not 'start'. Ignoring '$action' and ensuring shutdown.")
            handleServiceShutdown(manualStop = true, fromUserKill = true)
            return START_NOT_STICKY
        }

        when (action) {
            "start" -> {
                Log.i(TAG, "onStartCommand: 'start' action received.")
                isServiceStopping = false
                prefs.edit().putBoolean(KEY_SERVICE_MANUALLY_STOPPED, false).apply()
                
                if (!isForegroundServiceActive) {
                    startForegroundServiceWithNotification()
                }
                // Initialize and start components if not already active
                http?.main() // Idempotent or checks internally if already running
                Log.i(TAG, "onStartCommand: 'start' - HTTP service main() called.")
            }
            "stop" -> {
                Log.i(TAG, "onStartCommand: 'stop' action received (likely from Activity.onDestroy).")
                handleServiceShutdown(manualStop = false, fromUserKill = false)
                return START_NOT_STICKY // Don't restart if stopped by activity
            }
            "user_kill" -> {
                Log.i(TAG, "onStartCommand: 'user_kill' action received (from kill button).")
                handleServiceShutdown(manualStop = true, fromUserKill = true)
                return START_NOT_STICKY
            }
            ACTION_CHANGE_PASSWORD -> {
                if (isServiceStopping || !isForegroundServiceActive) {
                    Log.w(TAG, "onStartCommand: Service stopping or not in foreground. Ignoring action: '$action'")
                    return START_NOT_STICKY
                }
                val newPassword = intent?.getStringExtra(EXTRA_NEW_PASSWORD) // Fixed nullability issue here
                if (newPassword != null) {
                    changeHttpPassword(newPassword)
                } else {
                    Log.w(TAG, "ACTION_CHANGE_PASSWORD received without new password or intent was null.")
                }
            }
            else -> {
                if (isServiceStopping || !isForegroundServiceActive) {
                    Log.w(TAG, "onStartCommand: Service stopping or not in foreground. Ignoring action: '$action'")
                    return START_NOT_STICKY
                }
                processOtherCommands(intent)
            }
        }
        return START_STICKY
    }

    private fun startForegroundServiceWithNotification() {
        if (isForegroundServiceActive || isServiceStopping) {
            Log.i(TAG, "startForegroundServiceWithNotification: Skipping, already active or stopping. Active: $isForegroundServiceActive, Stopping: $isServiceStopping")
            return
        }
        try {
            Log.i(TAG, "Attempting to start foreground service.")
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.service_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = getString(R.string.service_description)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

            val notificationIntent = Intent(this, CameraActivity::class.java)
            val pendingIntent = createPendingIntentActivity(notificationIntent)

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_linked_camera)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            startForeground(NOTIFICATION_ID, builder.build())
            isForegroundServiceActive = true
            Log.i(TAG, "Foreground service started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service", e)
            isForegroundServiceActive = false
        }
    }

    private fun processOtherCommands(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: "unknown"
        Log.d(TAG, "processOtherCommands: Processing action '$action'")
        try {
            when (action) {
                "onPause" -> {
                    Log.d(TAG, "processOtherCommands: 'onPause' action.")
                    engine?.insidePause = true
                    if (engine?.isShowingPreview == true) engine?.restart() 
                }
                "onResume" -> {
                    Log.d(TAG, "processOtherCommands: 'onResume' action.")
                    engine?.insidePause = false
                    if (engine?.previewSurface != null && engine?.viewState?.preview == true) {
                        engine?.restart()
                    }
                }
                "start_camera_engine" -> {
                     Log.d(TAG, "processOtherCommands: 'start_camera_engine' action. Ensuring engine is ready.")
                    if (engine?.http == null) engine?.http = http
                    engine?.updateView() 
                }
                "new_view_state" -> {
                    Log.d(TAG, "processOtherCommands: 'new_view_state' action.")
                    val newState: CameraFragment.Companion.ViewState? = intent.extras?.getParcelable("data")
                    if (newState != null && engine?.viewState != newState) {
                        engine?.viewState = newState
                        Log.i(TAG, "ViewState updated, restarting CamEngine. Preview: ${newState.preview}, Stream: ${newState.stream}")
                        engine?.restart() 
                    } else if (newState == null) {
                        Log.w(TAG, "Received null ViewState in new_view_state action.")
                    }
                }
                "new_preview_surface" -> {
                    Log.d(TAG, "processOtherCommands: 'new_preview_surface' action.")
                    val surface: Surface? = intent.extras?.getParcelable("surface")
                    engine?.previewSurface = surface
                    if (surface != null && engine?.viewState?.preview == true && engine?.insidePause == false) {
                        Log.i(TAG, "Valid surface received and preview is on, restarting CamEngine.")
                        engine?.restart() 
                    } else if (surface == null) {
                        Log.i(TAG, "Preview surface set to null. CamEngine will handle this internally.")
                    }
                }
                "request_sensor_data" -> {
                    Log.d(TAG, "processOtherCommands: 'request_sensor_data' action.")
                    engine?.updateView()
                }
                else -> Log.w(TAG, "Unhandled action in processOtherCommands: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing command: $action", e)
        }
    }

    private fun createPendingIntentActivity(intent: Intent): PendingIntent {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, intent, flags)
    }

    private fun shutdownServiceResources() {
        Log.i(TAG, "shutdownServiceResources: Releasing CamEngine and HttpService.")
        try {
            engine?.destroy()
            engine = null
            Log.i(TAG, "shutdownServiceResources: CamEngine destroyed and nulled.")
        } catch (e: Exception) {
            Log.e(TAG, "shutdownServiceResources: Error destroying CamEngine.", e)
        }
        try {
            http?.stop()
            http = null // Nulled here
            Log.i(TAG, "shutdownServiceResources: HttpService stopped and nulled.")
        } catch (e: Exception) {
            Log.e(TAG, "shutdownServiceResources: Error stopping HttpService.", e)
        }
    }

    private fun handleServiceShutdown(manualStop: Boolean, fromUserKill: Boolean) {
        if (isServiceStopping && !fromUserKill) { 
            Log.w(TAG, "handleServiceShutdown: Already in the process of stopping. manualStop=$manualStop")
            return
        }
        Log.i(TAG, "handleServiceShutdown: Initiating shutdown. manualStop=$manualStop, fromUserKill=$fromUserKill")
        isServiceStopping = true

        if (manualStop) {
            prefs.edit().putBoolean(KEY_SERVICE_MANUALLY_STOPPED, true).apply()
            Log.i(TAG, "handleServiceShutdown: Marked service as manually stopped in preferences.")
        }

        shutdownServiceResources()

        if (isForegroundServiceActive) {
            Log.i(TAG, "handleServiceShutdown: Stopping foreground service.")
            stopForeground(STOP_FOREGROUND_REMOVE) 
            isForegroundServiceActive = false
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)

        Log.i(TAG, "handleServiceShutdown: Calling stopSelf().")
        stopSelf()

        if (fromUserKill) {
            val finishIntent = Intent(ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK)
            sendBroadcast(finishIntent)
            Log.i(TAG, "handleServiceShutdown: Sent broadcast to finish activity due to user kill.")
        }
    }
    
    private fun changeHttpPassword(newPassword: String) {
        val success = http?.changePassword(newPassword) ?: false
        if (success) {
            Log.i(TAG, "HTTP password changed successfully.")
            // Optionally, restart HttpService if needed, though Ktor might pick up changes if password check is dynamic
            // For simplicity, current HttpService reloads password on init and changePassword updates it in SharedPreferences
        } else {
            Log.w(TAG, "Failed to change HTTP password.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null // Not using binding for password change, using Intent action

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: Service is being destroyed.")
        shutdownServiceResources()
        if (isForegroundServiceActive) {
            Log.w(TAG, "onDestroy: Service destroyed but was still marked as foreground. Attempting to clear notification.")
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        }
        super.onDestroy()
        Log.i(TAG, "onDestroy: Service destruction completed.")
    }
}
