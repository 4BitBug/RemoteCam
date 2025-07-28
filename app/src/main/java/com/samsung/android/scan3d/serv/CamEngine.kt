package com.samsung.android.scan3d.serv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Parcelable
import android.util.Log
import android.util.Size
import android.view.Surface
import com.samsung.android.scan3d.fragments.CameraFragment
import com.samsung.android.scan3d.http.HttpService
import com.samsung.android.scan3d.util.Selector
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.parcelize.Parcelize
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
class CamEngine(val context: Context) {

    var http: HttpService? = null
    var resW = 1280
    var resH = 720

    var insidePause = false

    var isShowingPreview: Boolean = false

    private var cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    var cameraList: List<Selector.SensorDesc> =
        Selector.enumerateCameras(cameraManager)

    var camOutPutFormat = ImageFormat.JPEG // 默认使用JPEG，如果不支持会动态调整

    val executor = Executors.newSingleThreadExecutor()

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    internal val cameraHandler = Handler(cameraThread.looper)

    var viewState: CameraFragment.Companion.ViewState = CameraFragment.Companion.ViewState(
        true,
        stream = false,
        cameraId = "0",
        quality = 80,
        resolutionIndex = null
    )

    init {
        Log.i("CamEngine", "初始化CamEngine...")
        
        // 枚举摄像头
        cameraList = Selector.enumerateCameras(cameraManager)
        
        Log.i("CamEngine", "找到 ${cameraList.size} 个摄像头")
        cameraList.forEach { sensor ->
            Log.i("CamEngine", "可用摄像头: ${sensor.cameraId} - ${sensor.title}")
        }
        
        // 确保有可用的摄像头
        if (cameraList.isNotEmpty()) {
            // 如果默认的cameraId不存在，使用第一个可用的
            if (!cameraList.any { it.cameraId == viewState.cameraId }) {
                viewState.cameraId = cameraList.first().cameraId
                Log.i("CamEngine", "更新viewState.cameraId为 ${viewState.cameraId}")
            }
            
            // 初始化摄像头特性
            initializeCharacteristics()
        } else {
            Log.e("CamEngine", "没有可用摄像头!")
        }
    }

    fun getEncoder(mimeType: String, resW: Int, resH: Int): MediaCodec? {
        fun selectCodec(mimeType: String, needEncoder: Boolean): MediaCodecInfo? {
            val list = MediaCodecList(0).getCodecInfos()
            list.forEach {
                if (it.isEncoder) {
                    Log.i(
                        "CODECS",
                        "We got type " + it.name + " " + it.supportedTypes.contentToString()
                    )
                    if (it.supportedTypes.any { e -> e.equals(mimeType, ignoreCase = true) }) {
                        return it
                    }
                }
            }
            return null
        }

        val colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatCbYCrY;
        val codec = selectCodec("video/avc", true) ?: return null
        val format = MediaFormat.createVideoFormat("video/avc", resW, resH)
        format.setString(MediaFormat.KEY_MIME, "video/avc");
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
        Log.i("CODECS", "video/avc: " + codec)
        val encoder = MediaCodec.createByCodecName(codec.getName());
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoder.start();
        return encoder
    }


    /** [CameraCharacteristics] corresponding to the provided Camera ID */
    var characteristics: CameraCharacteristics? = null
    var sizes: List<Size> = emptyList()

