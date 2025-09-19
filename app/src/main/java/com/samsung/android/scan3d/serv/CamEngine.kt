package com.samsung.android.scan3d.serv

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper // Added for Looper.getMainLooper()
import android.os.Parcelable
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
// import androidx.lifecycle.Observer // Commented out as OrientationLiveData is not currently used for preview
// import com.example.android.camera.utils.OrientationLiveData // No longer strictly needed for preview rotation if ViewState is source of truth
import com.samsung.android.scan3d.fragments.CameraFragment
import com.samsung.android.scan3d.http.HttpService
import com.samsung.android.scan3d.util.Selector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.parcelize.Parcelize
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.ArrayList
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.floor
import kotlin.math.max

@Parcelize
data class ParcelableFpsRange(val lower: Int, val upper: Int) : Parcelable {
    override fun toString(): String = "[$lower, $upper]"
}

class CamEngine(val context: Context) {

    private val mainThreadHandler = Handler(Looper.getMainLooper())
    var http: HttpService? = null
    var resW = 1280 // Raw image width from camera
    var resH = 720  // Raw image height from camera
    var insidePause = false
    var isShowingPreview: Boolean = false

    private var cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    var cameraList: List<Selector.SensorDesc> = emptyList()
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    internal val cameraHandler = Handler(cameraThread.looper)
    private val engineScope = CoroutineScope(SupervisorJob() + cameraHandler.asCoroutineDispatcher())

    var viewState: CameraFragment.Companion.ViewState = CameraFragment.Companion.ViewState(
        true,
        stream = false,
        cameraId = "0",
        quality = 80,
        resolutionIndex = null,
        fpsRangeIndex = null,
        displayRotationDegrees = 0
    )

    private var chosenOutputFormat: Int = ImageFormat.YUV_420_888
    private val desiredEffectiveFpsValues: List<Int> = listOf(30, 15, 10, 5, 2, 1)
    var fpsRanges: ArrayList<ParcelableFpsRange> = ArrayList()
    private var actualHardwareFpsRanges: List<ParcelableFpsRange> = emptyList()
    private var effectiveFps: Int = 30
    private var actualCameraFps: Int = 30
    private var frameSkipRatio: Int = 1
    private var streamFrameCounter: Long = 0L

    var characteristics: CameraCharacteristics? = null
    var sizes: List<Size> = emptyList()

    // OrientationLiveData and its observer are currently commented out as CameraFragment ViewState provides displayRotation.
    // private var orientationLiveData: OrientationLiveData? = null
    // private var currentDeviceOrientation: Int = 0
    // private val orientationObserver = Observer<Int> { orientation ->
    //     Log.d("CAM_ENGINE_ORIENT", "Device orientation relative to sensor changed to: $orientation")
    //     currentDeviceOrientation = orientation
    // }

    init {
        Log.d("CAM_ENGINE_INIT", "################ CAMENGINE INIT BLOCK ENTERED ################")
        try {
            cameraList = Selector.enumerateCameras(cameraManager)
            if (cameraList.isNotEmpty()) {
                if (viewState.cameraId.isBlank() || cameraList.none { it.cameraId == viewState.cameraId }) {
                    viewState.cameraId = cameraList.first().cameraId
                    Log.w("CAM_ENGINE_INIT", "Initial viewState.cameraId was invalid. Defaulting to: ${viewState.cameraId}")
                }
                initializeCharacteristics()
            } else {
                Log.e("CAM_ENGINE_INIT", "No cameras available!")
            }
        } catch (e: Exception) {
            Log.e("CAM_ENGINE_INIT", "CRITICAL ERROR enumerating cameras or initializing characteristics", e)
        }
        Log.d("CAM_ENGINE_INIT", "################ CAMENGINE INIT BLOCK EXITED ################")
    }

