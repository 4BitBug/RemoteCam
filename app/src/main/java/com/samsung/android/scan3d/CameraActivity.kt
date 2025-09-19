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
    private var killReceiverRegistered = false
    private var finishActivityReceiverRegistered = false

    // Action defined in Cam.kt
    private val ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK = "com.samsung.android.scan3d.ACTION_FINISH_ACTIVITY_AND_REMOVE_TASK"

    private val killReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i("CameraActivity", "Received KILL broadcast - DEPRECATED, should be handled by service stop now.")
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
            
            registerKillReceiver() 
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
        try {
            val intentFilter = IntentFilter("KILL") // "KILL" action is for the old kill button, service should stop itself.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(killReceiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(killReceiver, intentFilter)
            }
            killReceiverRegistered = true
            Log.i("CameraActivity", "Kill receiver registered (listens for 'KILL' action)")
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
            Log.i("CameraActivity", "Attempting to start Cam service with action 'start'")
            
            val intent = Intent(this, Cam::class.java)
            intent.action = "start"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            Log.i("CameraActivity", "Cam service start command sent")
            
        } catch (e: Exception) {
            Log.e("CameraActivity", "Failed to start Cam service", e)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            Log.d("CameraActivity", "onPause: Sending 'onPause' action to Cam service.")
            sendCam {
                it.action = "onPause"
            }
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error in onPause sending action to service", e)
        }
    }

    fun sendCam(extra: (Intent) -> Unit) {
        try {
            val intent = Intent(this, Cam::class.java)
            extra(intent)
            // Every call to startService with an Intent will be delivered to Cam.onStartCommand
            startService(intent) 
        } catch (e: Exception) {
            // Catch specific exceptions if possible, e.g., IllegalStateException if service cannot be started.
            Log.e("CameraActivity", "Error sending command to Cam service", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("CameraActivity", "onDestroy: Sending 'stop' action to Cam service.")
        try {
            // This is crucial to tell the service to stop its work and release resources
            // when the activity is being destroyed through normal means.
            val stopIntent = Intent(this, Cam::class.java)
            stopIntent.action = "stop"
            startService(stopIntent) // Send the stop command
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error in onDestroy sending 'stop' action to Cam service", e)
        }
        
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
        Log.i("CameraActivity", "onDestroy completed.")
    }

    override fun onResume() {
        super.onResume()
        try {
            Log.d("CameraActivity", "onResume: Sending 'onResume' action to Cam service.")
            sendCam {
                it.action = "onResume"
            }
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error in onResume sending action to service", e)
        }
    }
}
