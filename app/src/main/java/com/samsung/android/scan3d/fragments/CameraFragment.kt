/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.samsung.android.scan3d.fragments

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.LayoutInflater
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.Navigation
import com.example.android.camera.utils.OrientationLiveData
import com.samsung.android.scan3d.CameraActivity
import com.samsung.android.scan3d.R
import com.samsung.android.scan3d.databinding.FragmentCameraBinding
import com.samsung.android.scan3d.serv.Cam
import com.samsung.android.scan3d.serv.CamEngine
import com.samsung.android.scan3d.util.ClipboardUtil
import com.samsung.android.scan3d.util.IpUtil
import kotlinx.parcelize.Parcelize
import android.os.Build
import android.view.Surface

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private var receiverRegistered = false

    var resW = 1280
    var resH = 720

    var viewState = ViewState(true, stream = false, cameraId = "0", quality = 80, resolutionIndex = null, fpsRangeIndex = null)

    lateinit var Cac: CameraActivity

    /** Live data listener for changes in the device orientation relative to the camera */
    private lateinit var relativeOrientation: OrientationLiveData

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)

        // Get the local ip address
        val localIp = IpUtil.getLocalIpAddress()
        _fragmentCameraBinding!!.textView6.text = "$localIp:8080/cam.mjpeg"
        _fragmentCameraBinding!!.textView6.setOnClickListener {
            // Copy the ip address to the clipboard
            ClipboardUtil.copyToClipboard(context, "ip", _fragmentCameraBinding!!.textView6.text.toString())
            // Toast to notify the user
            Toast.makeText(context, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
        }

        Cac = (activity as CameraActivity?)!!
        return fragmentCameraBinding.root
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "收到广播: ${intent.action}")
            Log.i(TAG, "广播extras: ${intent.extras?.keySet()?.joinToString()}")
            
            when (intent.action) {
                "UpdateFromCameraEngine" -> {
                    Log.i(TAG, "处理UpdateFromCameraEngine广播")
                    handleCameraEngineUpdate(intent)
                }
                else -> {
                    Log.w(TAG, "未知广播动作: ${intent.action}")
                }
            }
        }
    }

    private fun handleCameraEngineUpdate(intent: Intent) {
        try {
            Log.i(TAG, "开始处理摄像头引擎更新")
            
            // 处理快速数据更新
            val dataQuick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.extras?.getParcelable("dataQuick", CamEngine.Companion.DataQuick::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.extras?.getParcelable("dataQuick")
            }
            
            if (dataQuick != null) {
                Log.i(TAG, "收到快速数据: ms=${dataQuick.ms}, rateKbs=${dataQuick.rateKbs}")
                updateQuickData(dataQuick)
            }

            // 处理主要数据更新
            val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.extras?.getParcelable("data", CamEngine.Companion.Data::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.extras?.getParcelable("data")
            }
            
            if (data != null) {
                Log.i(TAG, "收到摄像头数据: ${data.sensors.size}个传感器, ${data.fpsRanges.size} FPS ranges, selected FPS index: ${data.fpsRangeSelected}")
                data.sensors.forEach { sensor ->
                    Log.i(TAG, "传感器: ${sensor.cameraId} - ${sensor.title}")
                }
                updateMainData(data)
            } else {
                Log.w(TAG, "数据为空")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "处理摄像头引擎更新时出错", e)
        }
    }

    private fun updateQuickData(dataQuick: CamEngine.Companion.DataQuick) {
        activity?.runOnUiThread {
            try {
                if (!isAdded || activity == null || isDetached || _fragmentCameraBinding == null) {
                    return@runOnUiThread
                }
                
                fragmentCameraBinding.qualFeedback?.text = " ${dataQuick.rateKbs}kB/sec"
                fragmentCameraBinding.ftFeedback?.text = " ${dataQuick.ms}ms"
                
            } catch (e: Exception) {
                Log.e(TAG, "更新快速数据时出错", e)
            }
        }
    }

    private fun updateMainData(data: CamEngine.Companion.Data) {
        // 更新分辨率
        try {
            if (data.resolutions.isNotEmpty() && data.resolutionSelected >= 0 && data.resolutionSelected < data.resolutions.size) {
                val re = data.resolutions[data.resolutionSelected]
                resW = re.width
                resH = re.height
                Log.i(TAG, "分辨率更新为: ${resW}x${resH}")
            } else {
                 Log.w(TAG, "无效的分辨率数据或索引: selected=${data.resolutionSelected}, size=${data.resolutions.size}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "更新分辨率时出错", e)
        }

        // 确保在主线程上更新UI
        if (Thread.currentThread() == android.os.Looper.getMainLooper().thread) {
            updateUIWithData(data)
        } else {
            activity?.runOnUiThread {
                try {
                    updateUIWithData(data)
                } catch (e: Exception) {
                    Log.e(TAG, "更新UI时出错", e)
                }
            }
        }
    }

    private fun updateUIWithData(data: CamEngine.Companion.Data) {
        if (!isAdded || activity == null || isDetached || _fragmentCameraBinding == null) {
            return
        }

        // 更新预览比例
        if (resW > 0 && resH > 0) {
            fragmentCameraBinding.viewFinder.setAspectRatio(resW, resH)
        }

        // 设置开关
        setupSwitches()

        // 设置摄像头选择器
        setupCameraSpinner(data)

        // 设置质量选择器
        setupQualitySpinner()

        // 设置分辨率选择器
        setupResolutionSpinner(data)

        // 设置FPS选择器
        setupFpsSpinner(data)
    }

    private fun setupSwitches() {
        fragmentCameraBinding.switch1?.setOnCheckedChangeListener(null) // Clear listener before setting checked state
        fragmentCameraBinding.switch2?.setOnCheckedChangeListener(null)

        fragmentCameraBinding.switch1?.isChecked = viewState.preview
        fragmentCameraBinding.switch2?.isChecked = viewState.stream
        
        Log.i(TAG, "开关初始状态: preview=${viewState.preview}, stream=${viewState.stream}")
        
        fragmentCameraBinding.switch1?.setOnCheckedChangeListener { _, isChecked ->
            if (viewState.preview != isChecked) {
                Log.i(TAG, "预览开关切换: $isChecked")
                viewState.preview = isChecked
                sendViewState()
            }
        }
        
        fragmentCameraBinding.switch2?.setOnCheckedChangeListener { _, isChecked ->
            if (viewState.stream != isChecked) {
                Log.i(TAG, "流媒体开关切换: $isChecked")
                viewState.stream = isChecked
                sendViewState()
            }
        }
    }

    private fun setupCameraSpinner(data: CamEngine.Companion.Data) {
        Log.i(TAG, "设置摄像头Spinner: ${data.sensors.size}个传感器")
        
        val spinner = fragmentCameraBinding.spinnerCam
        spinner.onItemSelectedListener = null // Clear listener

        val spinnerDataList = data.sensors.map { it.title }
        Log.i(TAG, "摄像头Spinner数据列表: ${spinnerDataList.joinToString()}")

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerDataList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val selectedIndex = data.sensors.indexOf(data.sensorSelected)
        if (selectedIndex >= 0) {
            spinner.setSelection(selectedIndex)
            Log.i(TAG, "设置摄像头Spinner选中项: $selectedIndex")
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < data.sensors.size) {
                    val newCameraId = data.sensors[position].cameraId
                    if (viewState.cameraId != newCameraId) {
                        Log.i(TAG, "摄像头已选择: $newCameraId")
                        viewState.cameraId = newCameraId
                        viewState.resolutionIndex = null // Reset resolution on camera change
                        viewState.fpsRangeIndex = null // Reset FPS on camera change
                        sendViewState()
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        Log.i(TAG, "摄像头Spinner设置完成")
    }

    private fun setupQualitySpinner() {
        val spinner = fragmentCameraBinding.spinnerQua
        spinner.onItemSelectedListener = null // Clear listener

        val quals = arrayOf(1, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
        val spinnerDataList = quals.map { it.toString() }
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerDataList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        
        val qualityIndex = quals.indexOfFirst { it == viewState.quality }.takeIf { it >=0 } ?: quals.indexOf(80)
        spinner.setSelection(qualityIndex)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val newQuality = quals[position]
                if (viewState.quality != newQuality) {
                    Log.i(TAG, "质量已选择: $newQuality")
                    viewState.quality = newQuality
                    sendViewState()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupResolutionSpinner(data: CamEngine.Companion.Data) {
        val spinner = fragmentCameraBinding.spinnerRes
        spinner.onItemSelectedListener = null // Clear listener
        
        if (data.resolutions.isEmpty()) {
            Log.w(TAG, "No resolutions available for camera ${viewState.cameraId}")
            spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf("N/A"))
            spinner.isEnabled = false
            return
        }
        spinner.isEnabled = true

        val spinnerDataList = data.resolutions.map { it.toString() }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerDataList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val resolutionIndex = data.resolutionSelected.takeIf { it >= 0 && it < data.resolutions.size } ?: 0
        spinner.setSelection(resolutionIndex)
        Log.i(TAG, "设置分辨率Spinner选中项: $resolutionIndex (ViewState says: ${viewState.resolutionIndex})")

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (viewState.resolutionIndex != position) {
                     Log.i(TAG, "分辨率已选择: position $position, value ${data.resolutions.getOrNull(position)}")
                    viewState.resolutionIndex = position
                    sendViewState()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFpsSpinner(data: CamEngine.Companion.Data) {
        val spinner = fragmentCameraBinding.spinnerFps // Make sure this ID (spinner_fps) exists in your XML
        spinner.onItemSelectedListener = null // Clear listener

        if (data.fpsRanges.isEmpty()) {
            Log.w(TAG, "No FPS ranges available for camera ${viewState.cameraId}")
            spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf("N/A"))
            spinner.isEnabled = false
            return
        }
        spinner.isEnabled = true

        val spinnerDataList = data.fpsRanges.map { "${it.lower} FPS" } // Format for display
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerDataList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        // data.fpsRangeSelected is already a safe index from CamEngine
        val fpsIndex = data.fpsRangeSelected.takeIf { it >= 0 && it < data.fpsRanges.size } ?: 0
        spinner.setSelection(fpsIndex)
        Log.i(TAG, "设置FPS Spinner选中项: $fpsIndex (ViewState says: ${viewState.fpsRangeIndex})")

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                // Only trigger if the selection has actually changed from current viewState
                val currentIndexInViewState = viewState.fpsRangeIndex ?: -1 // Treat null as different from any valid position
                if (currentIndexInViewState != position) {
                    Log.i(TAG, "FPS范围已选择: position $position, value ${data.fpsRanges.getOrNull(position)}")
                    viewState.fpsRangeIndex = position
                    sendViewState()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        Log.i(TAG, "FPS Spinner设置完成")
    }


    fun sendViewState() {
        Cac.sendCam {
            it.action = "new_view_state"
            it.putExtra("data", viewState)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        try {
            val intentFilter = IntentFilter("UpdateFromCameraEngine")
            val appContext = requireActivity().applicationContext
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, intentFilter)
            }
            receiverRegistered = true
            Log.i(TAG, "广播接收器已注册")
        } catch (e: Exception) {
            Log.e(TAG, "注册广播接收器失败", e)
        }

        view.postDelayed({
            try {
                Log.i(TAG, "启动摄像头引擎")
                Cac.sendCam { it.action = "start_camera_engine" }
            } catch (e: Exception) {
                Log.e(TAG, "启动摄像头引擎失败", e)
            }
        }, 500)

        view.postDelayed({
            try {
                Log.i(TAG, "请求传感器数据")
                Cac.sendCam { it.action = "request_sensor_data" }
            } catch (e: Exception) {
                Log.e(TAG, "请求传感器数据失败", e)
            }
        }, 1500)

        fragmentCameraBinding.buttonKill.setOnClickListener {
            Log.i(TAG, "User Kill button clicked, sending 'user_kill' action to service.")
            Cac.sendCam {
                it.action = "user_kill"
            }
            // Directly finish the activity after telling the service to shut down.
            // Give a brief moment for the service to process the command.
            view.postDelayed({
                Log.i(TAG, "Attempting to finish CameraActivity directly from fragment.")
                activity?.finishAndRemoveTask()
            }, 200) 
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "Surface销毁")
                Cac.sendCam {
                    it.action = "new_preview_surface"
                    it.putExtra("surface", null as Surface?)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "Surface改变: ${width}x${height}, 格式: $format")
                if (resW > 0 && resH > 0) {
                     fragmentCameraBinding.viewFinder.setAspectRatio(resW, resH)
                }
                Cac.sendCam {
                    it.action = "new_preview_surface"
                    it.putExtra("surface", holder.surface)
                }
            }

            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "Surface创建")
                if (holder.surface != null && holder.surface.isValid) {
                    Log.i(TAG, "Surface有效，设置预览")
                     if (resW > 0 && resH > 0) {
                        fragmentCameraBinding.viewFinder.setAspectRatio(resW, resH)
                    }
                    fragmentCameraBinding.viewFinder.postDelayed({
                        Cac.sendCam {
                            it.action = "new_preview_surface"
                            it.putExtra("surface", holder.surface)
                        }
                    }, 100)
                } else {
                    Log.w(TAG, "Surface无效")
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) {
            try {
                requireActivity().applicationContext.unregisterReceiver(receiver)
                receiverRegistered = false
                Log.i(TAG, "广播接收器已注销")
            } catch (e: Exception) {
                Log.e(TAG, "取消注册广播接收器失败", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
             try {
                val intentFilter = IntentFilter("UpdateFromCameraEngine")
                val appContext = requireActivity().applicationContext
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    appContext.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    appContext.registerReceiver(receiver, intentFilter)
                }
                receiverRegistered = true
                Log.i(TAG, "广播接收器已在onResume中重新注册")
            } catch (e: Exception) {
                Log.e(TAG, "在onResume中重新注册广播接收器失败", e)
            }
        }
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    companion object {
        private val TAG = CameraFragment::class.java.simpleName

        @Parcelize
        data class ViewState(
            var preview: Boolean,
            var stream: Boolean,
            var cameraId: String,
            var resolutionIndex: Int?,
            var quality: Int,
            var fpsRangeIndex: Int? = null // Added this line
        ) : Parcelable
    }
}