    private fun initializeCharacteristics() {
        try {
            Log.i("CamEngine", "初始化摄像头特性: ${viewState.cameraId}")
            characteristics = cameraManager.getCameraCharacteristics(viewState.cameraId)
            val currentCharacteristics = characteristics
            if (currentCharacteristics != null) {
                // 尝试获取JPEG输出尺寸
                try {
                    val configMap = currentCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    sizes = configMap?.getOutputSizes(camOutPutFormat)?.reversed() ?: emptyList()
                    Log.i("CamEngine", "摄像头 ${viewState.cameraId} 支持 ${sizes.size} 个JPEG分辨率")
                } catch (e: Exception) {
                    Log.w("CamEngine", "无法获取JPEG输出尺寸，尝试其他格式", e)
                    // 如果JPEG失败，尝试其他格式
                    try {
                        val configMap = currentCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                        sizes = configMap?.getOutputSizes(ImageFormat.YUV_420_888)?.reversed() ?: emptyList()
                        Log.i("CamEngine", "摄像头 ${viewState.cameraId} 支持 ${sizes.size} 个YUV分辨率")
                    } catch (e2: Exception) {
                        Log.w("CamEngine", "也无法获取YUV输出尺寸，使用默认分辨率", e2)
                        // 使用默认分辨率
                        sizes = listOf(
                            android.util.Size(1920, 1080),
                            android.util.Size(1280, 720),
                            android.util.Size(640, 480)
                        )
                        Log.i("CamEngine", "使用默认分辨率列表")
                    }
                }
                
                if (sizes.isNotEmpty()) {
                    Log.i("CamEngine", "摄像头特性初始化成功，${sizes.size} 个分辨率可用")
                } else {
                    Log.w("CamEngine", "没有可用分辨率，使用最小默认分辨率")
                    sizes = listOf(android.util.Size(640, 480))
                }
            } else {
                Log.e("CamEngine", "摄像头特性为空")
                // 使用最基本的默认设置
                sizes = listOf(android.util.Size(640, 480))
            }
        } catch (e: Exception) {
            Log.e("CamEngine", "获取摄像头 ${viewState.cameraId} 特性失败", e)
            // 完全失败时使用默认设置
            sizes = listOf(android.util.Size(640, 480))
            Log.i("CamEngine", "使用默认分辨率作为后备")
        }
    }


    private lateinit var imageReader: ImageReader

    private lateinit var camera: CameraDevice

    var previewSurface: Surface? = null

    private var session: CameraCaptureSession? = null

    private fun stopRunning() {
        if (session != null) {
            Log.i("CAMERA", "close")
            session!!.stopRepeating()
            session!!.close()
            session = null
            camera.close()
            imageReader.close()
        }
    }

