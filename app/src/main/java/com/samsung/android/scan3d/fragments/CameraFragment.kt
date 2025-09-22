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
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.LayoutInflater
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.samsung.android.scan3d.CameraActivity
import com.samsung.android.scan3d.databinding.FragmentCameraBinding
import com.samsung.android.scan3d.http.HttpService
import com.samsung.android.scan3d.serv.Cam
import com.samsung.android.scan3d.serv.CamEngine
import com.samsung.android.scan3d.util.ClipboardUtil
import com.samsung.android.scan3d.util.IpUtil
import kotlinx.parcelize.Parcelize
import android.os.Build

class CameraFragment : Fragment() {

    /** Android ViewBinding */
    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private var receiverRegistered = false

    // These will store the EFFECTIVE width and height for the SurfaceView,
    // considering the rotation CamEngine applies to the bitmap.
    var resW = 1280 // Default effective width
    var resH = 720  // Default effective height

    var viewState = ViewState(true, stream = false, cameraId = "0", quality = 80, resolutionIndex = null, fpsRangeIndex = null, displayRotationDegrees = 0)

    lateinit var Cac: CameraActivity
    private lateinit var httpPrefs: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        Cac = (activity as CameraActivity?)!!
        httpPrefs = requireActivity().getSharedPreferences(HttpService.PREFS_NAME, Context.MODE_PRIVATE)


