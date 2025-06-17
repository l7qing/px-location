package com.pxlocation.dinwei.utils

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.pxlocation.dinwei.PXLocationApp

/**
 * 权限管理工具类
 */
object PermissionUtils {

    private const val TAG = "PermissionUtils"

    // 位置权限
    private val LOCATION_PERMISSIONS = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )
    
    // 存储权限
    private val STORAGE_PERMISSIONS = arrayOf(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )
    
    // 后台位置权限 (Android 10+)
    private val BACKGROUND_LOCATION_PERMISSION = 
        Manifest.permission.ACCESS_BACKGROUND_LOCATION
    
    // 请求码
    const val REQUEST_LOCATION_PERMISSION = 100
    const val REQUEST_STORAGE_PERMISSION = 101
    const val REQUEST_BACKGROUND_LOCATION = 102
    
    /**
     * 检查是否有Root权限
     */
    fun hasRootPermission(): Boolean {
        val isRooted = PXLocationApp.isRooted
        Log.d(TAG, "hasRootPermission: $isRooted")
        return isRooted
    }
    
    /**
     * 检查位置权限
     */
    fun hasLocationPermissions(context: Context): Boolean {
        try {
            val hasPermissions = LOCATION_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(context, it) == 
                    PackageManager.PERMISSION_GRANTED
            }
            Log.d(TAG, "hasLocationPermissions: $hasPermissions")
            return hasPermissions
        } catch (e: Exception) {
            Log.e(TAG, "Error checking location permissions: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * 检查存储权限
     */
    fun hasStoragePermissions(context: Context): Boolean {
        try {
            // Android 10+ 不需要存储权限
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "hasStoragePermissions: true (Android 10+)")
                return true
            }
            
            val hasPermissions = STORAGE_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(context, it) == 
                    PackageManager.PERMISSION_GRANTED
            }
            Log.d(TAG, "hasStoragePermissions: $hasPermissions")
            return hasPermissions
        } catch (e: Exception) {
            Log.e(TAG, "Error checking storage permissions: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * 检查后台位置权限
     */
    fun hasBackgroundLocationPermission(context: Context): Boolean {
        try {
            // Android 10+ 需要后台位置权限
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context, 
                    BACKGROUND_LOCATION_PERMISSION
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            Log.d(TAG, "hasBackgroundLocationPermission: $hasPermission")
            return hasPermission
        } catch (e: Exception) {
            Log.e(TAG, "Error checking background location permission: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
    
    /**
     * 请求位置权限
     */
    fun requestLocationPermissions(activity: Activity) {
        try {
            Log.d(TAG, "Requesting location permissions")
            ActivityCompat.requestPermissions(
                activity,
                LOCATION_PERMISSIONS,
                REQUEST_LOCATION_PERMISSION
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting location permissions: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 请求存储权限
     */
    fun requestStoragePermissions(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                Log.d(TAG, "Requesting storage permissions")
                ActivityCompat.requestPermissions(
                    activity,
                    STORAGE_PERMISSIONS,
                    REQUEST_STORAGE_PERMISSION
                )
            } else {
                Log.d(TAG, "Storage permissions not needed on Android 10+")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting storage permissions: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 请求后台位置权限
     */
    fun requestBackgroundLocationPermission(activity: Activity) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Log.d(TAG, "Requesting background location permission")
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(BACKGROUND_LOCATION_PERMISSION),
                    REQUEST_BACKGROUND_LOCATION
                )
            } else {
                Log.d(TAG, "Background location permission not needed on Android < 10")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting background location permission: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 打开应用设置页面
     */
    fun openAppSettings(context: Context) {
        try {
            Log.d(TAG, "Opening app settings")
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            val uri = Uri.fromParts("package", context.packageName, null)
            intent.data = uri
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app settings: ${e.message}")
            e.printStackTrace()
        }
    }
} 