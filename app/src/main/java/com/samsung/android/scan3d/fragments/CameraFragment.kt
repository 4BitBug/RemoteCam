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
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.navigation.NavController
import androidx.navigation.Navigation
import com.example.android.camera.utils.OrientationLiveData
import com.samsung.android.scan3d.CameraActivity
import com.samsung.android.scan3d.R
import com.samsung.android.scan3d.databinding.FragmentCameraBinding
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

    var viewState = ViewState(true, stream = false, cameraId = "0", quality = 80, resolutionIndex = null)

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
            Log.i("CameraFragment", "收到广播: ${intent.action}")
            Log.i("CameraFragment", "广播extras: ${intent.extras?.keySet()?.joinToString()}")
            
            when (intent.action) {
                "UpdateFromCameraEngine" -> {
                    Log.i("CameraFragment", "处理UpdateFromCameraEngine广播")
                    handleCameraEngineUpdate(intent)
                }
                else -> {
                    Log.w("CameraFragment", "未知广播动作: ${intent.action}")
                }
            }
        }
    }

    private fun handleCameraEngineUpdate(intent: Intent) {
        try {
            Log.i("CameraFragment", "开始处理摄像头引擎更新")
            
            // 处理快速数据更新
            val dataQuick = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.extras?.getParcelable("dataQuick", CamEngine.Companion.DataQuick::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.extras?.getParcelable("dataQuick")
            }
            
            if (dataQuick != null) {
                Log.i("CameraFragment", "收到快速数据: ms=${dataQuick.ms}, rateKbs=${dataQuick.rateKbs}")
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
                Log.i("CameraFragment", "收到摄像头数据: ${data.sensors.size}个传感器")
                data.sensors.forEach { sensor ->
                    Log.i("CameraFragment", "传感器: ${sensor.cameraId} - ${sensor.title}")
                }
                updateMainData(data)
            } else {
                Log.w("CameraFragment", "数据为空")
            }
            
        } catch (e: Exception) {
            Log.e("CameraFragment", "处理摄像头引擎更新时出错", e)
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
                Log.e("CameraFragment", "更新快速数据时出错", e)
            }
        }
    }

    private fun updateMainData(data: CamEngine.Companion.Data) {
        // 更新分辨率
        try {
            val re = data.resolutions[data.resolutionSelected]
            resW = re.width
            resH = re.height
            Log.i("CameraFragment", "分辨率更新为: ${resW}x${resH}")
        } catch (e: Exception) {
            Log.e("CameraFragment", "更新分辨率时出错", e)
        }

        // 确保在主线程上更新UI
        if (Thread.currentThread() == android.os.Looper.getMainLooper().thread) {
            // 已经在主线程
            updateUIWithData(data)
        } else {
            // 切换到主线程
            activity?.runOnUiThread {
                try {
                    updateUIWithData(data)
                } catch (e: Exception) {
                    Log.e("CameraFragment", "更新UI时出错", e)
                }
            }
        }
    }

    private fun updateUIWithData(data: CamEngine.Companion.Data) {
        if (!isAdded || activity == null || isDetached || _fragmentCameraBinding == null) {
            return
        }

        // 更新预览比例
        fragmentCameraBinding.viewFinder.setAspectRatio(resW, resH)

        // 设置开关
        setupSwitches()

        // 设置摄像头选择器
        setupCameraSpinner(data)

        // 设置质量选择器
        setupQualitySpinner()

        // 设置分辨率选择器
        setupResolutionSpinner(data)
    }

    private fun setupSwitches() {
        // 设置开关初始状态
        fragmentCameraBinding.switch1?.isChecked = viewState.preview
        fragmentCameraBinding.switch2?.isChecked = viewState.stream
        
        Log.i("CameraFragment", "开关初始状态: preview=${viewState.preview}, stream=${viewState.stream}")
        
        fragmentCameraBinding.switch1?.setOnCheckedChangeListener { _, isChecked ->
            Log.i("CameraFragment", "预览开关切换: $isChecked")
            viewState.preview = isChecked
            sendViewState()
        }
        
        fragmentCameraBinding.switch2?.setOnCheckedChangeListener { _, isChecked ->
            Log.i("CameraFragment", "流媒体开关切换: $isChecked")
            viewState.stream = isChecked
            sendViewState()
        }
    }

    private fun setupCameraSpinner(data: CamEngine.Companion.Data) {
        Log.i("CameraFragment", "设置摄像头Spinner: ${data.sensors.size}个传感器")
        
        val spinner = fragmentCameraBinding.spinnerCam
        val spinnerDataList = data.sensors.map { it.title }
        
        Log.i("CameraFragment", "Spinner数据列表: ${spinnerDataList.joinToString()}")

        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerDataList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val selectedIndex = data.sensors.indexOf(data.sensorSelected)
        if (selectedIndex >= 0) {
            spinner.setSelection(selectedIndex)
            Log.i("CameraFragment", "设置Spinner选中项: $selectedIndex")
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position >= 0 && position < data.sensors.size) {
                    if (viewState.cameraId != data.sensors[position].cameraId) {
                        viewState.resolutionIndex = null
                    }
                    viewState.cameraId = data.sensors[position].cameraId
                    Log.i("CameraFragment", "摄像头已选择: ${data.sensors[position].cameraId}")
                    sendViewState()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        
        Log.i("CameraFragment", "摄像头Spinner设置完成")
    }

    private fun setupQualitySpinner() {
        val spinner = fragmentCameraBinding.spinnerQua
        val quals = arrayOf(1, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
        val spinnerDataList = quals.map { it.toString() }
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerDataList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        
        val qualityIndex = quals.indexOfFirst { it == viewState.quality }
        if (qualityIndex >= 0) {
            spinner.setSelection(qualityIndex)
        }

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewState.quality = quals[position]
                sendViewState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupResolutionSpinner(data: CamEngine.Companion.Data) {
        val spinner = fragmentCameraBinding.spinnerRes
        
        val spinnerDataList = data.resolutions.map { it.toString() }
        
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerDataList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        if (viewState.resolutionIndex == null) {
            viewState.resolutionIndex = 0
        }
        
        val resolutionIndex = if (viewState.resolutionIndex!! < data.resolutions.size) {
            viewState.resolutionIndex!!
        } else {
            0
        }
        
        spinner.setSelection(resolutionIndex)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                viewState.resolutionIndex = position
                sendViewState()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
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

        // 注册广播接收器 - 使用应用级Context确保能接收到同包广播
        try {
            val intentFilter = IntentFilter("UpdateFromCameraEngine")
            
            // 获取应用上下文，确保广播接收的稳定性
            val appContext = requireActivity().applicationContext
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ 需要明确指定接收器类型
                appContext.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13
                appContext.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                // Android 13以下
                appContext.registerReceiver(receiver, intentFilter)
            }
            receiverRegistered = true
            Log.i("CameraFragment", "广播接收器已注册到ApplicationContext")
            
            // 同时在Activity上也注册一个，双重保险
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requireActivity().registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    requireActivity().registerReceiver(receiver, intentFilter)
                }
                Log.i("CameraFragment", "广播接收器也注册到Activity")
            } catch (e: Exception) {
                Log.w("CameraFragment", "Activity注册失败，但ApplicationContext注册成功", e)
            }
            
        } catch (e: Exception) {
            Log.e("CameraFragment", "注册广播接收器失败", e)
        }

        // 延迟启动摄像头引擎，确保Fragment完全初始化
        view.postDelayed({
            try {
                Log.i("CameraFragment", "启动摄像头引擎")
                Cac.sendCam {
                    it.action = "start_camera_engine"
                }
            } catch (e: Exception) {
                Log.e("CameraFragment", "启动摄像头引擎失败", e)
            }
        }, 500)

        // 再次延迟请求传感器数据
        view.postDelayed({
            try {
                Log.i("CameraFragment", "请求传感器数据")
                Cac.sendCam {
                    it.action = "request_sensor_data"
                }
            } catch (e: Exception) {
                Log.e("CameraFragment", "请求传感器数据失败", e)
            }
        }, 1500)

        // Kill按钮
        fragmentCameraBinding.buttonKill.setOnClickListener {
            Log.i("CameraFragment", "停止服务")
            val intent = Intent("KILL")
            requireActivity().sendBroadcast(intent)
        }

        // Surface回调
        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i("CameraFragment", "Surface销毁")
                Cac.sendCam {
                    it.action = "new_preview_surface"
                    it.putExtra("surface", null as Surface?)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i("CameraFragment", "Surface改变: ${width}x${height}, 格式: $format")
                // Surface大小改变时也需要更新
                fragmentCameraBinding.viewFinder.setAspectRatio(resW, resH)
                Cac.sendCam {
                    it.action = "new_preview_surface"
                    it.putExtra("surface", holder.surface)
                }
            }

            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i("CameraFragment", "Surface创建")
                
                // 确保Surface有效
                if (holder.surface != null && holder.surface.isValid) {
                    Log.i("CameraFragment", "Surface有效，设置预览")
                    fragmentCameraBinding.viewFinder.setAspectRatio(resW, resH)
                    
                    // 延迟一点发送Surface，确保Surface完全准备好
                    fragmentCameraBinding.viewFinder.postDelayed({
                        Cac.sendCam {
                            it.action = "new_preview_surface"
                            it.putExtra("surface", holder.surface)
                        }
                    }, 100)
                } else {
                    Log.w("CameraFragment", "Surface无效")
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        if (receiverRegistered) {
            try {
                // 从ApplicationContext注销
                requireActivity().applicationContext.unregisterReceiver(receiver)
                // 也尝试从Activity注销
                try {
                    requireActivity().unregisterReceiver(receiver)
                } catch (e: Exception) {
                    // 忽略Activity注销失败
                }
                receiverRegistered = false
                Log.i("CameraFragment", "广播接收器已注销")
            } catch (e: Exception) {
                Log.e("CameraFragment", "取消注册广播接收器失败", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 广播接收器在onViewCreated中注册
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
            var quality: Int
        ) : Parcelable
    }
}
