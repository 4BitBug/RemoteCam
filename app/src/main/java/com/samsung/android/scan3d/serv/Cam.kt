package com.samsung.android.scan3d.serv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
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
    private var http: HttpService? = null
    private val CHANNEL_ID = "REMOTE_CAM"
    private val NOTIFICATION_ID = 123
    private var isForegroundServiceActive = false
    private var isServiceStopping = false // Flag to indicate the service is in the process of stopping

    private val ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK = "com.samsung.android.scan3d.ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK"

    companion object {
        private const val TAG = "CamService"
        const val PREFS_NAME = "RemoteCamServicePrefs"
        const val KEY_SERVICE_MANUALLY_STOPPED = "service_manually_stopped"
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        Log.i(TAG, "onCreate: Service creating.")

        // Initialize components here, but don't start intensive operations
        if (http == null) {
            http = HttpService()
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
                // Camera engine operations are typically triggered by subsequent actions like new_preview_surface or new_view_state
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
            else -> {
                if (isServiceStopping || !isForegroundServiceActive) {
                    Log.w(TAG, "onStartCommand: Service stopping or not in foreground. Ignoring action: '$action'")
                    // If service is meant to be stopped, ensure it does not process further commands.
                    // If it was manually stopped, it should already be handled above.
                    return START_NOT_STICKY
                }
                // Process other commands only if service is active and not stopping
                processOtherCommands(intent)
            }
        }
        return START_STICKY // For normal operations, if service is killed by system, try to restart.
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
                    if (engine?.isShowingPreview == true) engine?.restart() // Restart to apply changes if preview was on
                }
                "onResume" -> {
                    Log.d(TAG, "processOtherCommands: 'onResume' action.")
                    engine?.insidePause = false
                    // If surface is already available and preview is desired, restart might be needed
                    if (engine?.previewSurface != null && engine?.viewState?.preview == true) {
                        engine?.restart()
                    }
                }
                "start_camera_engine" -> {
                     Log.d(TAG, "processOtherCommands: 'start_camera_engine' action. Ensuring engine is ready.")
                    // This action might be redundant if view_state and surface handling are robust
                    // For now, ensure http is linked and request an update or restart if necessary
                    if (engine?.http == null) engine?.http = http
                    engine?.updateView() // Request an update which might trigger restart if conditions met
                }
                "new_view_state" -> {
                    Log.d(TAG, "processOtherCommands: 'new_view_state' action.")
                    val newState: CameraFragment.Companion.ViewState? = intent.extras?.getParcelable("data")
                    if (newState != null && engine?.viewState != newState) {
                        engine?.viewState = newState
                        Log.i(TAG, "ViewState updated, restarting CamEngine. Preview: ${newState.preview}, Stream: ${newState.stream}")
                        engine?.restart() // Restart to apply new view state
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
                        engine?.restart() // Restart to use the new surface
                    } else if (surface == null) {
                        Log.i(TAG, "Preview surface set to null. CamEngine will handle this internally.")
                        // CamEngine should internally stop drawing or attempting to use a null surface.
                        // If engine is not null and preview was active, CamEngine's restart (if called due to other state changes)
                        // or its destroy method will handle stopping camera operations.
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
            http = null
            Log.i(TAG, "shutdownServiceResources: HttpService stopped and nulled.")
        } catch (e: Exception) {
            Log.e(TAG, "shutdownServiceResources: Error stopping HttpService.", e)
        }
    }

    private fun handleServiceShutdown(manualStop: Boolean, fromUserKill: Boolean) {
        if (isServiceStopping && !fromUserKill) { // Allow user_kill to proceed even if already stopping
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
            stopForeground(STOP_FOREGROUND_REMOVE) // Remove notification when stopping
            isForegroundServiceActive = false
        }
        
        // Ensure notification is cancelled if somehow stopForeground didn't remove it
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(NOTIFICATION_ID)

        Log.i(TAG, "handleServiceShutdown: Calling stopSelf().")
        stopSelf() // Stop the service itself

        if (fromUserKill) {
            val finishIntent = Intent(ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK)
            sendBroadcast(finishIntent)
            Log.i(TAG, "handleServiceShutdown: Sent broadcast to finish activity due to user kill.")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: Service is being destroyed.")
        // Ensure resources are released as a final measure.
        // isServiceStopping should ideally be true here if shutdown was initiated properly.
        shutdownServiceResources()
        if (isForegroundServiceActive) {
             // This case should ideally not happen if stopForeground(true) was called
            Log.w(TAG, "onDestroy: Service destroyed but was still marked as foreground. Attempting to clear notification.")
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
        }
        super.onDestroy()
        Log.i(TAG, "onDestroy: Service destruction completed.")
    }
}
