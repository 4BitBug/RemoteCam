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

package com.samsung.android.scan3d

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.samsung.android.scan3d.databinding.ActivityCameraBinding
import com.samsung.android.scan3d.http.HttpService
import com.samsung.android.scan3d.serv.Cam
import kotlinx.coroutines.channels.Channel


class CameraActivity : AppCompatActivity() {

    private lateinit var activityCameraBinding: ActivityCameraBinding
    // private var receiverRegistered = false // Will be replaced by specific receiver flags
    private var killReceiverRegistered = false
    private var finishActivityReceiverRegistered = false

    // Action defined in Cam.kt
    private val ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK = "com.samsung.android.scan3d.ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK"

    private val killReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i("CameraActivity", "Received KILL broadcast - DEPRECATED, should be handled by service stop now.")
            // finish() // This might be redundant if service handles full shutdown.
            // For safety, we can leave it, or if Cam service reliably calls kill(),
            // this receiver might become obsolete.
        }
    }

    private val activityFinishReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK) {
                Log.i("CameraActivity", "Received broadcast to finish and remove task.")
                finishAndRemoveTask()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("CAMERAACTIVITY", "CAMERAACTIVITY onCreate")
        
        try {
            activityCameraBinding = ActivityCameraBinding.inflate(layoutInflater)
            setContentView(activityCameraBinding.root)
            
            registerKillReceiver() // Keep for now, but review if it's still needed.
            registerFinishActivityReceiver()
            
            if (checkPermissions()) {
                Log.i("CameraActivity", "Permissions granted, starting camera service")
                startCameraService()
            } else {
                Log.e("CameraActivity", "Required permissions not granted")
                finish()
                return
            }
            
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error in onCreate", e)
            finish()
        }
    }

    private fun registerKillReceiver() {
        // This receiver handles the old "KILL" broadcast.
        // Its utility should be reviewed as the service now handles its own stop via intent.
        try {
            val intentFilter = IntentFilter("KILL")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(killReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(killReceiver, intentFilter)
            }
            killReceiverRegistered = true
            Log.i("CameraActivity", "Kill receiver registered (potentially deprecated)")
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error registering kill receiver", e)
        }
    }

    private fun registerFinishActivityReceiver() {
        try {
            val intentFilter = IntentFilter(ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(activityFinishReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(activityFinishReceiver, intentFilter)
            }
            finishActivityReceiverRegistered = true
            Log.i("CameraActivity", "FinishActivityReceiver registered successfully for action: $ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK")
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error registering FinishActivityReceiver", e)
        }
    }

    private fun checkPermissions(): Boolean {
        val requiredPermissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.FOREGROUND_SERVICE
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
        }
        
        val hasAllPermissions = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        
        Log.i("CameraActivity", "Permission check result: $hasAllPermissions")
        return hasAllPermissions
    }

    private fun startCameraService() {
        try {
            Log.i("CameraActivity", "Starting camera service")
            
            val intent = Intent(this, Cam::class.java)
            intent.action = "start"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            Log.i("CameraActivity", "Camera service start command sent")
            
        } catch (e: Exception) {
            Log.e("CameraActivity", "Failed to start camera service", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            sendCam {
                it.action = "onPause"
            }
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error in onPause", e)
        }
    }

    fun sendCam(extra: (Intent) -> Unit) {
        try {
            val intent = Intent(this, Cam::class.java)
            extra(intent)
            startService(intent) // Service should already be running, this just sends commands.
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error sending command to Cam service", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // It's important that the Cam service's kill() method (which calls stopSelf)
        // is the primary way the service stops. CameraActivity.onDestroy sending a "stop"
        // command can be a fallback, but the service stopping itself is more robust.
        // The service now also broadcasts to finish this activity, so direct call to stop service here
        // might be redundant if the flow always goes through service.kill().
        // try {
        //     sendCam {
        //         it.action = "stop"
        //     }
        // } catch (e: Exception) {
        //     Log.e("CameraActivity", "Error in onDestroy sending stop to service", e)
        // }
        
        if (killReceiverRegistered) {
            try {
                unregisterReceiver(killReceiver)
                killReceiverRegistered = false
                Log.i("CameraActivity", "Kill receiver unregistered")
            } catch (e: Exception) {
                Log.e("CameraActivity", "Error unregistering kill receiver", e)
            }
        }
        if (finishActivityReceiverRegistered) {
            try {
                unregisterReceiver(activityFinishReceiver)
                finishActivityReceiverRegistered = false
                Log.i("CameraActivity", "FinishActivityReceiver unregistered")
            } catch (e: Exception) {
                Log.e("CameraActivity", "Error unregistering FinishActivityReceiver", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            sendCam {
                it.action = "onResume"
            }
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error in onResume", e)
        }
    }
}
