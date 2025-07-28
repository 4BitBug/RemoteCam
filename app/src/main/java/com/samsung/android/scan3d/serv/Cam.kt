package com.samsung.android.scan3d.serv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.app.Service

import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import com.samsung.android.scan3d.CameraActivity
import com.samsung.android.scan3d.R
import com.samsung.android.scan3d.fragments.CameraFragment
import com.samsung.android.scan3d.http.HttpService
import kotlinx.coroutines.launch


class Cam : Service() {
    var engine: CamEngine? = null
    var http: HttpService? = null
    val CHANNEL_ID = "REMOTE_CAM"
    private var isForegroundServiceStarted = false
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.i("CAM", "服务onCreate被调用")
        // 在onCreate中立即启动前台服务，确保不会超时
        startForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("CAM", "收到指令: ${intent?.action}")

        // 确保前台服务已启动
        if (!isForegroundServiceStarted) {
            startForegroundService()
        }

        if (intent != null) {
            try {
                when (intent.action) {
                    "start" -> {
                        Log.i("CAM", "启动服务")
                        initializeService()
                    }

                    "onPause" -> {
                        engine?.insidePause = true
                        if (engine?.isShowingPreview == true) {
                            engine?.restart()
                        }
                    }

                    "onResume" -> {
                        engine?.insidePause = false
                    }

                    "start_camera_engine" -> {
                        startCameraEngine()
                    }

                    "new_view_state" -> {
                        handleNewViewState(intent)
                    }

                    "new_preview_surface" -> {
                        handleNewPreviewSurface(intent)
                    }

                    "request_sensor_data" -> {
                        Log.i("CAM", "请求传感器数据")
                        engine?.updateView()
                    }
                }
            } catch (e: Exception) {
                Log.e("CAM", "处理指令时出错", e)
            }
        }

