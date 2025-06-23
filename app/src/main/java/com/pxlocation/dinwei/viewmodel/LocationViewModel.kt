package com.pxlocation.dinwei.viewmodel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.amap.api.maps.model.LatLng
import com.pxlocation.dinwei.service.LocationInjectorService
import com.pxlocation.dinwei.utils.RootUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 位置ViewModel
 * 管理位置模拟的业务逻辑
 */
class LocationViewModel(application: Application) : AndroidViewModel(application) {
    
    companion object {
        private const val TAG = "LocationViewModel"
    }
    
    // 当前选中位置
    private val _selectedLocation = MutableLiveData<LatLng?>()
    val selectedLocation: LiveData<LatLng?> = _selectedLocation
    
    // 当前位置地址
    private val _locationAddress = MutableLiveData<String>()
    val locationAddress: LiveData<String> = _locationAddress
    
    // 是否正在模拟位置
    private val _isMockingLocation = MutableLiveData<Boolean>()
    val isMockingLocation: LiveData<Boolean> = _isMockingLocation
    
    // 模拟位置状态
    private val _mockStatus = MutableLiveData<String>()
    val mockStatus: LiveData<String> = _mockStatus
    
    // 模拟位置详细状态
    private val _mockDetailedStatus = MutableLiveData<String>()
    val mockDetailedStatus: LiveData<String> = _mockDetailedStatus
    
    // 模拟位置成功使用的方法
    private val _mockMethods = MutableLiveData<List<String>>()
    val mockMethods: LiveData<List<String>> = _mockMethods
    
    // Root权限状态
    private val _rootStatus = MutableLiveData<Boolean>()
    val rootStatus: LiveData<Boolean> = _rootStatus
    
    // 错误信息
    private val _errorMessage = MutableLiveData<String?>()
    val errorMessage: LiveData<String?> = _errorMessage
    
    // 上次位置更新时间
    private val _lastUpdateTime = MutableLiveData<Long>()
    val lastUpdateTime: LiveData<Long> = _lastUpdateTime
    
    init {
        // 初始化状态
        _selectedLocation.value = null
        _locationAddress.value = "请选择位置"
        _isMockingLocation.value = false
        _mockStatus.value = "未开始模拟"
        _mockDetailedStatus.value = "请选择位置并点击开始模拟"
        _mockMethods.value = emptyList()
        _errorMessage.value = null
        _lastUpdateTime.value = 0L
        
        // 检查Root权限
        checkRootStatus()
        
        // 检查当前是否正在模拟位置
        checkMockingStatus()
        
        // 定期刷新状态
        startStatusRefresh()
    }
    
    /**
     * 设置选中位置
     */
    fun setSelectedLocation(location: LatLng) {
        _selectedLocation.value = location
        
        // 如果正在模拟位置，则更新位置
        if (_isMockingLocation.value == true) {
            updateMockLocation()
        }
    }
    
    /**
     * 设置位置地址
     */
    fun setLocationAddress(address: String) {
        _locationAddress.value = address
    }
    
    /**
     * 检查Root权限状态
     */
    fun checkRootStatus() {
        viewModelScope.launch {
            val hasRoot = withContext(Dispatchers.IO) {
                RootUtils.isDeviceRooted()
            }
            _rootStatus.value = hasRoot
            
            if (!hasRoot) {
                _mockStatus.value = "无Root权限，无法模拟位置"
                _mockDetailedStatus.value = "请确保设备已Root并授予应用Root权限"
                _errorMessage.value = "未获取Root权限"
            } else {
                _errorMessage.value = null
            }
        }
    }
    
    /**
     * 检查当前是否正在模拟位置
     */
    private fun checkMockingStatus() {
        val isMocking = LocationInjectorService.isMockingLocation()
        _isMockingLocation.value = isMocking
        
        if (isMocking) {
            val location = LocationInjectorService.getCurrentLocation()
            if (location != null) {
                val (latitude, longitude, _) = location
                _selectedLocation.value = LatLng(latitude, longitude)
                _mockStatus.value = "正在模拟位置: $latitude, $longitude"
                _mockDetailedStatus.value = "位置模拟中，上次更新: ${formatDateTime(System.currentTimeMillis())}"
                _lastUpdateTime.value = System.currentTimeMillis()
            }
        } else {
            _mockStatus.value = "未开始模拟位置"
            _mockDetailedStatus.value = "请选择位置并点击开始模拟"
        }
    }
    
    /**
     * 开始定期刷新状态
     */
    private fun startStatusRefresh() {
        viewModelScope.launch {
            while (true) {
                delay(5000) // 每5秒刷新一次
                refreshStatus()
            }
        }
    }
    