    fun restart() {
        stopRunning()
        // 使用协程启动suspend函数
        kotlinx.coroutines.GlobalScope.launch {
            try {
                initializeCamera()
                Log.i("CamEngine", "Camera restarted successfully")
            } catch (e: Exception) {
                Log.e("CamEngine", "Error restarting camera", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w("CamEngine", "Camera $cameraId has been disconnected")

            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e("CamEngine", exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /**
     * Starts a [CameraCaptureSession] and returns the configured session (as the result of the
     * suspend coroutine
     */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        // Create a capture session using the predefined targets; this also involves defining the
        // session state callback to be notified of when the session is ready
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {

            override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)

            override fun onConfigureFailed(session: CameraCaptureSession) {
                val exc = RuntimeException("Camera ${device.id} session configuration failed")
                Log.e("CamEngine", exc.message, exc)
                cont.resumeWithException(exc)
            }
        }, handler)
    }

    suspend fun initializeCamera() {
        Log.i("CAMERA", "开始初始化摄像头: ${viewState.cameraId}")

        val showLiveSurface = viewState.preview && !insidePause && previewSurface != null
        isShowingPreview = showLiveSurface
        
        Log.i("CAMERA", "预览设置: preview=${viewState.preview}, insidePause=$insidePause, surface=${previewSurface != null}")

        stopRunning()

        // 强制重新获取特性
        initializeCharacteristics()
        
        if (sizes.isEmpty()) {
            Log.e("CamEngine", "没有可用分辨率，无法继续")
            return
        }

        if (viewState.resolutionIndex == null || viewState.resolutionIndex!! >= sizes.size) {
            // 选择一个合适的默认分辨率
            viewState.resolutionIndex = 0 // 使用第一个可用分辨率
            Log.i("CAMERA", "设置默认分辨率索引: 0")
        }
        
        resW = sizes[viewState.resolutionIndex!!].width
        resH = sizes[viewState.resolutionIndex!!].height
        Log.i("CAMERA", "使用分辨率: ${resW}x${resH}")

        try {
            camera = openCamera(cameraManager, viewState.cameraId, cameraHandler)
            Log.i("CAMERA", "摄像头 ${viewState.cameraId} 打开成功")
        } catch (e: Exception) {
            Log.e("CAMERA", "打开摄像头 ${viewState.cameraId} 失败", e)
            return
        }

        try {
            imageReader = ImageReader.newInstance(resW, resH, camOutPutFormat, 4)
            Log.i("CAMERA", "ImageReader创建成功: ${resW}x${resH}, 格式: $camOutPutFormat")
        } catch (e: Exception) {
            Log.e("CAMERA", "创建ImageReader失败，尝试其他格式", e)
            try {
                // 尝试YUV格式
                imageReader = ImageReader.newInstance(resW, resH, ImageFormat.YUV_420_888, 4)
                Log.i("CAMERA", "使用YUV格式创建ImageReader成功")
            } catch (e2: Exception) {
                Log.e("CAMERA", "创建ImageReader完全失败", e2)
                return
            }
        }
        
        var targets = listOf(imageReader.surface)
        if (showLiveSurface) {
            targets = targets.plus(previewSurface!!)
            Log.i("CAMERA", "添加预览Surface到目标列表")
        }
        
        try {
            session = createCaptureSession(camera, targets, cameraHandler)
            Log.i("CAMERA", "捕获会话创建成功")
        } catch (e: Exception) {
            Log.e("CAMERA", "创建捕获会话失败", e)
            return
        }

        try {
            val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            
            if (showLiveSurface) {
                captureRequest.addTarget(previewSurface!!)
                Log.i("CAMERA", "添加预览Surface到捕获请求")
            }
            captureRequest.addTarget(imageReader.surface)
            
            // 尝试设置JPEG质量（如果支持）
            try {
                captureRequest.set(CaptureRequest.JPEG_QUALITY, viewState.quality.toByte())
            } catch (e: Exception) {
                Log.w("CAMERA", "无法设置JPEG质量", e)
            }
            
            // 尝试设置自动对焦模式（如果支持）
            try {
                val afModes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                if (afModes != null && afModes.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                    captureRequest.set(CaptureRequest.CONTROL_AF_MODE, CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                    Log.i("CAMERA", "设置连续自动对焦模式")
                }
            } catch (e: Exception) {
                Log.w("CAMERA", "无法设置自动对焦模式", e)
            }
            
            var lastTime = System.currentTimeMillis()
            var kodd = 0
            var aquired = java.util.concurrent.atomic.AtomicInteger(0)
            
            session!!.setRepeatingRequest(
                captureRequest.build(),
                object : CameraCaptureSession.CaptureCallback() {

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        super.onCaptureCompleted(session, request, result)

                        var lastImg = imageReader.acquireNextImage()

                        if (aquired.get() > 1 && lastImg != null) {
                            lastImg.close()
                            Log.d("CAMERA", "跳过重复帧")
                            lastImg = null
                        }

                        val img = lastImg ?: return
                        aquired.incrementAndGet()
                        
                        try {
                            var curTime = System.currentTimeMillis()
                            val delta = curTime - lastTime
                            lastTime = curTime
                            kodd += 1

                            // 处理不同格式的图像
                            val bytes = when (img.format) {
                                ImageFormat.JPEG -> {
                                    val buffer = img.planes[0].buffer
                                    ByteArray(buffer.remaining()).apply { buffer.get(this) }
                                }
                                ImageFormat.YUV_420_888 -> {
                                    // 简单的YUV转换（实际应用中可能需要更复杂的转换）
                                    val buffer = img.planes[0].buffer
                                    ByteArray(buffer.remaining()).apply { buffer.get(this) }
                                }
                                else -> {
                                    Log.w("CAMERA", "不支持的图像格式: ${img.format}")
                                    ByteArray(0)
                                }
                            }

                            if (bytes.isNotEmpty()) {
                                Log.d("CAMERA", "捕获帧: ${bytes.size} 字节, 格式: ${img.format}, stream=${viewState.stream}")

                                // 更新速度反馈
                                if (kodd % 10 == 0) {
                                    updateViewQuick(
                                        DataQuick(
                                            delta.toInt(),
                                            (bytes.size * 8 / 1000) // kbps计算
                                        )
                                    )
                                }

                                // 发送到HTTP流媒体
                                if (viewState.stream && http?.channel != null) {
                                    try {
                                        val result = http?.channel?.trySend(bytes)
                                        if (result?.isSuccess == true) {
                                            Log.d("CAMERA", "帧已发送到HTTP通道: ${bytes.size} 字节")
                                        } else {
                                            Log.w("CAMERA", "HTTP通道已满或关闭")
                                        }
                                    } catch (e: Exception) {
                                        Log.e("CAMERA", "发送帧到HTTP通道失败", e)
                                    }
                                } else {
                                    Log.d("CAMERA", "流媒体未启用或HTTP通道不可用")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CAMERA", "处理捕获帧时出错", e)
                        } finally {
                            img.close()
                            aquired.decrementAndGet()
                        }
                    }
                },
                cameraHandler
            )
            
            Log.i("CAMERA", "摄像头初始化完成，开始捕获")
            updateView()
            
        } catch (e: Exception) {
            Log.e("CAMERA", "设置捕获请求失败", e)
        }
    }

    fun destroy() {
        stopRunning()
        cameraThread.quitSafely()
    }

    fun updateView() {
        try {
            // 检查基本条件
            if (cameraList.isEmpty()) {
                Log.e("CamEngine", "摄像头列表为空!")
                return
            }
            
            if (sizes.isEmpty()) {
                initializeCharacteristics()
                if (sizes.isEmpty()) {
                    Log.e("CamEngine", "重新初始化后仍无分辨率可用")
                    return
                }
            }
            
            // 查找选中的传感器
            val selectedSensor = cameraList.find { it.cameraId == viewState.cameraId } ?: cameraList.first()
            
            // 确保resolutionIndex有效
            val resolutionIndex = viewState.resolutionIndex ?: 0
            
            val data = Data(
                cameraList,
                selectedSensor,
                resolutions = sizes,
                resolutionSelected = resolutionIndex
            )
            
            Log.i("CamEngine", "发送广播: ${data.sensors.size}个传感器")
            
            // 使用显式广播，确保能够送达
            val intent = Intent("UpdateFromCameraEngine")
            intent.setPackage(context.packageName) // 设置包名，确保广播能送达
            intent.putExtra("data", data)
            
            // 尝试多种方式发送广播
            try {
                context.sendBroadcast(intent)
                Log.i("CamEngine", "通过sendBroadcast发送成功")
            } catch (e: Exception) {
                Log.e("CamEngine", "sendBroadcast失败", e)
            }
            
            // 同时尝试有序广播
            try {
                context.sendOrderedBroadcast(intent, null)
                Log.i("CamEngine", "通过sendOrderedBroadcast发送成功")
            } catch (e: Exception) {
                Log.e("CamEngine", "sendOrderedBroadcast失败", e)
            }
            
        } catch (e: Exception) {
            Log.e("CamEngine", "updateView出错", e)
        }
    }

    fun updateViewQuick(dq: DataQuick) {
        val intent = Intent("UpdateFromCameraEngine")
        intent.setPackage(context.packageName) // 设置包名，确保广播能送达
        intent.putExtra("dataQuick", dq)
        
        try {
            context.sendBroadcast(intent)
            Log.d("CamEngine", "快速数据广播发送成功: ${dq.rateKbs}kB/s")
        } catch (e: Exception) {
            Log.e("CamEngine", "快速数据广播发送失败", e)
        }
    }

    companion object {
        @Parcelize
        data class Data(
            val sensors: List<Selector.SensorDesc>,
            val sensorSelected: Selector.SensorDesc,
            val resolutions: List<Size>,
            val resolutionSelected: Int,
        ) : Parcelable

        @Parcelize
        data class DataQuick(
            val ms: Int,
            val rateKbs: Int
        ) : Parcelable
    }


}