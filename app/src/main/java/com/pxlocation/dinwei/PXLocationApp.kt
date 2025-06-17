package com.pxlocation.dinwei

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.multidex.MultiDex
import com.amap.api.maps.MapsInitializer
import com.pxlocation.dinwei.utils.RootUtils
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PXLocationApp : Application() {
    
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    companion object {
        // 是否已获取Root权限
        var isRooted = false
        private const val TAG = "PXLocationApp"
    }
    
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // 初始化MultiDex
        MultiDex.install(this)
    }
    
    override fun onCreate() {
        super.onCreate()
        
        // 配置Shell库
        Shell.enableVerboseLogging = true
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_REDIRECT_STDERR)
                .setTimeout(10)
        )
        
        // 检查Root权限
        applicationScope.launch(Dispatchers.IO) {
            try {
                isRooted = RootUtils.requestRoot()
                Log.d(TAG, "设备Root状态: $isRooted")
            } catch (e: Exception) {
                Log.e(TAG, "检查Root权限时出错: ${e.message}")
                e.printStackTrace()
            }
        }
        
        try {
            // 初始化高德地图SDK
            MapsInitializer.updatePrivacyShow(this, true, true)
            MapsInitializer.updatePrivacyAgree(this, true)
        } catch (e: Exception) {
            Log.e(TAG, "初始化地图SDK时出错: ${e.message}")
            e.printStackTrace()
        }
    }
} 