    /**
     * 刷新状态
     */
    fun refreshStatus() {
        try {
            val isMocking = LocationInjectorService.isMockingLocation()
            _isMockingLocation.value = isMocking
            
            if (isMocking) {
                val location = LocationInjectorService.getCurrentLocation()
                if (location != null) {
                    val (latitude, longitude, accuracy) = location
                    _mockStatus.value = "正在模拟位置"
                    _mockDetailedStatus.value = "位置: $latitude, $longitude\n精度: $accuracy 米\n上次更新: ${formatDateTime(System.currentTimeMillis())}"
                    _lastUpdateTime.value = System.currentTimeMillis()
                }
                
                // 发送刷新状态请求到服务
                val intent = Intent(getApplication(), LocationInjectorService::class.java).apply {
                    action = LocationInjectorService.ACTION_REFRESH_STATUS
                }
                getApplication<Application>().startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "刷新状态时出错: ${e.message}")
        }
    }
    
    /**
     * 格式化日期时间
     */
    private fun formatDateTime(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
    
    /**
     * 开始模拟位置
     */
    fun startMockLocation() {
        val location = _selectedLocation.value
        if (location == null) {
            _mockStatus.value = "请先选择位置"
            _errorMessage.value = "未选择位置"
            return
        }
        
        try {
            Log.d(TAG, "Starting mock location: $location")
            
            // 创建Intent启动服务
            val intent = Intent(getApplication(), LocationInjectorService::class.java).apply {
                action = LocationInjectorService.ACTION_START_MOCK
                putExtra(LocationInjectorService.EXTRA_LATITUDE, location.latitude)
                putExtra(LocationInjectorService.EXTRA_LONGITUDE, location.longitude)
                putExtra(LocationInjectorService.EXTRA_ACCURACY, 10.0)
            }
            
            // 启动前台服务
            getApplication<Application>().startService(intent)
            
            // 更新状态
            _isMockingLocation.value = true
            _mockStatus.value = "正在模拟位置"
            _mockDetailedStatus.value = "位置: ${location.latitude}, ${location.longitude}\n精度: 10.0 米\n正在启动..."
            _lastUpdateTime.value = System.currentTimeMillis()
            _errorMessage.value = null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting mock location: ${e.message}")
            _mockStatus.value = "启动模拟失败"
            _mockDetailedStatus.value = "错误: ${e.message}"
            _errorMessage.value = "启动失败: ${e.message}"
            _isMockingLocation.value = false
        }
    }
    
    /**
     * 停止模拟位置
     */
    fun stopMockLocation() {
        try {
            Log.d(TAG, "Stopping mock location")
            
            // 创建Intent停止服务
            val intent = Intent(getApplication(), LocationInjectorService::class.java).apply {
                action = LocationInjectorService.ACTION_STOP_MOCK
            }
            
            // 停止服务
            getApplication<Application>().startService(intent)
            
            // 更新状态
            _isMockingLocation.value = false
            _mockStatus.value = "已停止模拟位置"
            _mockDetailedStatus.value = "位置模拟已停止"
            _errorMessage.value = null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping mock location: ${e.message}")
            _mockStatus.value = "停止模拟失败"
            _mockDetailedStatus.value = "停止失败: ${e.message}"
            _errorMessage.value = "停止失败: ${e.message}"
        }
    }
    
    /**
     * 更新模拟位置
     */
    fun updateMockLocation() {
        val location = _selectedLocation.value
        if (location == null) {
            _mockStatus.value = "请先选择位置"
            _errorMessage.value = "未选择位置"
            return
        }
        
        if (_isMockingLocation.value != true) {
            startMockLocation()
            return
        }
        
        try {
            Log.d(TAG, "Updating mock location: $location")
            
            // 创建Intent更新位置
            val intent = Intent(getApplication(), LocationInjectorService::class.java).apply {
                action = LocationInjectorService.ACTION_UPDATE_LOCATION
                putExtra(LocationInjectorService.EXTRA_LATITUDE, location.latitude)
                putExtra(LocationInjectorService.EXTRA_LONGITUDE, location.longitude)
                putExtra(LocationInjectorService.EXTRA_ACCURACY, 10.0)
            }
            
            // 发送更新位置请求
            getApplication<Application>().startService(intent)
            
            // 更新状态
            _mockStatus.value = "正在更新位置"
            _mockDetailedStatus.value = "位置: ${location.latitude}, ${location.longitude}\n精度: 10.0 米\n正在更新..."
            _lastUpdateTime.value = System.currentTimeMillis()
            _errorMessage.value = null
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating mock location: ${e.message}")
            _mockStatus.value = "更新位置失败"
            _mockDetailedStatus.value = "更新失败: ${e.message}"
            _errorMessage.value = "更新失败: ${e.message}"
        }
    }
    
    /**
     * 设置模拟状态
     */
    fun setMockStatus(status: String, detailedStatus: String, methods: List<String>) {
        _mockStatus.value = status
        _mockDetailedStatus.value = detailedStatus
        _mockMethods.value = methods
        _lastUpdateTime.value = System.currentTimeMillis()
    }
    
    /**
     * 设置错误信息
     */
    fun setErrorMessage(message: String?) {
        _errorMessage.value = message
    }
    
    override fun onCleared() {
        super.onCleared()
        // 如果ViewModel被销毁，确保停止位置模拟
        if (_isMockingLocation.value == true) {
            stopMockLocation()
        }
    }
} 