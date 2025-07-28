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

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.navigation.Navigation
import androidx.lifecycle.lifecycleScope
import com.samsung.android.scan3d.R
import android.util.Log

private const val PERMISSIONS_REQUEST_CODE = 10

/**
 * This [Fragment] requests permissions and, once granted, it will navigate to the next fragment
 */
class PermissionsFragment : Fragment() {

    private var permissionsRequested = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 检查是否已经请求过权限，避免重复请求
        if (permissionsRequested) {
            return
        }

        if (hasPermissions(requireContext())) {
            // If permissions have already been granted, proceed
            navigateToCamera()
        } else {
            // Request camera-related permissions
            permissionsRequested = true
            requestPermissions(getRequiredPermissions(), PERMISSIONS_REQUEST_CODE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(
            requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            // 检查所有权限是否都被授予
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            if (allGranted) {
                // Takes the user to the success fragment when permission is granted
                navigateToCamera()
            } else {
                // 检查哪些权限被拒绝
                val deniedPermissions = permissions.filterIndexed { index, _ ->
                    grantResults[index] != PackageManager.PERMISSION_GRANTED
                }
                Toast.makeText(context, "Required permissions denied: ${deniedPermissions.joinToString()}", Toast.LENGTH_LONG).show()
                // 可以选择重新请求权限或退出应用
                requireActivity().finish()
            }
        }
    }

    private fun navigateToCamera() {
        lifecycleScope.launchWhenStarted {
            try {
                Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                        PermissionsFragmentDirections.actionPermissionsToSelector())
            } catch (e: Exception) {
                Log.e("PermissionsFragment", "Navigation failed", e)
                // 如果导航失败，尝试重新创建Activity
                requireActivity().recreate()
            }
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.FOREGROUND_SERVICE
        )
        
        // Android 13+ 需要通知权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        
        // Android 14+ 需要摄像头前台服务权限
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.FOREGROUND_SERVICE_CAMERA)
        }
        
        Log.i("PermissionsFragment", "请求权限: ${permissions.joinToString()}")
        return permissions.toTypedArray()
    }

    companion object {

        /** Convenience method used to check if all permissions required by this app are granted */
        fun hasPermissions(context: Context): Boolean {
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
                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
            }
            
            Log.i("PermissionsFragment", "权限检查结果: $hasAllPermissions")
            return hasAllPermissions
        }
    }
}
