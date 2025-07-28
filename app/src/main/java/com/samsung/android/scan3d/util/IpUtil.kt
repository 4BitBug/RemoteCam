package com.samsung.android.scan3d.util

import android.util.Log
import java.net.NetworkInterface
import java.util.Collections

object IpUtil {

    fun getLocalIpAddress(): String {
        try {
            val interfaces: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            
            // 优先获取WiFi接口（通常以wlan开头）
            for (networkInterface in interfaces) {
                if (networkInterface.name.startsWith("wlan") && networkInterface.isUp && !networkInterface.isLoopback) {
                    val addresses = networkInterface.inetAddresses
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && 
                            address.isSiteLocalAddress && 
                            !address.hostAddress.contains(":") &&
                            address.hostAddress.startsWith("192.168.")) {
                            val ip = address.hostAddress
                            Log.i("IpUtil", "找到WiFi IP: $ip")
                            return ip
                        }
                    }
                }
            }
            
            // 如果没有找到WiFi接口，查找其他有效的局域网IP
            for (networkInterface in interfaces) {
                if (networkInterface.isUp && !networkInterface.isLoopback) {
                    val addresses = networkInterface.inetAddresses
                    for (address in addresses) {
                        if (!address.isLoopbackAddress && 
                            address.isSiteLocalAddress && 
                            !address.hostAddress.contains(":")) {
                            val ip = address.hostAddress
                            Log.i("IpUtil", "找到局域网IP: $ip (接口: ${networkInterface.name})")
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("IpUtil", "获取IP地址失败", e)
        }
        
        Log.w("IpUtil", "未找到有效IP地址，返回localhost")
        return "127.0.0.1" // 返回localhost，方便本地测试
    }
}