    private data class YuvPlaneData(val buffer: ByteArray, val rowStride: Int, val pixelStride: Int) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as YuvPlaneData
            if (!buffer.contentEquals(other.buffer)) return false
            if (rowStride != other.rowStride) return false
            if (pixelStride != other.pixelStride) return false
            return true
        }
        override fun hashCode(): Int {
            var result = buffer.contentHashCode()
            result = 31 * result + rowStride
            result = 31 * result + pixelStride
            return result
        }
    }

    private data class YuvImageData(
        val width: Int,
        val height: Int,
        val yPlane: YuvPlaneData,
        val uPlane: YuvPlaneData,
        val vPlane: YuvPlaneData,
        val format: Int
    )

    private fun convertYuvToJpeg(yuvData: YuvImageData): ByteArray {
        if (yuvData.format != ImageFormat.YUV_420_888) {
            Log.e("YUV_CONVERTER", "Invalid image format: ${yuvData.format}, expected YUV_420_888")
            return ByteArray(0)
        }
        val width = yuvData.width
        val height = yuvData.height
        val nv21 = ByteArray(width * height * 3 / 2)
        var yPos = 0
        val yByteBuffer = ByteBuffer.wrap(yuvData.yPlane.buffer)
        if (yuvData.yPlane.rowStride == width) {
            yByteBuffer.get(nv21, 0, width * height)
            yPos = width * height
        } else {
            for (row in 0 until height) {
                yByteBuffer.position(row * yuvData.yPlane.rowStride)
                yByteBuffer.get(nv21, yPos, width)
                yPos += width
            }
        }
        var vuPos = yPos
        val uByteBuffer = ByteBuffer.wrap(yuvData.uPlane.buffer)
        val vByteBuffer = ByteBuffer.wrap(yuvData.vPlane.buffer)
        for (row in 0 until height / 2) {
            val uRowStartInUBuffer = row * yuvData.uPlane.rowStride
            val vRowStartInVBuffer = row * yuvData.vPlane.rowStride
            for (col in 0 until width / 2) {
                val vPixel = vByteBuffer.get(vRowStartInVBuffer + col * yuvData.vPlane.pixelStride)
                val uPixel = uByteBuffer.get(uRowStartInUBuffer + col * yuvData.uPlane.pixelStride)
                nv21[vuPos++] = vPixel
                nv21[vuPos++] = uPixel
            }
        }
        return try {
            YuvImage(nv21, ImageFormat.NV21, width, height, null).run {
                ByteArrayOutputStream().use { stream ->
                    compressToJpeg(Rect(0, 0, width, height), viewState.quality, stream)
                    stream.toByteArray()
                }
            }
        } catch (e: Exception) {
            Log.e("YUV_CONVERTER", "YuvImage.compressToJpeg failed", e)
            ByteArray(0)
        }
    }

    private fun initializeCharacteristics() { // Renamed from initializeCharacteristicsAndOrientationListener
        Log.d("CAM_ENGINE_DEBUG", "Attempting to initialize characteristics for camera ID: '${viewState.cameraId}'")
        this.fpsRanges = ArrayList(desiredEffectiveFpsValues.map { ParcelableFpsRange(it, it) })

        if (viewState.cameraId.isBlank()) {
            Log.e("CAM_ENGINE_DEBUG", "initializeCharacteristics: Camera ID is blank.")
            if (cameraList.isNotEmpty()) {
                 viewState.cameraId = cameraList.first().cameraId
                 Log.w("CAM_ENGINE_DEBUG", "Defaulting to first camera: ${viewState.cameraId}")
            } else {
                characteristics = null
                sizes = listOf(Size(640, 480))
                chosenOutputFormat = ImageFormat.YUV_420_888
                actualHardwareFpsRanges = listOf(ParcelableFpsRange(30, 30))
                Log.e("CAM_ENGINE_DEBUG", "No camera ID and no cameras found. Using default characteristics.")
                return
            }
        }
        try {
            characteristics = cameraManager.getCameraCharacteristics(viewState.cameraId)
            
            val configMap = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpegSizes = configMap?.getOutputSizes(ImageFormat.JPEG)?.reversed()
            if (!jpegSizes.isNullOrEmpty()) {
                sizes = jpegSizes
                chosenOutputFormat = ImageFormat.JPEG
            } else {
                val yuvSizes = configMap?.getOutputSizes(ImageFormat.YUV_420_888)?.reversed()
                if (!yuvSizes.isNullOrEmpty()) {
                    sizes = yuvSizes
                    chosenOutputFormat = ImageFormat.YUV_420_888
                } else {
                    sizes = listOf(Size(1280, 720), Size(640, 480))
                    chosenOutputFormat = ImageFormat.YUV_420_888
                    Log.w("CAM_ENGINE_DEBUG", "No JPEG or YUV output sizes. Defaulting.")
                }
            }
            if (sizes.isEmpty()) sizes = listOf(Size(640,480))

            val allSdkFpsRanges = characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            val hwStaticRanges = ArrayList<ParcelableFpsRange>()
            allSdkFpsRanges?.forEach { range ->
                if (range.lower == range.upper) { 
                    hwStaticRanges.add(ParcelableFpsRange(range.lower, range.upper))
                }
            }
            this.actualHardwareFpsRanges = if (hwStaticRanges.isNotEmpty()) {
                hwStaticRanges.distinct().sortedByDescending { it.lower }
            } else {
                Log.w("CAM_ENGINE_DEBUG", "No static hardware FPS ranges. Defaulting to [30,30].")
                listOf(ParcelableFpsRange(30, 30))
            }
            Log.d("CAM_ENGINE_DEBUG", "Success. Format: ${if (chosenOutputFormat == ImageFormat.JPEG) "JPEG" else "YUV"}, Resolutions: ${sizes.size}, Hardware FPS: ${this.actualHardwareFpsRanges.joinToString()}")
        } catch (e: Exception) {
            Log.e("CAM_ENGINE_DEBUG", "Failed to get characteristics for '${viewState.cameraId}'", e)
            characteristics = null
            sizes = listOf(Size(1280, 720), Size(640, 480))
            chosenOutputFormat = ImageFormat.YUV_420_888
            this.actualHardwareFpsRanges = listOf(ParcelableFpsRange(30,30))
        }
    }

    private lateinit var imageReader: ImageReader
    private lateinit var camera: CameraDevice
    var previewSurface: Surface? = null
    private var session: CameraCaptureSession? = null

    private fun stopRunning() {
        Log.d("CAM_LIFECYCLE", "stopRunning ENTER for ID '${viewState.cameraId}'")
        try { session?.stopRepeating() } catch (e: Exception) { Log.e("CAM_LIFECYCLE", "Error stopping repeating", e) }
        try { session?.close() } catch (e: Exception) { Log.e("CAM_LIFECYCLE", "Error closing session", e) }
        session = null
        if (this::camera.isInitialized) {
            try { camera.close() } catch (e: Exception) { Log.e("CAM_LIFECYCLE", "Error closing camera", e) }
        }
        if (this::imageReader.isInitialized) {
            try { imageReader.close() } catch (e: Exception) { Log.e("CAM_LIFECYCLE", "Error closing imageReader", e) }
        }
        Log.d("CAM_LIFECYCLE", "stopRunning EXIT for ID '${viewState.cameraId}'")
    }

    fun restart() {
        Log.d("CAM_LIFECYCLE", "Restart requested. Cam: ${viewState.cameraId}, ResIdx: ${viewState.resolutionIndex}, FPS Idx: ${viewState.fpsRangeIndex}, DispRot: ${viewState.displayRotationDegrees}")
        engineScope.launch {
            try {
                initializeCamera()
            } catch (e: Exception) {
                Log.e("CAM_LIFECYCLE", "Error restarting camera in engineScope", e)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun openCamera(manager: CameraManager, cameraId: String, handler: Handler? = null): CameraDevice = suspendCancellableCoroutine { cont ->
        if (cameraId.isBlank()) { cont.resumeWithException(IllegalArgumentException("Camera ID blank")); return@suspendCancellableCoroutine }
        try {
            manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) { if (cont.isActive) cont.resume(device) }
                override fun onDisconnected(device: CameraDevice) { device.close() }
                override fun onError(device: CameraDevice, error: Int) { if (cont.isActive) cont.resumeWithException(RuntimeException("Camera $cameraId error: $error")); device.close() }
            }, handler)
        } catch (e: Exception) { if (cont.isActive) cont.resumeWithException(e) }
    }

    private suspend fun createCaptureSession(device: CameraDevice, targets: List<Surface>, handler: Handler? = null): CameraCaptureSession = suspendCancellableCoroutine { cont ->
        try {
            val outputConfigurations = targets.map { OutputConfiguration(it) }
            val executor = Executor { runnable -> (handler ?: cameraHandler).post(runnable) }
            val sessionConfiguration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigurations, executor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) { if (cont.isActive) cont.resume(session) }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        Log.e("CAM_ENGINE_DEBUG", "Cam ${device.id} config failed. Session: $session")
                        if (cont.isActive) cont.resumeWithException(RuntimeException("Cam ${device.id} config failed"))
                    }
                }
            )
            device.createCaptureSession(sessionConfiguration)
        } catch (e: Exception) {
            Log.e("CAM_ENGINE_DEBUG", "Exception in createCaptureSession setup for Cam ${device.id}", e)
            if (cont.isActive) cont.resumeWithException(e)
        }
    }

    suspend fun initializeCamera() {
        Log.d("CAM_LIFECYCLE", "initializeCamera ENTER: Cam:'${viewState.cameraId}', ResIdx:${viewState.resolutionIndex}, FpsIdx:${viewState.fpsRangeIndex}, DispRot: ${viewState.displayRotationDegrees}")
        stopRunning()
        initializeCharacteristics() // Updated call

        if (characteristics == null || sizes.isEmpty() || this.fpsRanges.isEmpty() || this.actualHardwareFpsRanges.isEmpty()) {
            Log.e("CAM_LIFECYCLE", "Init fail: Missing characteristics/sizes/UI FPS/Hardware FPS.")
            updateView(); return
        }

        viewState.resolutionIndex = viewState.resolutionIndex?.takeIf { it >= 0 && it < sizes.size } ?: 0
        val currentFpsRangeIndex = viewState.fpsRangeIndex?.takeIf { it >= 0 && it < this.fpsRanges.size } ?: 0
        viewState.fpsRangeIndex = currentFpsRangeIndex
        this.effectiveFps = this.fpsRanges[currentFpsRangeIndex].lower
        var chosenHwFpsRange = actualHardwareFpsRanges.filter { it.lower >= this.effectiveFps }.minByOrNull { it.lower }
        if (chosenHwFpsRange == null) chosenHwFpsRange = actualHardwareFpsRanges.maxByOrNull { it.lower }
        this.actualCameraFps = chosenHwFpsRange?.lower ?: 30
        this.frameSkipRatio = if (this.effectiveFps > 0) max(1, floor(this.actualCameraFps.toDouble() / this.effectiveFps.toDouble()).toInt()) else 1
        streamFrameCounter = 0L
        // resW and resH now store the RAW camera output resolution selected by user.
        // CameraFragment will use these + sensorOrientation to calculate aspect ratio for SurfaceView.
        resW = sizes[viewState.resolutionIndex!!].width
        resH = sizes[viewState.resolutionIndex!!].height
        val selectedSdkFpsRange = Range(this.actualCameraFps, this.actualCameraFps)

        imageReader = ImageReader.newInstance(resW, resH, chosenOutputFormat, 4)
        camera = openCamera(cameraManager, viewState.cameraId, cameraHandler)
        val targets = mutableListOf(imageReader.surface)
        isShowingPreview = viewState.preview && !insidePause && previewSurface != null
        session = createCaptureSession(camera, targets, cameraHandler)

        val captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(imageReader.surface)
            set(CaptureRequest.JPEG_QUALITY, viewState.quality.toByte())
            if (chosenOutputFormat == ImageFormat.JPEG) {
                 val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                 set(CaptureRequest.JPEG_ORIENTATION, sensorOrientation)
            }
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, selectedSdkFpsRange)
            characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.let { modes ->
                if (modes.contains(CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                    set(CaptureRequest.CONTROL_AF_MODE, CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
            }
        }

        session?.setRepeatingRequest(captureRequest.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                streamFrameCounter++
                var processedBitmapForPreview: Bitmap? = null
                var finalBytesForStream = ByteArray(0)

                if (streamFrameCounter % frameSkipRatio.toLong() == 0L) {
                    imageReader.acquireLatestImage()?.use { img ->
                        try {
                            finalBytesForStream = when (img.format) {
                                ImageFormat.JPEG -> {
                                    val buffer = img.planes[0].buffer
                                    val bytes = ByteArray(buffer.remaining())
                                    buffer.get(bytes)
                                    bytes
                                }
                                ImageFormat.YUV_420_888 -> {
                                    val yPlane = img.planes[0]; val uPlane = img.planes[1]; val vPlane = img.planes[2]
                                    val yBuffer = yPlane.buffer; val uBuffer = uPlane.buffer; val vBuffer = vPlane.buffer
                                    val yByteArray = ByteArray(yBuffer.remaining()); yBuffer.get(yByteArray)
                                    val uByteArray = ByteArray(uBuffer.remaining()); uBuffer.get(uByteArray)
                                    val vByteArray = ByteArray(vBuffer.remaining()); vBuffer.get(vByteArray)
                                    val yuvData = YuvImageData(img.width, img.height,
                                        YuvPlaneData(yByteArray, yPlane.rowStride, yPlane.pixelStride),
                                        YuvPlaneData(uByteArray, uPlane.rowStride, uPlane.pixelStride),
                                        YuvPlaneData(vByteArray, vPlane.rowStride, vPlane.pixelStride), img.format)
                                    convertYuvToJpeg(yuvData)
                                }
                                else -> ByteArray(0).also { Log.w("CAM_ENGINE_DEBUG", "Unsupported image format: ${img.format}") }
                            }

                            if (isShowingPreview && finalBytesForStream.isNotEmpty()) {
                                val decodedBitmap = BitmapFactory.decodeByteArray(finalBytesForStream, 0, finalBytesForStream.size)
                                if (decodedBitmap != null && decodedBitmap.width > 0 && decodedBitmap.height > 0) {
                                    val sensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                                    val physicalDisplayRotation = viewState.displayRotationDegrees // From CameraFragment
                                    
                                    val rotationDegreesForPreview = (sensorOrientation - physicalDisplayRotation + 360) % 360
                                    // Log.v("CAM_ENGINE_PREVIEW_ROT", "Sensor: $sensorOrientation, ViewState DisplayRot: $physicalDisplayRotation -> PreviewBitmapRot: $rotationDegreesForPreview")

                                    if (rotationDegreesForPreview != 0) {
                                        val matrix = Matrix()
                                        matrix.postRotate(rotationDegreesForPreview.toFloat())
                                        processedBitmapForPreview = Bitmap.createBitmap(
                                            decodedBitmap, 0, 0, decodedBitmap.width, decodedBitmap.height, matrix, true
                                        )
                                        if (processedBitmapForPreview != decodedBitmap) {
                                            decodedBitmap.recycle()
                                        }
                                    } else {
                                        processedBitmapForPreview = decodedBitmap
                                    }
                                } else {
                                   Log.w("CAM_ENGINE_DEBUG", "BitmapFactory.decodeByteArray returned null or zero-dimension bitmap for preview.")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("CAM_ENGINE_DEBUG", "Frame ${streamFrameCounter}: Img Processing Error", e)
                            finalBytesForStream = ByteArray(0)
                            processedBitmapForPreview?.recycle()
                            processedBitmapForPreview = null
                        }
                    }
                } else {
                    imageReader.acquireLatestImage()?.use { /* Skip & close */ }
                }

                if (finalBytesForStream.isNotEmpty() && viewState.stream && http?.channel != null) {
                    http?.channel?.trySend(finalBytesForStream)
                }

                val currentSurface = previewSurface
                processedBitmapForPreview?.let { bmpToDraw ->
                    if (isShowingPreview && currentSurface != null && currentSurface.isValid) {
                        if (bmpToDraw.width > 0 && bmpToDraw.height > 0) {
                            try {
                                val canvas = currentSurface.lockCanvas(null)
                                if (canvas != null) {
                                   if (canvas.width > 0 && canvas.height > 0) {
                                        canvas.drawColor(Color.BLACK)
                                        val bmpWidth = bmpToDraw.width
                                        val bmpHeight = bmpToDraw.height
                                        val cvWidth = canvas.width
                                        val cvHeight = canvas.height
                                        val bmpAspectRatio = bmpWidth.toFloat() / bmpHeight.toFloat()
                                        val cvAspectRatio = cvWidth.toFloat() / cvHeight.toFloat()
                                        val srcRect = Rect(0, 0, bmpWidth, bmpHeight)
                                        val dstRect = Rect()
                                        if (bmpAspectRatio > cvAspectRatio) {
                                            val scaledHeight = (cvWidth / bmpAspectRatio).toInt().coerceAtLeast(1)
                                            val topOffset = (cvHeight - scaledHeight) / 2
                                            dstRect.set(0, topOffset, cvWidth, topOffset + scaledHeight)
                                        } else {
                                            val scaledWidth = (cvHeight * bmpAspectRatio).toInt().coerceAtLeast(1)
                                            val leftOffset = (cvWidth - scaledWidth) / 2
                                            dstRect.set(leftOffset, 0, leftOffset + scaledWidth, cvHeight)
                                        }
                                        canvas.drawBitmap(bmpToDraw, srcRect, dstRect, null)
                                    } else {
                                        Log.w("CAM_ENGINE_DEBUG", "Frame ${streamFrameCounter}: Canvas has zero dimensions.")
                                    }
                                    currentSurface.unlockCanvasAndPost(canvas)
                                } else {
                                     Log.w("CAM_ENGINE_DEBUG", "Frame ${streamFrameCounter}: lockCanvas() returned null.")
                                }
                            } catch (e: Exception) {
                                Log.e("CAM_ENGINE_DEBUG", "Frame ${streamFrameCounter}: Preview draw error", e)
                            }
                        } else {
                            Log.w("CAM_ENGINE_DEBUG", "Frame ${streamFrameCounter}: Bitmap to draw has zero dimensions (should have been caught earlier).")
                        }
                    } else {
                        Log.d("CAM_ENGINE_DEBUG", "Frame ${streamFrameCounter}: Processed bitmap not drawn. isShowingPreview: $isShowingPreview, Surface: $currentSurface isValid: ${currentSurface?.isValid}")
                    }
                    if (!bmpToDraw.isRecycled) {
                        bmpToDraw.recycle()
                    }
                }
            }
        }, cameraHandler)
        Log.d("CAM_LIFECYCLE", "initializeCamera: Success. Repeating request set. Cam '${viewState.cameraId}'.")
        updateView()
        Log.d("CAM_LIFECYCLE", "initializeCamera EXIT: Cam '${viewState.cameraId}'.")
    }

    fun destroy() {
        Log.d("CAM_LIFECYCLE", "Destroying CamEngine for ID '${viewState.cameraId}'.")
        stopRunning()
        cameraThread.quitSafely()
        engineScope.cancel()
        Log.d("CAM_LIFECYCLE", "CamEngine destroyed for ID '${viewState.cameraId}'.")
    }

    fun updateView() {
        try {
            if (cameraList.isEmpty()) {
                cameraList = Selector.enumerateCameras(cameraManager)
            }
            if (cameraList.isEmpty()) { 
                Log.e("CAM_ENGINE_DEBUG", "updateView: No cameras found even after re-enumeration.")
                val errorData = Data(emptyList(), Selector.SensorDesc("Error", "No Cam", ImageFormat.JPEG), 0, emptyList(), 0, ArrayList(), 0)
                context.sendBroadcast(Intent("UpdateFromCameraEngine").apply { setPackage(context.packageName); putExtra("data", errorData) })
                return
            }

            if (this.characteristics == null || sizes.isEmpty() || this.fpsRanges.isEmpty()) {
                Log.w("CAM_ENGINE_DEBUG", "updateView: Stale/uninit characteristics/sizes/fpsRanges. Attempting re-init.")
                initializeCharacteristics() 
                if (this.characteristics == null || sizes.isEmpty() || this.fpsRanges.isEmpty()) {
                    Log.e("CAM_ENGINE_DEBUG", "updateView: Still no characteristics/sizes/fpsRanges after re-init. Sending error data.")
                    val errorSensor = cameraList.firstOrNull() ?: Selector.SensorDesc("Error", "No Cam", ImageFormat.JPEG)
                    val currentSensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
                    val errorFpsRanges = ArrayList(desiredEffectiveFpsValues.map { ParcelableFpsRange(it,it) })
                    context.sendBroadcast(Intent("UpdateFromCameraEngine").apply { setPackage(context.packageName); putExtra("data", Data(cameraList, errorSensor, currentSensorOrientation, emptyList(), 0, errorFpsRanges, 0)) })
                    return
                }
            }
            
            val selectedSensorDesc = cameraList.find { it.cameraId == viewState.cameraId } ?: cameraList.first()
            val currentSensorOrientation = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

            val resolutionIndex = viewState.resolutionIndex?.takeIf { it >= 0 && it < sizes.size } ?: 0
            val fpsRangeIndex = viewState.fpsRangeIndex?.takeIf { it >= 0 && it < this.fpsRanges.size } ?: 0
            
            val data = Data(cameraList, selectedSensorDesc, currentSensorOrientation, sizes, resolutionIndex, this.fpsRanges, fpsRangeIndex)
            context.sendBroadcast(Intent("UpdateFromCameraEngine").apply { setPackage(context.packageName); putExtra("data", data) })
            Log.d("CAM_ENGINE_DEBUG", "updateView: Sent data for ${selectedSensorDesc.title} with sensor orientation: $currentSensorOrientation")
        } catch (e: Exception) { 
            Log.e("CAM_ENGINE_DEBUG", "Error in updateView", e) 
        }
    }

    companion object {
        @Parcelize
        data class Data(
            val sensors: List<Selector.SensorDesc>,
            val sensorSelected: Selector.SensorDesc,
            val sensorOrientation: Int, // Added field for sensor orientation
            val resolutions: List<Size>,
            val resolutionSelected: Int,
            val fpsRanges: ArrayList<ParcelableFpsRange>,
            val fpsRangeSelected: Int
        ) : Parcelable

        @Parcelize data class DataQuick(val ms: Int, val rateKbs: Int) : Parcelable
    }
}
