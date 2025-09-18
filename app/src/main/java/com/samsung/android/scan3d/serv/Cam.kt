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
import com.samsung.android.scan3d.http.HttpService
import kotlinx.coroutines.launch

class Cam : Service() {
    var engine: CamEngine? = null
    var http: HttpService? = null
    val CHANNEL_ID = "REMOTE_CAM"
    private val NOTIFICATION_ID = 123
    private var isForegroundServiceStarted = false
    private var isStopping = false

    val ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK = "com.samsung.android.scan3d.ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK"

    companion object {
        const val PREFS_NAME = "RemoteCamServicePrefs"
        const val KEY_SERVICE_MANUALLY_STOPPED = "service_manually_stopped"
    }

    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        isStopping = prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)

        Log.i("CAM", "Service onCreate. isStopping (from prefs): $isStopping")

        if (isStopping) {
            Log.w("CAM", "Service was manually stopped previously. Shutting down immediately.")
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            stopSelf()
            return // Do not proceed further if manually stopped
        }
        // If not manually stopped, proceed to start foreground if needed.
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("CAM", "onStartCommand: action=${intent?.action}, isStopping=$isStopping, manually_stopped_pref=${prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)}")

        if (intent?.action == "start") {
            Log.i("CAM", "Received 'start' command. Resetting manually_stopped state.")
            isStopping = false
            prefs.edit().putBoolean(KEY_SERVICE_MANUALLY_STOPPED, false).apply()
            // Proceed with initialization and ensure foreground service is up
            if (!isForegroundServiceStarted) {
                startForegroundService()
            }
            initializeService()
            return START_STICKY
        }

        if (intent?.action == "stop") {
            Log.i("CAM", "Received 'stop' command.")
            kill()
            return START_NOT_STICKY
        }

        // This check handles cases where service restarts after being killed by system
        // or if onCreate decided it should stop based on prefs.
        if (isStopping || prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)) {
            Log.w("CAM", "Service is stopping or was manually stopped. Ignoring: ${intent?.action}. Shutting down.")
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            stopSelf() // Ensure this instance stops
            return START_NOT_STICKY
        }

        // For other intents, if not starting, stopping, or manually stopped.
        if (!isForegroundServiceStarted) {
            startForegroundService()
        }

        if (intent != null) {
            try {
                when (intent.action) {
                    // "start" and "stop" are handled above
                    "onPause" -> {
                        engine?.insidePause = true
                        if (engine?.isShowingPreview == true) engine?.restart()
                    }
                    "onResume" -> engine?.insidePause = false
                    "start_camera_engine" -> startCameraEngine()
                    "new_view_state" -> handleNewViewState(intent)
                    "new_preview_surface" -> handleNewPreviewSurface(intent)
                    "request_sensor_data" -> engine?.updateView()
                    else -> Log.w("CAM", "Unhandled action: ${intent.action}")
                }
            } catch (e: Exception) {
                Log.e("CAM", "Error processing command: ${intent.action}", e)
            }
        }
        return START_STICKY // Default for ongoing operations
    }

    private fun startForegroundService() {
        if (isStopping || isForegroundServiceStarted) {
            Log.i("CAM", "startForegroundService: Bailing out. isStopping=$isStopping, isForegroundServiceStarted=$isForegroundServiceStarted")
            return
        }
        
        try {
            Log.i("CAM", "Attempting to start foreground service")
            val channel = NotificationChannel(CHANNEL_ID, getString(R.string.service_name), NotificationManager.IMPORTANCE_LOW)
            channel.description = getString(R.string.service_description)
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)

            val notificationIntent = Intent(this, CameraActivity::class.java)
            val pendingIntent = createPendingIntentActivity(notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_linked_camera)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            val notification: Notification = builder.build()
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    val typeCamera = 1 // ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    val typeSpecialUse = 1073741824 // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    startForeground(NOTIFICATION_ID, notification, typeCamera or typeSpecialUse)
                } catch (e: Exception) {
                    Log.w("CAM", "Failed to set foreground service type, using default", e)
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            
            isForegroundServiceStarted = true
            Log.i("CAM", "Foreground service started successfully with ID: $NOTIFICATION_ID")
        } catch (e: Exception) {
            Log.e("CAM", "Failed to start foreground service", e)
        }
    }

    private fun initializeService() {
        if (isStopping) return 
        try {
            if (http == null) {
                http = HttpService()
                try {
                    http?.main()
                    Log.i("CAM", "HTTP service started on port 8080")
                    engine?.http = http
                } catch (e: Exception) {
                    Log.e("CAM", "HTTP service failed to start", e)
                }
            }
        } catch (e: Exception) {
            Log.e("CAM", "Service initialization failed", e)
        }
    }

    private fun createPendingIntentActivity(intent: Intent, flags: Int): PendingIntent {
        val combinedFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) FLAG_IMMUTABLE or flags else flags
        return PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, combinedFlags)
    }

    private fun startCameraEngine() {
        if (isStopping) return 
        try {
            engine?.destroy()
            engine = CamEngine(this)
            engine?.http = http 
            Log.i("CAM", "Camera engine started")
            engine?.updateView()
        } catch (e: Exception) {
            Log.e("CAM", "Failed to start camera engine", e)
        }
    }

    private fun handleNewViewState(intent: Intent) {
        if (isStopping) return
        try {
            val new: com.samsung.android.scan3d.fragments.CameraFragment.Companion.ViewState? = intent.extras?.getParcelable("data")
            if (engine?.viewState != null && new != null && engine?.viewState != new) {
                engine?.viewState = new
                engine?.restart()
            }
        } catch (e: Exception) {
            Log.e("CAM", "Error handling new view state", e)
        }
    }

    private fun handleNewPreviewSurface(intent: Intent) {
        if (isStopping) return
        try {
            val surface: Surface? = intent.extras?.getParcelable("surface")
            engine?.previewSurface = surface
            if (engine?.viewState?.preview == true) {
                engine?.let { eng ->
                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            kotlinx.coroutines.delay(200) 
                            if (!isStopping) eng.initializeCamera()
                        } catch (e: Exception) {
                            Log.e("CAM", "Failed to reinitialize camera with new surface", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("CAM", "Error handling new preview surface", e)
        }
    }

    fun kill() {
        if (isStopping && prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)) { 
            Log.w("CAM", "Kill method already invoked and service is marked as manually stopped.")
            return
        }
        Log.i("CAM", "Kill method invoked. Setting isStopping=true and marking as manually stopped in prefs.")
        isStopping = true
        prefs.edit().putBoolean(KEY_SERVICE_MANUALLY_STOPPED, true).apply()
        
        engine?.destroy()
        http?.stop()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        Log.i("CAM", "Calling stopForeground(STOP_FOREGROUND_REMOVE)")
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i("CAM", "Explicitly cancelling notification with ID: $NOTIFICATION_ID")
        notificationManager.cancel(NOTIFICATION_ID)
        
        isForegroundServiceStarted = false 
        
        Log.i("CAM", "Calling stopSelf()")
        stopSelf() 
        Log.i("CAM", "Service stopped and resources released")

        val finishIntent = Intent(ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK)
        sendBroadcast(finishIntent)
        Log.i("CAM", "Sent broadcast to finish activity and remove task")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i("CAM", "Service onDestroy called. isStopping: $isStopping, manually_stopped_pref: ${prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)}")
        // If service is destroyed, and it was meant to be stopping, ensure notification is gone.
        if (isStopping || prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            Log.i("CAM", "onDestroy: Explicitly cancelled notification $NOTIFICATION_ID as service was stopping or manually stopped.")
        }
    }
}