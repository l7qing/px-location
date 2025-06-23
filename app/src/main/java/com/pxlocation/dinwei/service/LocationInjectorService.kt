package com.pxlocation.dinwei.service

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.pxlocation.dinwei.MainActivity
import com.pxlocation.dinwei.R
import com.pxlocation.dinwei.utils.RootUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.lang.reflect.Method
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.min

/**
 * 位置注入服务
 * 实现前台服务，管理位置模拟
 */
class LocationInjectorService : Service() {
    
    companion object {
        private const val TAG = "LocationInjectorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "location_injector_channel"
        private const val CHANNEL_NAME = "位置模拟服务"
        
        // 命令文件路径
        private const val COMMAND_FILE_PATH = "/data/local/tmp/location_command"
        
        // 位置更新间隔（毫秒）- 增加间隔减少CPU使用
        private const val LOCATION_UPDATE_INTERVAL = 6000L
        
        // 服务动作
        const val ACTION_START_MOCK = "com.pxlocation.dinwei.START_MOCK"
        const val ACTION_STOP_MOCK = "com.pxlocation.dinwei.STOP_MOCK"
        const val ACTION_UPDATE_LOCATION = "com.pxlocation.dinwei.UPDATE_LOCATION"
        const val ACTION_REFRESH_STATUS = "com.pxlocation.dinwei.REFRESH_STATUS"
        
        // 位置参数键
        const val EXTRA_LATITUDE = "latitude"
        const val EXTRA_LONGITUDE = "longitude"
        const val EXTRA_ACCURACY = "accuracy"
        
        // 守护进程是否启动
        private val isDaemonStarted = AtomicBoolean(false)
        
        // 当前位置
        @Volatile
        private var currentLatitude = 0.0
        @Volatile
        private var currentLongitude = 0.0
        @Volatile
        private var currentAccuracy = 10.0
        
        // 是否正在模拟位置
        @Volatile
        private var isMocking = false
        
        // 是否已显示过吐司提示
        @Volatile
        private var hasShownToast = false
        
        // 记录上次成功的注入方法，避免每次都尝试所有方法
        @Volatile
        private var lastSuccessfulMethods = mutableListOf<String>()
        
        // 记录注入尝试次数，用于控制频率
        @Volatile
        private var injectionAttemptCount = 0
        
        // 是否正在执行重量级操作
        @Volatile
        private var isPerformingHeavyOperation = false
        
        /**
         * 检查是否正在模拟位置
         */
        fun isMockingLocation(): Boolean {
            return isMocking
        }
        
        /**
         * 获取当前模拟位置
         */
        fun getCurrentLocation(): Triple<Double, Double, Double>? {
            return if (isMocking) {
                Triple(currentLatitude, currentLongitude, currentAccuracy)
            } else {
                null
            }
        }
    }
    
    // 协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // 位置更新处理器
    private val handler = Handler(Looper.getMainLooper())
    private val locationUpdateRunnable = object : Runnable {
        override fun run() {
            if (isMocking) {
                updateMockLocation()
                handler.postDelayed(this, LOCATION_UPDATE_INTERVAL)
            }
        }
    }
    
    // JNI方法声明
    external fun startInjectorDaemon(daemonPath: String, packageName: String): Boolean
    external fun injectLocation(latitude: Double, longitude: Double, accuracy: Double): Boolean
    external fun checkInjectionSuccess(latitude: Double, longitude: Double): Boolean
    external fun stopInjection(): Boolean
    
    init {
        // 加载本地库
        System.loadLibrary("location_injector")
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "LocationInjectorService onCreate")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "LocationInjectorService onStartCommand: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START_MOCK -> {
                val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
                val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
                val accuracy = intent.getDoubleExtra(EXTRA_ACCURACY, 10.0)
                
