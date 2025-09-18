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
        // Defer startForegroundService until onStartCommand with "start" action
        // to ensure manual stop logic is fully processed.
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("CAM", "onStartCommand: action=${intent?.action}, isStopping=$isStopping, manually_stopped_pref=${prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)}")

        if (intent?.action == "start") {
            Log.i("CAM", "Received 'start' command. Resetting manually_stopped state.")
            isStopping = false
            prefs.edit().putBoolean(KEY_SERVICE_MANUALLY_STOPPED, false).apply()
            if (!isForegroundServiceStarted) {
                startForegroundService() // Start foreground service here
            }
            if (isForegroundServiceStarted || !isStopping) { // Proceed if foreground started or not stopping
                initializeService()
            } else {
                Log.w("CAM", "Skipping initializeService because foreground service did not start or service is stopping.")
            }
            return START_STICKY
        }

        if (intent?.action == "stop") {
            Log.i("CAM", "Received 'stop' command.")
            kill()
            return START_NOT_STICKY
        }

        // If service is already started and not stopping, process other intents
        if (isForegroundServiceStarted && !isStopping) {
            if (intent != null) {
                try {
                    when (intent.action) {
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
            return START_STICKY
        } else if (isStopping || prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)) {
            Log.w("CAM", "Service is stopping or was manually stopped. Ignoring: ${intent?.action}. Shutting down.")
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            stopSelf()
            return START_NOT_STICKY
        }

        // Fallback for unexpected scenarios, attempt to start foreground if not already
        if (!isForegroundServiceStarted) {
             Log.w("CAM", "onStartCommand: Reached a state where service might need to start foreground (action: ${intent?.action}).")
             // This path should ideally not be hit if "start" action is always first.
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        if (isStopping || isForegroundServiceStarted) {
            Log.i("CAM", "startForegroundService: Bailing out. isStopping=$isStopping, isForegroundServiceStarted=$isForegroundServiceStarted")
            return
        }
        
        try {
            Log.i("CAM", "Attempting to start foreground service.")
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
            
            // For Android Q (API 29) and above, foreground service types are declared in manifest.
            // Calling startForeground without explicit types will use those from the manifest.
            // For Android U (API 34)+, calling startForeground(id, notification) is equivalent to
            // startForeground(id, notification, 0), which means use types from manifest.
            startForeground(NOTIFICATION_ID, notification)
            
            isForegroundServiceStarted = true
            Log.i("CAM", "Foreground service started successfully with ID: $NOTIFICATION_ID using manifest types.")
        } catch (e: Exception) {
            Log.e("CAM", "Failed to start foreground service", e)
            isForegroundServiceStarted = false // Ensure this is false if it failed
        }
    }

    private fun initializeService() {
        Log.i("CAM_DEBUG", "initializeService: Called. Current http: $http, engine: $engine")
        if (isStopping) {
            Log.w("CAM_DEBUG", "initializeService: Bailing out, service is stopping.")
            return
        }
        try {
            if (http == null) {
                Log.i("CAM_DEBUG", "initializeService: Attempting to create HttpService instance.")
                try {
                    http = HttpService()
                    Log.i("CAM_DEBUG", "initializeService: HttpService instance CREATED: $http")
                } catch (e: Exception) {
                    Log.e("CAM_DEBUG", "initializeService: FAILED to create HttpService instance", e)
                    return // Can't proceed without http service
                }

                try {
                    Log.i("CAM_DEBUG", "initializeService: Attempting to call http.main()")
                    http?.main()
                    Log.i("CAM_DEBUG", "initializeService: http.main() CALLED. Ktor server should be starting.")
                    engine?.http = http 
                    Log.i("CAM_DEBUG", "initializeService: Attempted to assign http to engine. Engine: $engine, Engine's http: ${engine?.http}")
                } catch (e: Exception) {
                    Log.e("CAM_DEBUG", "initializeService: HTTP service main() method FAILED", e)
                }
            } else {
                Log.i("CAM_DEBUG", "initializeService: HttpService instance ALREADY EXISTS: $http")
                 if (engine != null && engine?.http == null) {
                    engine?.http = http
                    Log.i("CAM_DEBUG", "initializeService: Assigned existing http to engine. Engine: $engine, Engine's http: ${engine?.http}")
                }
            }
        } catch (e: Exception) {
            Log.e("CAM_DEBUG", "initializeService: Outer error during service initialization", e)
        }
    }

    private fun createPendingIntentActivity(intent: Intent, flags: Int): PendingIntent {
        val combinedFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) FLAG_IMMUTABLE or flags else flags
        return PendingIntent.getActivity(this, System.currentTimeMillis().toInt(), intent, combinedFlags)
    }

    private fun startCameraEngine() {
        Log.i("CAM_DEBUG", "startCameraEngine: Called. Current http: $http, engine: $engine. isStopping: $isStopping")
        if (isStopping) {
             Log.w("CAM_DEBUG", "startCameraEngine: Bailing out, service is stopping.")
            return
        }
        if (http == null) {
            Log.e("CAM_DEBUG", "startCameraEngine: Http service is null. Cannot start engine without it. Re-initializing service.")
            initializeService() // Attempt to re-initialize if http is null
            if (http == null) {
                Log.e("CAM_DEBUG", "startCameraEngine: Http service still null after re-init attempt. Aborting.")
                return
            }
        }
        try {
            engine?.destroy()
            engine = CamEngine(this)
            Log.i("CAM_DEBUG", "startCameraEngine: CamEngine instance CREATED: $engine")
            engine?.http = http 
            Log.i("CAM_DEBUG", "startCameraEngine: Assigned http to new engine. Engine's http: ${engine?.http}")
            engine?.updateView() 
        } catch (e: Exception) {
            Log.e("CAM_DEBUG", "startCameraEngine: Failed to start camera engine", e)
        }
    }

    private fun handleNewViewState(intent: Intent) {
        if (isStopping) return
        try {
            val new: com.samsung.android.scan3d.fragments.CameraFragment.Companion.ViewState? = intent.extras?.getParcelable("data")
            Log.i("CAM_DEBUG", "handleNewViewState: Received new view state: $new. Current engine: $engine")
            if (engine != null && new != null && engine?.viewState != new) {
                engine?.viewState = new
                Log.i("CAM_DEBUG", "handleNewViewState: ViewState changed, restarting engine. New stream state: ${new.stream}, CameraId: ${new.cameraId}")
                engine?.restart() 
            } else if (engine == null) {
                 Log.w("CAM_DEBUG", "handleNewViewState: Engine is null. Starting camera engine.")
                 startCameraEngine() // If engine is null, try to start it with new viewstate
                 engine?.viewState = new ?: return // if new is null, bail, otherwise set and restart
                 engine?.restart()
            }
        } catch (e: Exception) {
            Log.e("CAM_DEBUG", "Error handling new view state", e)
        }
    }

    private fun handleNewPreviewSurface(intent: Intent) {
        if (isStopping) return
        try {
            val surface: Surface? = intent.extras?.getParcelable("surface")
            Log.i("CAM_DEBUG", "handleNewPreviewSurface: Received new surface: $surface. Current engine: $engine")
            engine?.previewSurface = surface
            if (engine?.viewState?.preview == true && engine != null) {
                engine?.let { eng ->
                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            kotlinx.coroutines.delay(200)
                            if (!isStopping) {
                                Log.i("CAM_DEBUG", "handleNewPreviewSurface: Re-initializing camera for new surface.")
                                eng.initializeCamera()
                            }
                        } catch (e: Exception) {
                            Log.e("CAM_DEBUG", "Failed to reinitialize camera with new surface", e)
                        }
                    }
                }
            } else if (engine == null) {
                Log.w("CAM_DEBUG", "handleNewPreviewSurface: Engine is null, cannot process surface.")
            }
        } catch (e: Exception) {
            Log.e("CAM_DEBUG", "Error handling new preview surface", e)
        }
    }

    fun kill() {
        if (isStopping && prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)) { 
            Log.w("CAM_DEBUG", "Kill method: Already invoked and service is marked as manually stopped.")
            return
        }
        Log.i("CAM_DEBUG", "Kill method invoked. Setting isStopping=true and marking as manually stopped in prefs.")
        isStopping = true
        prefs.edit().putBoolean(KEY_SERVICE_MANUALLY_STOPPED, true).apply()
        
        Log.i("CAM_DEBUG", "Kill: Destroying engine: $engine")
        engine?.destroy()
        Log.i("CAM_DEBUG", "Kill: Stopping HTTP service: $http")
        http?.stop()
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        Log.i("CAM_DEBUG", "Kill: Calling stopForeground(STOP_FOREGROUND_REMOVE)")
        stopForeground(STOP_FOREGROUND_REMOVE)
        Log.i("CAM_DEBUG", "Kill: Explicitly cancelling notification with ID: $NOTIFICATION_ID")
        notificationManager.cancel(NOTIFICATION_ID)
        
        isForegroundServiceStarted = false 
        
        Log.i("CAM_DEBUG", "Kill: Calling stopSelf()")
        stopSelf() 
        Log.i("CAM_DEBUG", "Kill: Service stopped and resources released")

        val finishIntent = Intent(ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK)
        sendBroadcast(finishIntent)
        Log.i("CAM_DEBUG", "Kill: Sent broadcast to finish activity and remove task")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.i("CAM_DEBUG", "Service onDestroy called. isStopping: $isStopping, manually_stopped_pref: ${prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)}")
        if (isStopping || prefs.getBoolean(KEY_SERVICE_MANUALLY_STOPPED, false)) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(NOTIFICATION_ID)
            Log.i("CAM_DEBUG", "onDestroy: Explicitly cancelled notification $NOTIFICATION_ID as service was stopping or manually stopped.")
        }
    }
}