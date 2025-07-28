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
    private var receiverRegistered = false

    private val receiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i("CameraActivity", "Received KILL broadcast")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("CAMERAACTIVITY", "CAMERAACTIVITY onCreate")
        
        try {
            activityCameraBinding = ActivityCameraBinding.inflate(layoutInflater)
            setContentView(activityCameraBinding.root)
            
            // 根据Android版本使用不同的注册方式
            registerKillReceiver()
            
            // 检查权限后再启动服务
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
            val intentFilter = IntentFilter("KILL")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 需要指定导出状态
                registerReceiver(receiver, intentFilter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                // Android 13以下使用传统方式
                registerReceiver(receiver, intentFilter)
            }
            receiverRegistered = true
            Log.i("CameraActivity", "Kill receiver registered successfully")
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error registering kill receiver", e)
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
        
        // Android 13+ 需要通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Android 14+ 需要摄像头前台服务权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            requiredPermissions.add(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
        }
        
        val hasAllPermissions = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        
        Log.i("CameraActivity", "权限检查结果: $hasAllPermissions")
        return hasAllPermissions
    }

    private fun startCameraService() {
        try {
            Log.i("CameraActivity", "启动摄像头服务")
            
            val intent = Intent(this, Cam::class.java)
            intent.action = "start"
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            
            Log.i("CameraActivity", "摄像头服务启动指令已发送")
            
        } catch (e: Exception) {
            Log.e("CameraActivity", "启动摄像头服务失败", e)
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
            // 只发送Intent，不启动服务（服务已经在startCameraService中启动了）
            startService(intent)
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error sending camera intent", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            sendCam {
                it.action = "stop"
            }
        } catch (e: Exception) {
            Log.e("CameraActivity", "Error in onDestroy", e)
        }
        
        if (receiverRegistered) {
            try {
                unregisterReceiver(receiver)
                receiverRegistered = false
                Log.i("CameraActivity", "Kill receiver unregistered successfully")
            } catch (e: Exception) {
                Log.e("CameraActivity", "Error unregistering receiver", e)
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