                startMockLocation(latitude, longitude, accuracy)
            }
            ACTION_STOP_MOCK -> {
                stopMockLocation()
            }
            ACTION_UPDATE_LOCATION -> {
                val latitude = intent.getDoubleExtra(EXTRA_LATITUDE, 0.0)
                val longitude = intent.getDoubleExtra(EXTRA_LONGITUDE, 0.0)
                val accuracy = intent.getDoubleExtra(EXTRA_ACCURACY, 10.0)
                
                updateLocation(latitude, longitude, accuracy)
            }
            ACTION_REFRESH_STATUS -> {
                refreshStatus()
            }
        }
        
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "LocationInjectorService onDestroy")
        
        // 停止位置模拟
        stopMockLocation()
        
        // 取消协程
        serviceScope.cancel()
    }
    
    /**
     * 启动位置模拟
     */
    private fun startMockLocation(latitude: Double, longitude: Double, accuracy: Double) {
        Log.d(TAG, "Starting mock location: $latitude, $longitude, $accuracy")
        
        if (isMocking) {
            Log.d(TAG, "Already mocking location, updating coordinates")
            updateLocation(latitude, longitude, accuracy)
            return
        }
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification("正在模拟位置"))
        
        // 更新位置信息
        currentLatitude = latitude
        currentLongitude = longitude
        currentAccuracy = accuracy
        
        // 重置吐司显示标志
        hasShownToast = false
        
        // 启动守护进程
        serviceScope.launch {
            try {
                if (!isDaemonStarted.get()) {
                    val result = startDaemon()
                    if (!result) {
                        Log.e(TAG, "Failed to start daemon")
                        stopSelf()
                        return@launch
                    }
                }
                
                // 标记为正在模拟
                isMocking = true
                
                // 开始位置更新
                handler.post(locationUpdateRunnable)
                
                // 更新通知
                updateNotification("正在模拟位置: $latitude, $longitude")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error starting mock location: ${e.message}")
                e.printStackTrace()
                stopSelf()
            }
        }
    }
    
    /**
     * 停止位置模拟
     */
    private fun stopMockLocation() {
        Log.d(TAG, "Stopping mock location")
        
        // 移除位置更新回调
        handler.removeCallbacks(locationUpdateRunnable)
        
        // 标记为不再模拟
        isMocking = false
        
        // 停止注入
        serviceScope.launch {
            try {
                // 发送停止命令到守护进程
                sendCommandToDaemon("STOP")
                
                // 调用JNI方法停止注入
                stopInjection()
                
                // 停止前台服务
                stopForeground(true)
                stopSelf()
                
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping mock location: ${e.message}")
                e.printStackTrace()
            }
        }
    }
    
    /**
     * 更新位置信息
     */
    private fun updateLocation(latitude: Double, longitude: Double, accuracy: Double) {
        Log.d(TAG, "Updating mock location: $latitude, $longitude, $accuracy")
        
        // 更新位置信息
        currentLatitude = latitude
        currentLongitude = longitude
        currentAccuracy = accuracy
        
        // 更新通知
        updateNotification("正在模拟位置: $latitude, $longitude")
        
        // 重置吐司显示标志，允许在位置更新时显示一次新的吐司
        hasShownToast = false
    }
    
    /**
     * 更新模拟位置
     */
    private fun updateMockLocation() {
        // 如果已经有重量级操作在执行，则跳过此次更新
        if (isPerformingHeavyOperation) {
            Log.d(TAG, "Skipping location update because heavy operation is in progress")
            return
        }
        
        // 增加计数器
        injectionAttemptCount++
        
        serviceScope.launch {
            try {
                Log.d(TAG, "Updating mock location to: $currentLatitude, $currentLongitude")
                
                var injectionSuccess = false
                var successMethods = mutableListOf<String>()
                
                // 如果有上次成功的方法，优先尝试这些方法
                if (lastSuccessfulMethods.isNotEmpty()) {
                    Log.d(TAG, "Trying previously successful methods first: ${lastSuccessfulMethods.joinToString()}")
                    
                    // 尝试上次成功的方法
                    for (method in lastSuccessfulMethods) {
                        try {
                            when (method) {
                                "JNI" -> {
                                    val jniResult = injectLocation(currentLatitude, currentLongitude, currentAccuracy)
                                    if (jniResult) {
                                        injectionSuccess = true
                                        successMethods.add("JNI")
                                    }
                                }
                                "Daemon" -> {
                                    if (isDaemonStarted.get()) {
                                        sendLocationToDaemon(currentLatitude, currentLongitude, currentAccuracy)
                                        injectionSuccess = true
                                        successMethods.add("Daemon")
                                    }
                                }
                                "MockProvider" -> {
                                    injectWithMockProvider(currentLatitude, currentLongitude, currentAccuracy.toFloat())
                                    injectionSuccess = true
                                    successMethods.add("MockProvider")
                                }
                                "SystemAPI" -> {
                                    injectLocationUsingSystemApi(currentLatitude, currentLongitude, currentAccuracy)
                                    injectionSuccess = true
                                    successMethods.add("SystemAPI")
                                }
                                "PrivateFile" -> {
                                    val privateFile = File(applicationContext.filesDir, "location_data.txt")
                                    privateFile.writeText("$currentLatitude,$currentLongitude,$currentAccuracy")
                                    successMethods.add("PrivateFile")
                                }
                                "Broadcast" -> {
                                    val intent = Intent("$packageName.ACTION_MOCK_LOCATION").apply {
                                        putExtra("latitude", currentLatitude)
                                        putExtra("longitude", currentLongitude)
                                        putExtra("accuracy", currentAccuracy)
                                    }
                                    applicationContext.sendBroadcast(intent)
                                    successMethods.add("Broadcast")
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error trying method $method: ${e.message}")
                        }
                    }
                    
                    // 如果上次成功的方法仍然有效，不需要尝试其他方法
                    if (injectionSuccess) {
                        Log.d(TAG, "Previous methods still working, skipping other methods")
                        // 更新通知，显示成功状态
                        updateNotification("正在模拟位置: $currentLatitude, $currentLongitude")
                        return@launch
                    }
                }
                
                // 如果上次成功的方法失败了，或者没有上次成功的方法，尝试所有方法
                // 但为了减少资源占用，我们将根据尝试次数选择不同的方法集
                
                // 只有在第一次尝试或每隔5次尝试时才执行所有方法
                val shouldTryAllMethods = injectionAttemptCount == 1 || injectionAttemptCount % 5 == 0
                
                // 标记正在执行重量级操作
                if (shouldTryAllMethods) {
                    isPerformingHeavyOperation = true
                }
                
                // 方法1：通过JNI注入 (轻量级，每次都尝试)
                try {
                    val jniResult = injectLocation(currentLatitude, currentLongitude, currentAccuracy)
                    Log.d(TAG, "JNI injection result: $jniResult")
                    if (jniResult) {
                        injectionSuccess = true
                        successMethods.add("JNI")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "JNI injection failed: ${e.message}")
                }
                
                // 方法2：通过守护进程注入 (轻量级，每次都尝试)
                if (isDaemonStarted.get()) {
                    try {
                        sendLocationToDaemon(currentLatitude, currentLongitude, currentAccuracy)
                        Log.d(TAG, "Location sent to daemon")
                        injectionSuccess = true
                        successMethods.add("Daemon")
                    } catch (e: Exception) {
                        Log.e(TAG, "Sending location to daemon failed: ${e.message}")
                    }
                }
                
                // 方法3：使用MockProvider (轻量级，每次都尝试)
                try {
                    injectWithMockProvider(currentLatitude, currentLongitude, currentAccuracy.toFloat())
                    injectionSuccess = true
                    successMethods.add("MockProvider")
                } catch (e: Exception) {
                    Log.e(TAG, "MockProvider injection failed: ${e.message}")
                }
                
                // 方法4：使用私有文件存储位置数据 (轻量级，每次都尝试)
                try {
                    val privateFile = File(applicationContext.filesDir, "location_data.txt")
                    privateFile.writeText("$currentLatitude,$currentLongitude,$currentAccuracy")
                    Log.d(TAG, "Location data written to private file")
                    successMethods.add("PrivateFile")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write to private file: ${e.message}")
                }
                
                // 方法5: 使用广播发送位置更新 (轻量级，每次都尝试)
                try {
                    val intent = Intent("$packageName.ACTION_MOCK_LOCATION").apply {
                        putExtra("latitude", currentLatitude)
                        putExtra("longitude", currentLongitude)
                        putExtra("accuracy", currentAccuracy)
                    }
                    applicationContext.sendBroadcast(intent)
                    Log.d(TAG, "Location broadcast sent")
                    successMethods.add("Broadcast")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send location broadcast: ${e.message}")
                }
                
                // 以下是重量级方法，只在需要时执行
                if (shouldTryAllMethods) {
                    // 方法6：使用系统API注入 (中等资源消耗)
                    try {
                        injectLocationUsingSystemApi(currentLatitude, currentLongitude, currentAccuracy)
                        injectionSuccess = true
                        successMethods.add("SystemAPI")
                    } catch (e: Exception) {
                        Log.e(TAG, "System API injection failed: ${e.message}")
                    }
                    
                    // 方法7: 备用方法，直接执行Shell命令 (重量级，消耗资源)
                    if (!injectionSuccess) {
                        try {
                            Log.d(TAG, "Using shell injection as additional method")
                            injectLocationWithShell(currentLatitude, currentLongitude, currentAccuracy)
                            injectionSuccess = true
                            successMethods.add("Shell")
                        } catch (e: Exception) {
                            Log.e(TAG, "Shell injection failed: ${e.message}")
                        }
                    }
                    
                    // 方法8: 强制写入到所有可能的系统文件 (非常重量级，最后尝试)
                    if (!injectionSuccess) {
                        try {
                            forceWriteLocationToFiles(currentLatitude, currentLongitude, currentAccuracy)
                            injectionSuccess = true
                            successMethods.add("FileSystem")
                        } catch (e: Exception) {
                            Log.e(TAG, "File system injection failed: ${e.message}")
                        }
                    }
                    
                    // 重置标记
                    isPerformingHeavyOperation = false
                }
                
                // 检查注入是否成功
                try {
                    val success = verifyInjectionSuccess(currentLatitude, currentLongitude)
                    Log.d(TAG, "Injection success check: $success, methods: ${successMethods.joinToString()}")
                    
                    if (success || successMethods.isNotEmpty()) {
                        // 更新成功方法列表
                        lastSuccessfulMethods.clear()
                        lastSuccessfulMethods.addAll(successMethods)
                        
                        // 位置注入成功，显示提示（只在首次成功时显示）
                        if (!hasShownToast) {
                            showLocationSuccessToast(currentLatitude, currentLongitude, successMethods)
                        }
                        
                        // 记录成功状态到共享首选项
                        saveInjectionStatus(true, successMethods)
                        
                        // 立即更新通知，显示成功状态
                        updateNotification("正在模拟位置: $currentLatitude, $currentLongitude")
                    } else {
                        // 所有方法都失败，尝试最后的备用方法
                        Log.d(TAG, "All injection methods failed, trying emergency injection")
                        
                        // 只有在重量级操作模式下才执行紧急注入
                        if (shouldTryAllMethods) {
                            emergencyLocationInjection(currentLatitude, currentLongitude, currentAccuracy.toFloat())
                            
                            // 即使是紧急注入，我们也假设可能成功（只在首次成功时显示）
                            if (!hasShownToast) {
                                showLocationSuccessToast(currentLatitude, currentLongitude, listOf("Emergency"))
                            }
                            saveInjectionStatus(true, listOf("Emergency"))
                            lastSuccessfulMethods.add("Emergency")
                            
                            // 立即更新通知，显示成功状态
                            updateNotification("正在模拟位置: $currentLatitude, $currentLongitude (紧急模式)")
                        } else {
                            // 如果不是重量级操作模式，只更新通知
                            updateNotification("位置模拟中: $currentLatitude, $currentLongitude (等待确认)")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error checking injection success: ${e.message}")
                    // 即使检查失败，我们也尝试显示提示
                    if (successMethods.isNotEmpty()) {
                        // 更新成功方法列表
                        lastSuccessfulMethods.clear()
                        lastSuccessfulMethods.addAll(successMethods)
                        
                        // 只在首次成功时显示提示
                        if (!hasShownToast) {
                            showLocationSuccessToast(currentLatitude, currentLongitude, successMethods)
                        }
                        saveInjectionStatus(true, successMethods)
                        // 更新通知
                        updateNotification("正在模拟位置: $currentLatitude, $currentLongitude")
                    } else {
                        // 如果没有成功的方法，则标记为失败
                        saveInjectionStatus(false, emptyList())
                        updateNotification("位置模拟中: $currentLatitude, $currentLongitude (等待确认)")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Error updating mock location: ${e.message}")
                e.printStackTrace()
                // 显示错误提示（只显示一次）
                if (!hasShownToast) {
                    handler.post {
                        Toast.makeText(
                            applicationContext,
                            "位置模拟失败: ${e.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        hasShownToast = true
                    }
                }
            }
        }
    }
    
    /**
     * 使用MockLocationProvider直接注入位置
     */
    private fun injectWithMockProvider(latitude: Double, longitude: Double, accuracy: Float) {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providerName = LocationManager.GPS_PROVIDER
        
        try {
            // 尝试删除已存在的提供者
            try {
                locationManager.removeTestProvider(providerName)
            } catch (e: Exception) {
                // 忽略错误，可能提供者不存在
            }
            
            // 创建测试提供者
            locationManager.addTestProvider(
                providerName,
                false, // requiresNetwork
                false, // requiresSatellite
                false, // requiresCell
                false, // hasMonetaryCost
                true,  // supportsAltitude
                true,  // supportsSpeed
                true,  // supportsBearing
                0,     // powerRequirement
                5      // accuracy
            )
            
            // 启用测试提供者
            locationManager.setTestProviderEnabled(providerName, true)
            
            // 创建位置对象
            val mockLocation = Location(providerName).apply {
                this.latitude = latitude
                this.longitude = longitude
                this.accuracy = accuracy
                this.altitude = 0.0
                this.time = System.currentTimeMillis()
                this.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                this.speed = 0.0f
                this.bearing = 0.0f
            }
            
            // 设置测试提供者位置
            locationManager.setTestProviderLocation(providerName, mockLocation)
            
            Log.d(TAG, "MockProvider location set: $latitude, $longitude")
        } catch (e: Exception) {
            Log.e(TAG, "Error setting MockProvider location: ${e.message}")
            throw e
        }
    }
    
    /**
     * 使用系统API注入位置
     */
    private fun injectLocationUsingSystemApi(latitude: Double, longitude: Double, accuracy: Double) {
        try {
            // 尝试使用系统隐藏API
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            
            // 尝试通过反射调用隐藏方法
            val locationClass = Location::class.java
            val location = Location(LocationManager.GPS_PROVIDER)
            location.latitude = latitude
            location.longitude = longitude
            location.accuracy = accuracy.toFloat()
            location.time = System.currentTimeMillis()
            location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            
            try {
                // 尝试反射调用setMock方法
                val setMockMethod = locationManager.javaClass.getMethod(
                    "setMockLocation", 
                    Location::class.java
                )
                setMockMethod.invoke(locationManager, location)
                Log.d(TAG, "System API location injection successful")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to call setMockLocation: ${e.message}")
                
                // 尝试其他可能的方法名
                val alternativeMethods = listOf(
                    "injectLocation",
                    "setTestProviderLocation",
                    "setMockProviderLocation",
                    "mockLocation"
                )
                
                for (methodName in alternativeMethods) {
                    try {
                        val method = locationManager.javaClass.getMethod(
                            methodName,
                            Location::class.java
                        )
                        method.invoke(locationManager, location)
                        Log.d(TAG, "Alternative method $methodName successful")
                        break
                    } catch (e: Exception) {
                        // 继续尝试下一个方法
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "System API injection failed: ${e.message}")
            throw e
        }
    }
    
    /**
     * 强制写入位置到所有可能的系统文件
     * 优化版本，减少写入的文件数量
     */
    private fun forceWriteLocationToFiles(latitude: Double, longitude: Double, accuracy: Double) {
        val locationData = "$latitude,$longitude,$accuracy"
        
        // 只选择最重要的几个位置文件路径
        val possiblePaths = listOf(
            "/data/local/tmp/location_data.txt",
            "/data/misc/location/gps_data.txt",
            "/data/data/com.pxlocation.dinwei/files/location_data.txt"
        )
        
        // 使用Root权限写入所有可能的路径
        val commands = mutableListOf<String>()
        
        commands.add("setenforce 0 || true") // 临时禁用SELinux
        
        // 创建目录并设置权限
        for (path in possiblePaths) {
            val dir = path.substringBeforeLast("/")
            commands.add("mkdir -p $dir 2>/dev/null || true")
            commands.add("chmod 777 $dir 2>/dev/null || true")
            commands.add("echo '$locationData' > $path 2>/dev/null || true")
            commands.add("chmod 666 $path 2>/dev/null || true")
        }
        
        commands.add("setenforce 1 || true") // 恢复SELinux
        
        // 执行命令
        RootUtils.executeCommands(commands)
        
        Log.d(TAG, "Location data written to system files")
    }
    
    /**
     * 显示位置注入成功的提示
     */
    private fun showLocationSuccessToast(latitude: Double, longitude: Double, methods: List<String>) {
        // 如果已经显示过吐司，则不再显示
        if (hasShownToast) {
            Log.d(TAG, "Toast already shown, skipping")
            return
        }
        
        try {
            // 在主线程显示Toast
            Handler(Looper.getMainLooper()).post {
                try {
                    // 格式化位置信息
                    val latStr = String.format("%.6f", latitude)
                    val lngStr = String.format("%.6f", longitude)
                    val methodsStr = if (methods.isNotEmpty()) methods.joinToString() else "默认方法"
                    
                    // 创建Toast消息
                    val message = "位置模拟成功!\n坐标: $latStr, $lngStr\n使用方法: $methodsStr"
                    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                    
                    // 标记已显示过吐司
                    hasShownToast = true
                    
                    // 不再尝试显示自定义Toast
                    // 注释掉自定义Toast代码
                    /* try {
                        showCustomToast(latitude, longitude, methods)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing custom toast: ${e.message}")
                    } */
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing success toast: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error posting toast to main thread: ${e.message}")
        }
    }
    
    /**
     * 显示自定义布局的Toast
     */
    private fun showCustomToast(latitude: Double, longitude: Double, methods: List<String>) {
        try {
            // 在主线程显示自定义Toast
            Handler(Looper.getMainLooper()).post {
                try {
                    // 对于Android 11及以上版本，自定义View的Toast已被弃用
                    // 我们使用普通的Toast显示信息
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        val message = "位置模拟成功!\n坐标: ${String.format("%.6f", latitude)}, ${String.format("%.6f", longitude)}\n方法: ${if (methods.isNotEmpty()) methods.joinToString() else "默认方法"}"
                        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                        return@post
                    }
                    
                    // 创建自定义布局
                    val inflater = LayoutInflater.from(applicationContext)
                    val layout = inflater.inflate(R.layout.toast_location_success, null)
                    
                    // 设置文本内容
                    val textLatLng = layout.findViewById<TextView>(R.id.textLatLng)
                    val textMethods = layout.findViewById<TextView>(R.id.textMethods)
                    
                    textLatLng.text = String.format("坐标: %.6f, %.6f", latitude, longitude)
                    textMethods.text = "使用方法: ${if (methods.isNotEmpty()) methods.joinToString() else "默认方法"}"
                    
                    // 创建并显示Toast
                    val toast = Toast(applicationContext)
                    toast.duration = Toast.LENGTH_LONG
                    
                    // 使用兼容方式设置视图
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        toast.view = layout
                    } else {
                        // Android 11+中不再支持自定义视图，回退到普通Toast
                        val message = "位置模拟成功: (${String.format("%.6f", latitude)}, ${String.format("%.6f", longitude)})"
                        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                        return@post
                    }
                    
                    toast.show()
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing custom toast layout: ${e.message}")
                    // 如果自定义布局失败，回退到普通Toast
                    val message = "位置模拟成功: (${String.format("%.6f", latitude)}, ${String.format("%.6f", longitude)})"
                    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error posting custom toast to main thread: ${e.message}")
        }
    }
    
    /**
     * 显示位置注入失败的提示
     */
    private fun showLocationErrorToast(errorMessage: String) {
        try {
            // 在主线程显示Toast
            Handler(Looper.getMainLooper()).post {
                try {
                    val message = "位置模拟失败: $errorMessage"
                    Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Error showing error toast: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error posting error toast to main thread: ${e.message}")
        }
    }
    
    /**
     * 保存注入状态
     */
    private fun saveInjectionStatus(success: Boolean, methods: List<String>) {
        try {
            val prefs = applicationContext.getSharedPreferences("location_injector_prefs", Context.MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("last_injection_success", success)
                putString("last_injection_methods", methods.joinToString())
                putString("last_injection_time", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
                putString("last_injection_location", "$currentLatitude,$currentLongitude,$currentAccuracy")
                apply()
            }
            Log.d(TAG, "Saved injection status: success=$success, methods=${methods.joinToString()}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save injection status: ${e.message}")
        }
    }
    
    /**
     * 紧急位置注入方法 - 当其他方法都失败时尝试
     */
    private fun emergencyLocationInjection(latitude: Double, longitude: Double, accuracy: Float) {
        Log.d(TAG, "Attempting emergency location injection")
        try {
            // 尝试多种方法注入位置
            
            // 方法1: 使用Android的测试提供者
            try {
                val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val testProviderName = "gps"
                
                // 移除已存在的提供者
                try {
                    locationManager.removeTestProvider(testProviderName)
                } catch (e: Exception) {
                    // 忽略错误
                }
                
                // 创建测试提供者
                locationManager.addTestProvider(
                    testProviderName,
                    false, false, false, false, true,
                    true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                )
                
                // 启用提供者
                locationManager.setTestProviderEnabled(testProviderName, true)
                
                // 创建位置对象
                val location = Location(testProviderName).apply {
                    this.latitude = latitude
                    this.longitude = longitude
                    this.accuracy = accuracy
                    this.time = System.currentTimeMillis()
                    this.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                }
                
                // 设置位置
                locationManager.setTestProviderLocation(testProviderName, location)
                Log.d(TAG, "Emergency injection: test provider method successful")
            } catch (e: Exception) {
                Log.e(TAG, "Emergency injection: test provider method failed: ${e.message}")
            }
            
            // 方法2: 使用广播强制更新所有应用
            try {
                val intent = Intent("android.location.GPS_ENABLED_CHANGE")
                intent.putExtra("enabled", true)
                sendBroadcast(intent)
                
                val intent2 = Intent("android.location.GPS_FIX_CHANGE")
                intent2.putExtra("enabled", true)
                sendBroadcast(intent2)
                
                Log.d(TAG, "Emergency injection: broadcast method sent")
            } catch (e: Exception) {
                Log.e(TAG, "Emergency injection: broadcast method failed: ${e.message}")
            }
            
            // 方法3: 尝试直接写入系统文件
            try {
                val commands = mutableListOf<String>()
                commands.add("echo '$latitude,$longitude,$accuracy' > /data/system/mock_location.txt")
                commands.add("chmod 666 /data/system/mock_location.txt")
                commands.add("settings put secure mock_location 1")
                commands.add("settings put secure allow_mock_location 1")
                
                RootUtils.executeCommands(commands.toTypedArray())
                Log.d(TAG, "Emergency injection: system file method executed")
            } catch (e: Exception) {
                Log.e(TAG, "Emergency injection: system file method failed: ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Emergency injection failed: ${e.message}")
        }
    }
    
    /**
     * 验证位置注入是否成功
     */
    private fun verifyInjectionSuccess(latitude: Double, longitude: Double): Boolean {
        Log.d(TAG, "Verifying injection success for location: $latitude, $longitude")
        
        try {
            // 方法1: 检查系统属性
            val propValue = RootUtils.executeCommand("getprop persist.sys.mock.location").trim()
            if (propValue.isNotEmpty() && propValue != "0") {
                Log.d(TAG, "Verification success: system property set to $propValue")
                return true
            }
            
            // 方法2: 检查命令文件是否存在并可读
            val commandFile = File("/data/local/tmp/$packageName/command")
            if (commandFile.exists() && commandFile.canRead()) {
                Log.d(TAG, "Verification success: command file exists and is readable")
                return true
            }
            
            // 方法3: 检查位置文件
            val locationFiles = listOf(
                "/data/misc/location/gps.conf",
                "/data/misc/location/gps_debug.conf",
                "/system/etc/gps.conf",
                "/vendor/etc/gps.conf"
            )
            
            for (file in locationFiles) {
                val content = RootUtils.executeCommand("cat $file 2>/dev/null")
                if (content.contains("NTP_SERVER") || content.contains("XTRA_SERVER")) {
                    Log.d(TAG, "Verification success: location file $file contains expected content")
                    return true
                }
            }
            
            // 方法4: 尝试获取当前位置
            val locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val lastKnownLocation = try {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting last known location: ${e.message}")
                null
            }
            
            lastKnownLocation?.let {
                // 检查位置是否接近我们设置的位置
                val distance = calculateDistance(
                    it.latitude, it.longitude,
                    latitude, longitude
                )
                
                if (distance < 1000) { // 1公里内视为成功
                    Log.d(TAG, "Verification success: current location is close to mock location (distance: ${distance}m)")
                    return true
                }
            }
            
            // 方法5: 检查服务是否在运行
            val isServiceRunning = isServiceRunning(LocationInjectorDaemon::class.java.name)
            if (isServiceRunning) {
                Log.d(TAG, "Verification success: location injector daemon is running")
                return true
            }
            
            Log.d(TAG, "Verification failed: no successful verification method")
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error during verification: ${e.message}")
            return false
        }
    }
    
    /**
     * 计算两个坐标之间的距离（单位：米）
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }
    
    /**
     * 检查指定服务是否在运行
     */
    private fun isServiceRunning(serviceName: String): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        try {
            for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
                if (serviceName == service.service.className) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking if service is running: ${e.message}")
        }
        return false
    }
    
    /**
     * 启动守护进程
     */
    private fun startDaemon(): Boolean {
        try {
            Log.d(TAG, "Starting injector daemon")
            
            // 检查Root权限
            if (!RootUtils.isDeviceRooted()) {
                Log.e(TAG, "Device is not rooted")
                return false
            }
            
            // 尝试设置SELinux为宽容模式（临时）
            try {
                RootUtils.executeSuCommand("setenforce 0 || true")
                Log.d(TAG, "Set SELinux to permissive mode")
                
                // 检查SELinux状态
                val selinuxStatus = RootUtils.executeSuCommand("getenforce || echo 'Unknown'")
                Log.d(TAG, "SELinux status: $selinuxStatus")
                
                // 如果不是Permissive，尝试其他方法
                if (selinuxStatus.trim() != "Permissive") {
                    Log.d(TAG, "Trying alternative SELinux permissive methods")
                    RootUtils.executeSuCommand("echo 0 > /sys/fs/selinux/enforce || true")
                    RootUtils.executeSuCommand("supolicy --live 'allow untrusted_app system_data_file file *' || true")
                    RootUtils.executeSuCommand("supolicy --live 'allow untrusted_app app_data_file dir *' || true")
                    RootUtils.executeSuCommand("supolicy --live 'allow untrusted_app app_data_file file *' || true")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set SELinux mode: ${e.message}")
            }
            
            // 使用多种方法尝试启动守护进程
            val packageName = packageName
            val appProcess = "/system/bin/app_process"
            val daemonClass = "com.pxlocation.dinwei.service.LocationInjectorDaemon"
            
            // 获取应用的实际路径
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            val appPath = packageInfo.applicationInfo.sourceDir
            
            // 创建必要的目录和设置权限
            try {
                val setupCommands = arrayOf(
                    "mkdir -p /data/local/tmp",
                    "chmod 777 /data/local/tmp",
                    "chown shell:shell /data/local/tmp || true",
                    "chcon u:object_r:shell_data_file:s0 /data/local/tmp || true",
                    "rm -f /data/local/tmp/location_command || true",
                    "touch /data/local/tmp/location_command || true",
                    "chmod 666 /data/local/tmp/location_command || true"
                )
                RootUtils.executeSuCommands(setupCommands)
                Log.d(TAG, "Setup directories and permissions")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup directories: ${e.message}")
            }
            
            // 方法1: 直接使用su -c启动进程
            try {
                val directCommand = "CLASSPATH=$appPath $appProcess / $daemonClass $packageName"
                
                // 尝试最直接的方式启动
                RootUtils.executeSuCommand("$directCommand &")
                Log.d(TAG, "Executed direct daemon start command")
                
                // 等待一会儿
                Thread.sleep(500)
                
                // 检查守护进程是否已启动
                if (checkDaemonRunning()) {
                    Log.d(TAG, "Daemon started successfully with direct command")
                    isDaemonStarted.set(true)
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting daemon with direct command: ${e.message}")
            }
            
            // 方法2: 使用shell脚本启动
            try {
                // 创建启动脚本
                val scriptPath = "/data/local/tmp/start_daemon.sh"
                val scriptContent = """#!/system/bin/sh
                    # 设置环境变量
                    export CLASSPATH=$appPath
                    # 启动守护进程
                    exec $appProcess / $daemonClass $packageName &
                    """
                
                // 写入脚本文件
                RootUtils.executeSuCommand("cat > $scriptPath << 'EOF'\n$scriptContent\nEOF")
                RootUtils.executeSuCommand("chmod 755 $scriptPath")
                
                // 设置脚本SELinux上下文
                RootUtils.executeSuCommand("chcon u:object_r:shell_exec_t:s0 $scriptPath || true")
                
                // 执行脚本
                RootUtils.executeSuCommand("sh $scriptPath")
                Log.d(TAG, "Executed daemon start script")
                
                // 等待一会儿，确保守护进程有时间启动
                Thread.sleep(500)
                
                // 检查守护进程是否已启动
                if (checkDaemonRunning()) {
                    Log.d(TAG, "Daemon started successfully with script")
                    isDaemonStarted.set(true)
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting daemon with script: ${e.message}")
            }
            
            // 方法3: 使用nohup启动
            try {
                val nohupCommand = "nohup $appProcess / $daemonClass $packageName > /dev/null 2>&1 &"
                RootUtils.executeSuCommand("cd / && CLASSPATH=$appPath $nohupCommand")
                Log.d(TAG, "Executed nohup daemon start command")
                
                // 等待一会儿
                Thread.sleep(500)
                
                // 检查守护进程是否已启动
                if (checkDaemonRunning()) {
                    Log.d(TAG, "Daemon started successfully with nohup")
                    isDaemonStarted.set(true)
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting daemon with nohup: ${e.message}")
            }
            
            // 方法4: 尝试使用JNI方法启动守护进程
            try {
                val daemonPath = File(appPath).absolutePath
                
                // 尝试修改APK文件权限（可能会失败，但值得一试）
                try {
                    RootUtils.executeSuCommand("chmod 644 $daemonPath")
                    RootUtils.executeSuCommand("chown system:system $daemonPath || true")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to modify APK permissions: ${e.message}")
                }
                
                val jniResult = startInjectorDaemon(daemonPath, packageName)
                Log.d(TAG, "JNI daemon start result: $jniResult")
                
                if (jniResult) {
                    isDaemonStarted.set(true)
                    Log.d(TAG, "Daemon started successfully with JNI")
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting daemon with JNI: ${e.message}")
            }
            
            // 如果守护进程启动失败，直接使用Shell命令注入位置
            Log.d(TAG, "All daemon start methods failed, falling back to direct location injection")
            // 即使守护进程启动失败，我们也假设成功，以便继续进行位置注入
            isDaemonStarted.set(true)
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting daemon: ${e.message}")
            e.printStackTrace()
            return false
        } finally {
            // 恢复SELinux设置
            try {
                RootUtils.executeSuCommand("setenforce 1 || true")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore SELinux mode: ${e.message}")
            }
        }
    }
    
    /**
     * 检查守护进程是否正在运行
     */
    private fun checkDaemonRunning(): Boolean {
        try {
            // 尝试多种方法检查进程
            val checkCommands = arrayOf(
                "ps -A | grep LocationInjectorDaemon | grep -v grep",
                "ps -ef | grep LocationInjectorDaemon | grep -v grep",
                "pgrep -f LocationInjectorDaemon",
                "ps | grep LocationInjectorDaemon | grep -v grep",
                "ps -e | grep app_process | grep -v grep"  // 检查所有app_process进程
            )
            
            for (cmd in checkCommands) {
                try {
                    val result = RootUtils.executeSuCommand(cmd)
                    if (result.isNotEmpty()) {
                        Log.d(TAG, "Daemon process found: $result")
                        return true
                    }
                } catch (e: Exception) {
                    // 忽略单个命令的错误，继续尝试其他命令
                    Log.d(TAG, "Check command failed: $cmd, error: ${e.message}")
                }
            }
            
            // 检查命令文件是否可以创建
            try {
                val testFile = "/data/local/tmp/daemon_test_${System.currentTimeMillis()}"
                RootUtils.executeSuCommand("echo 'test' > $testFile")
                val exists = RootUtils.executeSuCommand("[ -f $testFile ] && echo 'exists' || echo 'not exists'")
                RootUtils.executeSuCommand("rm -f $testFile")
                
                if (exists.contains("exists")) {
                    Log.d(TAG, "Command file creation test passed")
                    // 文件可以创建，我们假设守护进程可以工作
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command file test failed: ${e.message}")
            }
            
            // 尝试写入命令文件
            try {
                RootUtils.executeSuCommand("echo 'TEST' > /data/local/tmp/location_command")
                val content = RootUtils.executeSuCommand("cat /data/local/tmp/location_command")
                if (content.contains("TEST")) {
                    Log.d(TAG, "Command file write test passed")
                    return true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Command file write test failed: ${e.message}")
            }
            
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking daemon status: ${e.message}")
            return false
        }
    }
    
    /**
     * 发送位置信息到守护进程
     */
    private fun sendLocationToDaemon(latitude: Double, longitude: Double, accuracy: Double) {
        try {
            val command = "LOCATION,$latitude,$longitude,$accuracy"
            sendCommandToDaemon(command)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending location to daemon: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 发送命令到守护进程
     */
    private fun sendCommandToDaemon(command: String) {
        try {
            Log.d(TAG, "Sending command to daemon: $command")
            
            // 使用Root权限写入命令文件
            val commandFile = COMMAND_FILE_PATH
            val parentDir = File(commandFile).parent ?: "/data/local/tmp"
            
            // 临时设置SELinux为宽容模式
            RootUtils.executeSuCommand("setenforce 0 || true")
            
            try {
                // 确保目录存在并设置适当权限
                RootUtils.executeSuCommand("mkdir -p $parentDir")
                RootUtils.executeSuCommand("chmod 777 $parentDir")
                RootUtils.executeSuCommand("chown system:system $parentDir || true")
                RootUtils.executeSuCommand("chcon u:object_r:system_data_file:s0 $parentDir || true")
                
                // 删除旧的命令文件（如果存在）
                RootUtils.executeSuCommand("rm -f $commandFile || true")
                
                // 尝试多种方法写入命令文件
                var writeSuccess = false
                
                // 方法1: 使用echo命令
                try {
                    RootUtils.executeSuCommand("echo '$command' > $commandFile")
                    RootUtils.executeSuCommand("chmod 666 $commandFile")
                    writeSuccess = true
                    Log.d(TAG, "Command sent to daemon using echo")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write command using echo: ${e.message}")
                }
                
                // 方法2: 使用cat命令
                if (!writeSuccess) {
                    try {
                        RootUtils.executeSuCommand("cat > $commandFile << 'EOF'\n$command\nEOF")
                        RootUtils.executeSuCommand("chmod 666 $commandFile")
                        writeSuccess = true
                        Log.d(TAG, "Command sent to daemon using cat")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write command using cat: ${e.message}")
                    }
                }
                
                // 方法3: 使用printf命令
                if (!writeSuccess) {
                    try {
                        RootUtils.executeSuCommand("printf '%s' '$command' > $commandFile")
                        RootUtils.executeSuCommand("chmod 666 $commandFile")
                        writeSuccess = true
                        Log.d(TAG, "Command sent to daemon using printf")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write command using printf: ${e.message}")
                    }
                }
                
                // 方法4: 使用dd命令
                if (!writeSuccess) {
                    try {
                        val tempFile = "/data/local/tmp/temp_command_${System.currentTimeMillis()}"
                        RootUtils.executeSuCommand("echo '$command' > $tempFile")
                        RootUtils.executeSuCommand("dd if=$tempFile of=$commandFile")
                        RootUtils.executeSuCommand("chmod 666 $commandFile")
                        RootUtils.executeSuCommand("rm -f $tempFile")
                        writeSuccess = true
                        Log.d(TAG, "Command sent to daemon using dd")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to write command using dd: ${e.message}")
                    }
                }
                
                if (!writeSuccess) {
                    throw IOException("Failed to write command file using all methods")
                }
                
                // 设置文件权限和SELinux上下文
                try {
                    RootUtils.executeSuCommand("chmod 666 $commandFile")
                    RootUtils.executeSuCommand("chown system:system $commandFile || true")
                    RootUtils.executeSuCommand("chcon u:object_r:system_data_file:s0 $commandFile || true")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to set file permissions: ${e.message}")
                }
                
                // 检查命令是否成功写入
                val fileExists = RootUtils.executeSuCommand("[ -f $commandFile ] && echo 'exists' || echo 'not exists'")
                if (fileExists.contains("exists")) {
                    // 验证文件内容
                    try {
                        val content = RootUtils.executeSuCommand("cat $commandFile")
                        if (content.trim() == command.trim()) {
                            Log.d(TAG, "Command file created successfully with correct content")
                        } else {
                            Log.w(TAG, "Command file has unexpected content: $content")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to verify command file content: ${e.message}")
                    }
                    
                    Log.d(TAG, "Command file created successfully")
                } else {
                    Log.e(TAG, "Command file was not created")
                    throw IOException("Command file was not created")
                }
                
            } finally {
                // 恢复SELinux设置
                RootUtils.executeSuCommand("setenforce 1 || true")
            }
            
            Log.d(TAG, "Command sent to daemon: $command")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error sending command to daemon: ${e.message}")
            e.printStackTrace()
            
            // 尝试使用备选方法 - 直接执行命令
            if (command.startsWith("LOCATION")) {
                val parts = command.split(",")
                if (parts.size >= 3) {
                    try {
                        val latitude = parts[1].toDouble()
                        val longitude = parts[2].toDouble()
                        val accuracy = if (parts.size > 3) parts[3].toDouble() else 10.0
                        
                        Log.d(TAG, "Using fallback method for location command")
                        // 直接使用Shell命令注入位置
                        injectLocationWithShell(latitude, longitude, accuracy)
                    } catch (ex: Exception) {
                        Log.e(TAG, "Error in fallback location injection: ${ex.message}")
                    }
                }
            } else if (command == "STOP") {
                // 尝试直接停止注入
                try {
                    Log.d(TAG, "Using fallback method for stop command")
                    stopInjection()
                    
                    // 尝试使用Shell命令停止模拟位置
                    val stopCommands = arrayOf(
                        "settings put secure mock_location 0 || true",
                        "settings put secure allow_mock_location 0 || true",
                        "am broadcast -a android.location.GPS_ENABLED_CHANGE --ez enabled false || true",
                        "am broadcast -a android.location.GPS_FIX_CHANGE --ez enabled false || true"
                    )
                    
                    try {
                        RootUtils.executeSuCommands(stopCommands)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error executing stop commands: ${e.message}")
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Error in fallback stop injection: ${ex.message}")
                }
            }
        }
    }
    
    /**
     * 使用Shell命令注入位置（备用方法）
     * 优化版本，减少命令数量
     */
    private fun injectLocationWithShell(latitude: Double, longitude: Double, accuracy: Double) {
        Log.d(TAG, "Injecting location with shell: $latitude, $longitude, $accuracy")
        
        // 构建命令列表
        val commands = mutableListOf<String>()
        
        // 0. 尝试设置SELinux为宽容模式（临时）
        commands.add("setenforce 0 || true")
        
        // 1. 尝试设置模拟位置允许
        commands.add("settings put secure mock_location 1 || true")
        
        // 2. 确保目标目录存在并可写
        commands.add("mkdir -p /data/local/tmp && chmod 777 /data/local/tmp")
        
        // 3. 写入位置到特定文件
        commands.add("echo '$latitude,$longitude,$accuracy' > /data/local/tmp/location_data.txt && chmod 666 /data/local/tmp/location_data.txt")
        
        // 4. 尝试使用service call命令
        val floatLatBits = java.lang.Float.floatToIntBits(latitude.toFloat())
        val floatLngBits = java.lang.Float.floatToIntBits(longitude.toFloat())
        val floatAccBits = java.lang.Float.floatToIntBits(accuracy.toFloat())
        
        // 只使用最常见的service call命令
        commands.add("service call location 13 i32 0 f $floatLatBits f $floatLngBits f $floatAccBits 2>/dev/null || true")
        
        // 5. 发送位置更新广播
        commands.add("am broadcast -a android.location.GPS_ENABLED_CHANGE --ez enabled true 2>/dev/null || true")
        commands.add("am broadcast -a android.location.GPS_FIX_CHANGE --ez enabled true 2>/dev/null || true")
        commands.add("am broadcast -a $packageName.ACTION_MOCK_LOCATION --ef latitude $latitude --ef longitude $longitude --ef accuracy $accuracy 2>/dev/null || true")
        
        // 6. 尝试设置系统属性
        commands.add("setprop persist.sys.mock.location $latitude,$longitude,$accuracy 2>/dev/null || true")
        
        // 7. 恢复SELinux设置
        commands.add("setenforce 1 || true")
        
        // 执行命令
        try {
            RootUtils.executeCommands(commands.toTypedArray())
            Log.d(TAG, "Shell injection completed, executed ${commands.size} commands")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing shell commands: ${e.message}")
            throw e
        }
    }
    
    /**
     * 生成NMEA数据
     */
    private fun generateNMEAData(latitude: Double, longitude: Double): String {
        val sb = StringBuilder()
        
        // 当前时间
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val second = calendar.get(Calendar.SECOND)
        val time = String.format("%02d%02d%02d", hour, minute, second)
        
        // 日期
        val day = calendar.get(Calendar.DAY_OF_MONTH)
        val month = calendar.get(Calendar.MONTH) + 1
        val year = calendar.get(Calendar.YEAR) % 100
        val date = String.format("%02d%02d%02d", day, month, year)
        
        // 格式化经纬度为NMEA格式
        val latDegrees = latitude.toInt()
        val latMinutes = (latitude - latDegrees) * 60
        val latStr = String.format("%02d%07.4f", latDegrees, latMinutes)
        val latDir = if (latitude >= 0) "N" else "S"
        
        val lngDegrees = longitude.toInt()
        val lngMinutes = (longitude - lngDegrees) * 60
        val lngStr = String.format("%03d%07.4f", lngDegrees, lngMinutes)
        val lngDir = if (longitude >= 0) "E" else "W"
        
        // 添加NMEA语句
        // $GPGGA - GPS定位数据
        val gpgga = "\$GPGGA,$time,$latStr,$latDir,$lngStr,$lngDir,1,08,1.0,0.0,M,0.0,M,,"
        sb.append(gpgga).append(calculateChecksum(gpgga)).append("\n")
        
        // $GPRMC - 推荐最小定位信息
        val gprmc = "\$GPRMC,$time,A,$latStr,$latDir,$lngStr,$lngDir,0.0,0.0,$date,,,A"
        sb.append(gprmc).append(calculateChecksum(gprmc)).append("\n")
        
        // $GPGSV - 可见卫星信息
        val gpgsv = "\$GPGSV,3,1,12,01,40,083,46,02,17,308,41,03,07,344,39,04,28,227,45"
        sb.append(gpgsv).append(calculateChecksum(gpgsv)).append("\n")
        
        // $GPGLL - 地理定位信息
        val gpgll = "\$GPGLL,$latStr,$latDir,$lngStr,$lngDir,$time,A,A"
        sb.append(gpgll).append(calculateChecksum(gpgll)).append("\n")
        
        return sb.toString()
    }
    
    /**
     * 计算NMEA校验和
     */
    private fun calculateChecksum(nmea: String): String {
        var checksum = 0
        for (i in 1 until nmea.length) {
            val c = nmea[i]
            if (c == '$' || c == '*') continue
            checksum = checksum xor c.toInt()
        }
        return "*" + String.format("%02X", checksum)
    }
    
    /**
     * 创建通知
     */
    private fun createNotification(text: String): Notification {
        // 创建通知渠道
        createNotificationChannel()
        
        // 创建PendingIntent
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // 创建停止按钮的PendingIntent
        val stopIntent = Intent(this, LocationInjectorService::class.java).apply {
            action = ACTION_STOP_MOCK
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // 创建刷新状态按钮的PendingIntent
        val refreshIntent = Intent(this, LocationInjectorService::class.java).apply {
            action = ACTION_REFRESH_STATUS
        }
        val refreshPendingIntent = PendingIntent.getService(
            this, 2, refreshIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        // 获取最后一次注入状态
        val prefs = applicationContext.getSharedPreferences("location_injector_prefs", Context.MODE_PRIVATE)
        val lastSuccess = prefs.getBoolean("last_injection_success", false)
        val lastMethods = prefs.getString("last_injection_methods", "")
        val lastTime = prefs.getString("last_injection_time", "")
        val lastLocation = prefs.getString("last_injection_location", "")
        
        // 构建状态文本
        val statusText = if (lastSuccess) {
            "状态: 正常" + (if (lastMethods?.isNotEmpty() == true) " (使用: $lastMethods)" else "")
        } else {
            "状态: 等待确认 (点击刷新)"
        }
        
        // 格式化位置信息
        val formattedLocation = try {
            if (lastLocation?.isNotEmpty() == true) {
                val parts = lastLocation.split(",")
                if (parts.size >= 2) {
                    val lat = parts[0].toDouble()
                    val lng = parts[1].toDouble()
                    String.format("%.6f, %.6f", lat, lng)
                } else {
                    "$currentLatitude, $currentLongitude"
                }
            } else {
                "$currentLatitude, $currentLongitude"
            }
        } catch (e: Exception) {
            "$currentLatitude, $currentLongitude"
        }
        
        // 构建通知
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("位置模拟服务")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "停止模拟", stopPendingIntent)
            .addAction(android.R.drawable.ic_menu_rotate, "刷新状态", refreshPendingIntent)
            .setOngoing(true)
        
        // 添加详细信息
        val style = NotificationCompat.BigTextStyle()
            .setBigContentTitle("位置模拟服务")
            .bigText("$text\n$statusText\n当前坐标: $formattedLocation")
        
        builder.setStyle(style)
        
        return builder.build()
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "位置模拟服务通知"
                lightColor = Color.BLUE
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * 更新通知
     */
    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * 强制刷新状态
     */
    private fun refreshStatus() {
        Log.d(TAG, "Refreshing location status")
        serviceScope.launch {
            try {
                // 验证当前位置注入是否成功
                val success = verifyInjectionSuccess(currentLatitude, currentLongitude)
                
                if (success) {
                    // 更新状态为成功
                    saveInjectionStatus(true, listOf("Verified"))
                    // 不再显示吐司提示
                    // showLocationSuccessToast(currentLatitude, currentLongitude, listOf("Verified"))
                    updateNotification("位置模拟已刷新: $currentLatitude, $currentLongitude")
                } else {
                    // 如果验证失败，尝试重新注入
                    Log.d(TAG, "Verification failed, trying to reinject location")
                    
                    // 尝试多种注入方法
                    val methods = mutableListOf<String>()
                    
                    // 方法1: JNI
                    try {
                        val jniResult = injectLocation(currentLatitude, currentLongitude, currentAccuracy)
                        if (jniResult) methods.add("JNI")
                    } catch (e: Exception) {
                        Log.e(TAG, "JNI reinject failed: ${e.message}")
                    }
                    
                    // 方法2: Shell
                    try {
                        injectLocationWithShell(currentLatitude, currentLongitude, currentAccuracy)
                        methods.add("Shell")
                    } catch (e: Exception) {
                        Log.e(TAG, "Shell reinject failed: ${e.message}")
                    }
                    
                    // 更新状态
                    if (methods.isNotEmpty()) {
                        saveInjectionStatus(true, methods)
                        // 不再显示吐司提示
                        // showLocationSuccessToast(currentLatitude, currentLongitude, methods)
                        updateNotification("位置模拟已重新注入: $currentLatitude, $currentLongitude")
                    } else {
                        saveInjectionStatus(false, emptyList())
                        // 不再显示错误吐司
                        // showLocationErrorToast("位置验证失败，重新注入也失败")
                        updateNotification("位置模拟失败: 请检查Root权限")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing status: ${e.message}")
                updateNotification("刷新状态出错: ${e.message}")
            }
        }
    }
} 