        return START_STICKY
    }

    private fun startForegroundService() {
        if (isForegroundServiceStarted) {
            Log.i("CAM", "前台服务已启动")
            return
        }
        
        try {
            Log.i("CAM", "启动前台服务")
            val channel = NotificationChannel(
                CHANNEL_ID,
                "RemoteCam服务",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "RemoteCam摄像头流媒体服务"
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)

            // Create a notification for the foreground service
            val notificationIntent = Intent(this, CameraActivity::class.java)
            val pendingIntent = createPendingIntent(notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT)

            val intentKill = Intent("KILL")
            val pendingIntentKill = createBroadcastPendingIntent(intentKill, PendingIntent.FLAG_UPDATE_CURRENT)

            val builder = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("RemoteCam正在运行")
                .setContentText("点击打开，正在提供摄像头流媒体服务")
                .setOngoing(true)
                .setSmallIcon(R.drawable.ic_linked_camera)
                .addAction(R.drawable.ic_close, "停止", pendingIntentKill)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            val notification: Notification = builder.build()
            
            // Android 14+需要在onCreate后立即调用startForeground
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // 使用反射方式避免编译时错误
                try {
                    val typeCamera = 1 // ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
                    val typeSpecialUse = 1073741824 // ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE 的值
                    startForeground(123, notification, typeCamera or typeSpecialUse)
                } catch (e: Exception) {
                    Log.w("CAM", "无法设置前台服务类型，使用默认方式", e)
                    startForeground(123, notification)
                }
            } else {
                startForeground(123, notification)
            }
            
            isForegroundServiceStarted = true
            Log.i("CAM", "前台服务启动成功")
        } catch (e: Exception) {
            Log.e("CAM", "启动前台服务失败", e)
        }
    }

    private fun initializeService() {
        try {
            Log.i("CAM", "初始化服务组件")
            if (http == null) {
                Log.i("CAM", "创建HTTP服务")
                http = HttpService()
                
                try {
                    http?.main()
                    Log.i("CAM", "HTTP服务启动成功，监听端口8080")
                    
                    // 如果摄像头引擎已存在，连接HTTP服务
                    if (engine != null) {
                        engine?.http = http
                        Log.i("CAM", "HTTP服务已连接到现有摄像头引擎")
                    }
                    
                } catch (e: Exception) {
                    Log.e("CAM", "HTTP服务启动失败", e)
                    // HTTP服务启动失败不影响摄像头功能
                }
            } else {
                Log.i("CAM", "HTTP服务已存在")
            }
        } catch (e: Exception) {
            Log.e("CAM", "初始化服务失败", e)
        }
    }

    private fun createPendingIntent(intent: Intent, flags: Int): PendingIntent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 需要指定导出状态
            PendingIntent.getActivity(
                this,
                System.currentTimeMillis().toInt(),
                intent,
                FLAG_IMMUTABLE or flags
            )
        } else {
            // Android 12以下使用传统方式
            PendingIntent.getActivity(
                this,
                System.currentTimeMillis().toInt(),
                intent,
                flags
            )
        }
    }

    private fun createBroadcastPendingIntent(intent: Intent, flags: Int): PendingIntent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ 需要指定导出状态
            PendingIntent.getBroadcast(
                this,
                System.currentTimeMillis().toInt(),
                intent,
                FLAG_IMMUTABLE or flags
            )
        } else {
            // Android 12以下使用传统方式
            PendingIntent.getBroadcast(
                this,
                System.currentTimeMillis().toInt(),
                intent,
                flags
            )
        }
    }

    private fun startCameraEngine() {
        try {
            Log.i("CAM", "启动摄像头引擎")
            
            if (engine != null) {
                Log.i("CAM", "摄像头引擎已存在，先销毁")
                engine?.destroy()
            }
            
            engine = CamEngine(this)
            
            // 确保HTTP服务和摄像头引擎连接
            if (http != null) {
                engine?.http = http
                Log.i("CAM", "HTTP服务已连接到摄像头引擎")
            } else {
                Log.w("CAM", "HTTP服务尚未初始化")
            }
            
            Log.i("CAM", "摄像头引擎创建成功")
            
            // 立即发送初始数据
            engine?.updateView()
            
        } catch (e: Exception) {
            Log.e("CAM", "启动摄像头引擎失败", e)
        }
    }

    private fun handleNewViewState(intent: Intent) {
        try {
            val old = engine?.viewState
            val new: CameraFragment.Companion.ViewState? = intent.extras?.getParcelable("data")
            
            if (old != null && new != null) {
                Log.i("CAM", "new_view_state: $new")
                Log.i("CAM", "from: $old")
                engine?.viewState = new
                if (old != new) {
                    Log.i("CAM", "diff")
                    engine?.restart()
                }
            } else {
                Log.w("CAM", "Invalid view state data")
            }
        } catch (e: Exception) {
            Log.e("CAM", "Error handling new view state", e)
        }
    }

    private fun handleNewPreviewSurface(intent: Intent) {
        try {
            val surface: Surface? = intent.extras?.getParcelable("surface")
            Log.i("CAM", "设置新的预览Surface: ${surface != null}")
            
            if (surface != null) {
                Log.i("CAM", "Surface有效: ${surface.isValid}")
            }
            
            engine?.previewSurface = surface
            
            // 如果预览开关打开，立即重新初始化摄像头
            if (engine?.viewState?.preview == true) {
                Log.i("CAM", "预览已启用，重启摄像头")
                // 使用协程启动suspend函数
                engine?.let { eng ->
                    kotlinx.coroutines.GlobalScope.launch {
                        try {
                            // 短暂延迟确保Surface完全准备好
                            kotlinx.coroutines.delay(200)
                            eng.initializeCamera()
                            Log.i("CAM", "摄像头重启成功")
                        } catch (e: Exception) {
                            Log.e("CAM", "重启摄像头失败", e)
                        }
                    }
                }
            } else {
                Log.i("CAM", "预览已禁用，不重启摄像头")
            }
        } catch (e: Exception) {
            Log.e("CAM", "处理新预览Surface时出错", e)
        }
    }

    fun kill() {
        try {
            engine?.destroy()
            http?.stop() // 正确停止HTTP服务
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.e("CAM", "Error in kill", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("CAM", "OnDestroy")
        kill()
    }

    companion object {
        sealed class ToCam()
        class Start() : ToCam()
        class NewSurface(surface: Surface) : ToCam()
    }
}