        val localIp = IpUtil.getLocalIpAddress()
        _fragmentCameraBinding!!.textView6.text = "$localIp:59713"
        _fragmentCameraBinding!!.textView6.setOnClickListener {
            context?.let { ctx ->
                ClipboardUtil.copyToClipboard(ctx, "ip", _fragmentCameraBinding!!.textView6.text.toString())
                Toast.makeText(ctx, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        }
        return fragmentCameraBinding.root
    }

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.d(TAG, "Broadcast received: ${intent.action}")
            when (intent.action) {
                "UpdateFromCameraEngine" -> {
                    handleCameraEngineUpdate(intent)
                }
                else -> {
                    Log.w(TAG, "Unknown broadcast action: ${intent.action}")
                }
            }
        }
    }

    private fun handleCameraEngineUpdate(intent: Intent) {
        val dataQuick: CamEngine.Companion.DataQuick? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.extras?.getParcelable("dataQuick", CamEngine.Companion.DataQuick::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.extras?.getParcelable("dataQuick")
        }
        dataQuick?.let { updateQuickData(it) }

        val data: CamEngine.Companion.Data? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.extras?.getParcelable("data", CamEngine.Companion.Data::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.extras?.getParcelable("data")
        }
        data?.let { updateMainData(it) }
    }
    
    private fun updateDisplayRotationStateIfNeeded() {
        val currentDisplayRotationDegrees = when (activity?.windowManager?.defaultDisplay?.rotation) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> {
                Log.w(TAG, "Unknown display rotation, defaulting to 0")
                0
            }
        }
        if (viewState.displayRotationDegrees != currentDisplayRotationDegrees) {
            Log.d(TAG, "Display rotation changed to: $currentDisplayRotationDegrees degrees")
            viewState.displayRotationDegrees = currentDisplayRotationDegrees
            sendViewState() // This will trigger CamEngine to potentially resend data if it considers displayRotation
                           // And also CameraFragment will re-evaluate aspect ratio on next data update if needed.
        }
    }

    private fun updateQuickData(dataQuick: CamEngine.Companion.DataQuick) {
        activity?.runOnUiThread {
            if (!isAdded || _fragmentCameraBinding == null) return@runOnUiThread
            fragmentCameraBinding.qualFeedback?.text = " ${dataQuick.rateKbs}kB/sec"
            fragmentCameraBinding.ftFeedback?.text = " ${dataQuick.ms}ms"
        }
    }

    private fun updateMainData(data: CamEngine.Companion.Data) {
        if (data.resolutions.isNotEmpty() && data.resolutionSelected >= 0 && data.resolutionSelected < data.resolutions.size) {
            val rawCamResolution = data.resolutions[data.resolutionSelected]
            val rawCamWidth = rawCamResolution.width
            val rawCamHeight = rawCamResolution.height
            val sensorOrientation = data.sensorOrientation
            val currentDisplayRotation = viewState.displayRotationDegrees // This is already updated by updateDisplayRotationStateIfNeeded

            // This calculation must mirror the rotation logic in CamEngine.kt to correctly predict the final bitmap dimensions.
            val totalBitmapRotation = (sensorOrientation - currentDisplayRotation + 360) % 360

            Log.d(TAG, "updateMainData: RawCam[${rawCamWidth}x${rawCamHeight}], SensorOrient: $sensorOrientation, DispRot: $currentDisplayRotation, TotalBitmapRot: $totalBitmapRotation")

            if (totalBitmapRotation == 90 || totalBitmapRotation == 270) {
                // If bitmap is rotated by 90/270, its width becomes height for the preview, and height becomes width.
                resW = rawCamHeight // Effective width for SurfaceView
                resH = rawCamWidth  // Effective height for SurfaceView
            } else {
                // If bitmap is rotated by 0/180 (or not at all), its dimensions map directly.
                resW = rawCamWidth  // Effective width for SurfaceView
                resH = rawCamHeight // Effective height for SurfaceView
            }
            Log.d(TAG, "updateMainData: Effective SurfaceView Res [${resW}x${resH}]")
        }

        activity?.runOnUiThread {
             if (!isAdded || _fragmentCameraBinding == null) return@runOnUiThread
            updateUIWithData(data) // This will call setAspectRatio with the new resW, resH
        }
    }

    private fun updateUIWithData(data: CamEngine.Companion.Data) {
        // resW and resH are now effective dimensions for the SurfaceView
        if (resW > 0 && resH > 0) {
            fragmentCameraBinding.viewFinder.setAspectRatio(resW, resH)
        }
        setupSwitches(data)
        setupCameraSpinner(data)
        setupQualitySpinner(data)
        setupResolutionSpinner(data)
        setupFpsSpinner(data)
    }

    private fun setupSwitches(data: CamEngine.Companion.Data) {
        fragmentCameraBinding.switch1?.setOnCheckedChangeListener(null)
        fragmentCameraBinding.switch2?.setOnCheckedChangeListener(null)
        fragmentCameraBinding.switch1?.isChecked = viewState.preview 
        fragmentCameraBinding.switch2?.isChecked = viewState.stream
        fragmentCameraBinding.switch1?.setOnCheckedChangeListener { _, isChecked ->
            if (viewState.preview != isChecked) {
                viewState.preview = isChecked
                sendViewState()
            }
        }
        fragmentCameraBinding.switch2?.setOnCheckedChangeListener { _, isChecked ->
            if (viewState.stream != isChecked) {
                viewState.stream = isChecked
                sendViewState()
            }
        }
    }

    private fun setupCameraSpinner(data: CamEngine.Companion.Data) {
        val spinner = fragmentCameraBinding.spinnerCam
        spinner.onItemSelectedListener = null
        val spinnerDataList = data.sensors.map { it.title }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerDataList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val selectedIndex = data.sensors.indexOfFirst { it.cameraId == data.sensorSelected.cameraId }
        if (selectedIndex >= 0) spinner.setSelection(selectedIndex)
        else if (data.sensors.isNotEmpty()) spinner.setSelection(0)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position < data.sensors.size) {
                    val newCameraId = data.sensors[position].cameraId
                    if (viewState.cameraId != newCameraId) {
                        viewState.cameraId = newCameraId
                        viewState.resolutionIndex = null 
                        viewState.fpsRangeIndex = null
                        sendViewState()
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupQualitySpinner(data: CamEngine.Companion.Data) {
        val spinner = fragmentCameraBinding.spinnerQua
        spinner.onItemSelectedListener = null
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
                    viewState.quality = newQuality
                    sendViewState()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupResolutionSpinner(data: CamEngine.Companion.Data) {
        val spinner = fragmentCameraBinding.spinnerRes
        spinner.onItemSelectedListener = null
        if (data.resolutions.isEmpty()) {
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
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (data.resolutionSelected != position) { 
                    viewState.resolutionIndex = position
                    sendViewState()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupFpsSpinner(data: CamEngine.Companion.Data) {
        val spinner = fragmentCameraBinding.spinnerFps
        spinner.onItemSelectedListener = null
        if (data.fpsRanges.isEmpty()) {
            spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, listOf("N/A"))
            spinner.isEnabled = false
            return
        }
        spinner.isEnabled = true
        val spinnerDataList = data.fpsRanges.map { "${it.lower} FPS" }
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, spinnerDataList)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        val fpsIndex = data.fpsRangeSelected.takeIf { it >= 0 && it < data.fpsRanges.size } ?: 0
        spinner.setSelection(fpsIndex)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (data.fpsRangeSelected != position) {
                    viewState.fpsRangeIndex = position
                    sendViewState()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun loadAndDisplayCurrentPassword() {
        val currentPassword = httpPrefs.getString(HttpService.KEY_HTTP_PASSWORD, "password") // Default to "password"
        fragmentCameraBinding.editTextNewPassword.setText(currentPassword)
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
        Log.d(TAG, "onViewCreated")

        loadAndDisplayCurrentPassword() // Load and display the current password

        val intentFilter = IntentFilter("UpdateFromCameraEngine")
        val appContext = requireActivity().applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, intentFilter)
        }
        receiverRegistered = true

        updateDisplayRotationStateIfNeeded() 

        view.postDelayed({ Cac.sendCam { it.action = "start_camera_engine" } }, 100)
        view.postDelayed({ Cac.sendCam { it.action = "request_sensor_data" } }, 500)

        fragmentCameraBinding.buttonKill.setOnClickListener {
            Log.i(TAG, "User Kill button clicked, sending 'user_kill' action to service.")
            Cac.sendCam { it.action = "user_kill" }
            view.postDelayed({
                Log.i(TAG, "Attempting to finish CameraActivity directly from fragment.")
                activity?.finishAndRemoveTask()
            }, 200)
        }

        fragmentCameraBinding.buttonChangePassword.setOnClickListener {
            val newPassword = fragmentCameraBinding.editTextNewPassword.text.toString()
            if (newPassword.isNotBlank()) {
                Cac.sendCam {
                    it.action = Cam.ACTION_CHANGE_PASSWORD
                    it.putExtra(Cam.EXTRA_NEW_PASSWORD, newPassword)
                }
                Toast.makeText(requireContext(), "Password change requested.", Toast.LENGTH_SHORT).show()
                // Don't clear the password here, so it continues to show the current (newly set) password
            } else {
                Toast.makeText(requireContext(), "Password cannot be empty.", Toast.LENGTH_SHORT).show()
            }
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "Surface destroyed")
                Cac.sendCam {
                    it.action = "new_preview_surface"
                    it.putExtra("surface", null as Surface?)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "Surface changed: ${width}x${height}")
                updateDisplayRotationStateIfNeeded()
                 if (resW > 0 && resH > 0) {
                    fragmentCameraBinding.viewFinder.setAspectRatio(resW, resH)
                }
                Cac.sendCam {
                    it.action = "new_preview_surface"
                    it.putExtra("surface", holder.surface)
                }
            }

            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "Surface created")
                if (holder.surface != null && holder.surface.isValid) {
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
                    Log.w(TAG, "Surface invalid on creation")
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        loadAndDisplayCurrentPassword() // Also load/refresh password on resume
        if (!receiverRegistered) {
            val intentFilter = IntentFilter("UpdateFromCameraEngine")
            val appContext = requireActivity().applicationContext
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(receiver, intentFilter)
            }
            receiverRegistered = true
        }
        updateDisplayRotationStateIfNeeded()
        Cac.sendCam { it.action = "request_sensor_data" } 
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        if (receiverRegistered) {
            try {
                requireActivity().applicationContext.unregisterReceiver(receiver)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver not registered during onPause: ${e.message}")
            }
            receiverRegistered = false
        }
    }

    override fun onDestroyView() {
        Log.d(TAG, "onDestroyView")
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
            var fpsRangeIndex: Int? = null, 
            var displayRotationDegrees: Int = 0
        ) : Parcelable
    }
}
