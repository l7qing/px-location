/**
 * PX位置模拟 主活动
 * 
 * 位置模拟优化修复说明：
 * 1. 系统服务劫持增强：
 *    - 添加了设备特定的服务调用命令（华为、小米、OPPO、Vivo、三星等）
 *    - 优化了命令执行和验证逻辑，自动重试成功的命令
 *    - 添加了SELinux临时禁用处理，解决权限问题
 *    - 增加了对不同系统版本的兼容性支持
 * 
 * 2. 文件系统操作改进：
 *    - 新增forceWriteLocationToFiles方法，写入位置数据到10+种可能的系统路径
 *    - 改进写入命令格式，确保跨设备兼容性
 *    - 批量执行命令以提高稳定性，分批处理避免命令行过长
 *    - 扩展了系统属性设置，覆盖更多可能的配置项
 * 
 * 3. 验证逻辑优化：
 *    - 改进位置验证方法，增加详细日志记录验证过程
 *    - 使用更短的前缀匹配以处理精度差异问题
 *    - 增加备用系统文件和属性检查，提高验证成功率
 *    - 对验证失败但有部分成功的情况进行处理
 * 
 * 4. 地图应用支持增强：
 *    - 针对高德地图和百度地图增加专门处理逻辑
 *    - 增加配置文件写入和权限设置
 *    - 添加特定于应用的广播命令
 *    - 实现应用运行检测的温和方法
 * 
 * 5. 服务流程优化：
 *    - 减少环境重置频率，从每5次改为每3次，提高性能
 *    - 增加延迟时间，给系统更多时间处理位置变更
 *    - 增强错误处理和重试机制，提高模拟持久性
 *    - 优化位置模拟循环，失败后自动切换到备选方法
 * 
 * 6. NMEA数据优化：
 *    - 实现标准NMEA格式数据生成，支持GPGGA格式
 *    - 添加校验和计算，确保数据有效性
 *    - 正确处理坐标转换为度分格式
 *    - 使用UTC时间确保时间标记准确
 * 
 * 7. 广播增强：
 *    - 扩展位置变更广播的参数，提高兼容性
 *    - 添加GPS状态变更序列，先禁用再启用
 *    - 实现特定应用的定向广播
 *    - 检测验证失败时自动发送额外广播
 * 
 * 8. 调试与日志：
 *    - 全面增强日志系统，记录每个操作的详细过程
 *    - 添加设备信息记录，便于问题排查
 *    - 跟踪成功的命令，用于后续优化
 *    - 验证时保存完整日志，便于故障分析
 * 
 * 9. 安全性改进：
 *    - 安全处理SELinux状态，操作后恢复原状态
 *    - 文件操作添加错误处理，避免失败时中断
 *    - 改进Shell命令构建，避免特殊字符导致的问题
 *    - 批量处理避免单点故障
 * 
 * 10. 设备兼容性：
 *     - 对华为、小米、OPPO/Realme、Vivo、三星等设备增加专门支持
 *     - 处理不同Android版本的服务调用差异
 *     - 使用制造商和型号信息调整策略
 *     - 添加特定厂商文件路径支持
 */
package com.pxlocation.dinwei

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import com.pxlocation.dinwei.ui.PrivacyPolicyActivity
import com.pxlocation.dinwei.ui.components.LocationSearchBar
import com.pxlocation.dinwei.ui.components.MapComponent
import com.pxlocation.dinwei.ui.theme.PXlocationTheme
import com.pxlocation.dinwei.utils.PermissionUtils
import com.pxlocation.dinwei.utils.RootUtils
import com.pxlocation.dinwei.viewmodel.LocationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Color

// 定义格式化小数点的扩展函数
fun Double.formatCoordinate(digits: Int): String {
    return String.format(Locale.US, "%.${digits}f", this)
}

// 格式化日期时间
fun formatDateTime(timestamp: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(timestamp))
}

class MainActivity : ComponentActivity() {
    
    // 对话框状态
    private val showLocationPermissionDialog = mutableStateOf(false)
    private val showRootPermissionDialog = mutableStateOf(false)
    
    // 位置状态
    private val selectedLocation = mutableStateOf<LatLng?>(null)
    private val isMockingLocation = mutableStateOf(false)
    private val locationAddress = mutableStateOf("获取地址中...")
    
    // 地图组件引用
    private var mapComponentRef: com.pxlocation.dinwei.ui.components.MapComponent? = null
    
    // 添加ViewModel引用
    private lateinit var locationViewModel: LocationViewModel
    
    companion object {
        private const val TAG = "MainActivity"
        private const val LOCATION_PERMISSION_REQUEST_CODE = 1001
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            // 初始化ViewModel
            locationViewModel = ViewModelProvider(this)[LocationViewModel::class.java]
            
            // 检查隐私政策是否已接受
            if (!PrivacyPolicyActivity.isPrivacyPolicyAccepted(this)) {
                Log.d(TAG, "Privacy policy not accepted, launching PrivacyPolicyActivity")
                PrivacyPolicyActivity.start(this)
                return
            } else {
                Log.d(TAG, "Privacy policy already accepted")
            }
            
            setContent {
                PXlocationTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        var mapLoaded by remember { mutableStateOf(false) }
                        var searchText by remember { mutableStateOf("") }
                        
                        // 使用ViewModel的LiveData
                        val selectedLocation by locationViewModel.selectedLocation.observeAsState()
                        val locationAddress by locationViewModel.locationAddress.observeAsState("获取地址中...")
                        val isMockingLocation by locationViewModel.isMockingLocation.observeAsState(false)
                        val mockStatus by locationViewModel.mockStatus.observeAsState("未开始模拟")
                        val rootStatus by locationViewModel.rootStatus.observeAsState(false)
                        val mockDetailedStatus by locationViewModel.mockDetailedStatus.observeAsState("")
                        val errorMessage by locationViewModel.errorMessage.observeAsState()
                        val lastUpdateTime by locationViewModel.lastUpdateTime.observeAsState(0L)
                        
                        var showLocationPermissionDialog by remember { mutableStateOf(false) }
                        var showRootPermissionDialog by remember { mutableStateOf(false) }
                        
                        // 创建协程作用域
                        val coroutineScope = rememberCoroutineScope()
                        
                        // 地理编码搜索器
                        val geocodeSearch = remember {
                            GeocodeSearch(this@MainActivity).apply {
                                setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                                    override fun onRegeocodeSearched(result: RegeocodeResult?, rCode: Int) {
                                        if (rCode == 1000 && result != null) {
                                            val address = result.regeocodeAddress?.formatAddress ?: "未知地址"
                                            Log.d(TAG, "获取到地址: $address")
                                            locationViewModel.setLocationAddress(address)
                                        } else {
                                            val errorMsg = "获取地址失败，错误码: $rCode"
                                            Log.e(TAG, errorMsg)
                                            locationViewModel.setLocationAddress("地址获取失败")
                                        }
                                    }

                                    override fun onGeocodeSearched(result: GeocodeResult?, rCode: Int) {
                                        // 不需要实现，因为我们只使用反向地理编码
                                    }
                                })
                            }
                        }
                        
                        // 当选中位置变化时，反向地理编码获取地址
                        LaunchedEffect(key1 = selectedLocation) {
                            if (selectedLocation != null) {
                                try {
                                    Log.d(TAG, "开始获取位置地址: $selectedLocation")
                                    locationViewModel.setLocationAddress("获取地址中...")
                                    
                                    val query = RegeocodeQuery(
                                        LatLonPoint(selectedLocation?.latitude ?: 0.0, selectedLocation?.longitude ?: 0.0),
                                        200f, GeocodeSearch.AMAP
                                    )
                                    geocodeSearch.getFromLocationAsyn(query)
                                } catch (e: Exception) {
                                    Log.e(TAG, "获取地址时出错: ${e.message}")
                                    locationViewModel.setLocationAddress("地址获取失败: ${e.message}")
                                }
                            }
                        }
                        
                        // 检查权限
                        LaunchedEffect(key1 = Unit) {
                            // 检查位置权限
                            if (!PermissionUtils.hasLocationPermissions(this@MainActivity)) {
                                Log.d(TAG, "没有位置权限")
                                showLocationPermissionDialog = true
                            }
                            
                            // 检查Root权限
                            if (!RootUtils.isDeviceRooted()) {
                                Log.d(TAG, "没有Root权限")
                                showRootPermissionDialog = true
                            }
                        }
                        
                        // 主界面布局
                        Box(modifier = Modifier.fillMaxSize()) {
                            // 地图组件
                            val mapComponent = MapComponent(
                                modifier = Modifier.fillMaxSize(),
                                onMapLoaded = {
                                    Log.d(TAG, "地图加载完成")
                                    mapLoaded = true
                                },
                                onLocationSelected = { latLng ->
                                    Log.d(TAG, "地图点击: $latLng")
                                    locationViewModel.setSelectedLocation(latLng)
                                }
                            )
                            
                            // 保存地图组件引用
                            DisposableEffect(mapComponent) {
                                Log.d(TAG, "注册地图组件")
                                mapComponentRef = mapComponent
                                
                                onDispose {
                                    Log.d(TAG, "清除地图组件引用")
                                    mapComponentRef = null
                                }
                            }
                            
                            // 顶部搜索栏
                            LocationSearchBar(
                                onLocationSelected = { latLng ->
                                    Log.d(TAG, "从搜索栏选择位置: $latLng")
                                    locationViewModel.setSelectedLocation(latLng)
                                    
                                    // 移动地图到选择的位置
                                    mapComponentRef?.moveToLocation(latLng, true)
                                },
                                onGetCurrentLocation = {
                                    Log.d(TAG, "获取当前位置")
                                    showUserActualLocation()
                                }
                            )
                            
                            // 添加状态卡片在顶部
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 80.dp) // 留出搜索栏的空间
                            ) {
                                StatusCard(
                                    mockStatus = mockStatus,
                                    isMockingLocation = isMockingLocation,
                                    rootStatus = rootStatus,
                                    mockDetailedStatus = mockDetailedStatus,
                                    errorMessage = errorMessage,
                                    lastUpdateTime = lastUpdateTime
                                )
                            }
                            
                            // 底部信息卡片
                            if (selectedLocation != null) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp)
                                        .align(Alignment.BottomCenter),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = locationAddress,
                                            style = MaterialTheme.typography.titleMedium,
                                            textAlign = TextAlign.Center
                                        )
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        // 使用安全调用并提供默认值来避免智能转换问题
                                        val lat = selectedLocation?.latitude ?: 0.0
                                        val lng = selectedLocation?.longitude ?: 0.0
                                        Text(
                                            text = "经度: ${lng.formatCoordinate(6)}\n纬度: ${lat.formatCoordinate(6)}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            textAlign = TextAlign.Center
                                        )
                                        
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        // 显示模拟状态
                                        Text(
                                            text = mockStatus,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isMockingLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            textAlign = TextAlign.Center
                                        )
                                        
                                        Spacer(modifier = Modifier.height(16.dp))
                                        
                                        // 模拟位置按钮
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceEvenly
                                        ) {
                                            Button(
                                                onClick = {
                                                    if (isMockingLocation) {
                                                        locationViewModel.stopMockLocation()
                                                    } else {
                                                        locationViewModel.startMockLocation()
                                                    }
                                                },
                                                enabled = rootStatus && selectedLocation != null
                                            ) {
                                                Text(if (isMockingLocation) "停止模拟" else "开始模拟")
                                            }
                                            
                                            Button(
                                                onClick = {
                                                    locationViewModel.updateMockLocation()
                                                },
                                                enabled = rootStatus && selectedLocation != null && isMockingLocation
                                            ) {
                                                Text("更新位置")
                                            }
                                        }
                                    }
                                }
                            }
                            
                            // 加载指示器
                            if (!mapLoaded) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                            
                            // 位置权限对话框
                            if (showLocationPermissionDialog) {
                                AlertDialog(
                                    onDismissRequest = { showLocationPermissionDialog = false },
                                    title = { Text("需要位置权限") },
                                    text = { Text("此应用需要位置权限才能提供完整功能。") },
                                    confirmButton = {
                                        TextButton(onClick = {
                                            showLocationPermissionDialog = false
                                            requestLocationPermission()
                                        }) {
                                            Text("授予权限")
                                        }
                                    },
                                    dismissButton = {
                                        TextButton(onClick = { showLocationPermissionDialog = false }) {
                                            Text("取消")
                                        }
                                    }
                                )
                            }
                            
                            // Root权限对话框
                            if (showRootPermissionDialog) {
                                AlertDialog(
                                    onDismissRequest = { showRootPermissionDialog = false },
                                    title = { Text("需要Root权限") },
                                    text = { Text("此应用需要Root权限才能模拟位置。请确保您的设备已获取Root权限。") },
                                    confirmButton = {
                                        TextButton(onClick = { showRootPermissionDialog = false }) {
                                            Text("了解")
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建主活动时出错: ${e.message}")
            e.printStackTrace()
        }
    }
    
    /**
     * 请求位置权限
     */
    private fun requestLocationPermission() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ),
            LOCATION_PERMISSION_REQUEST_CODE
        )
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "位置权限已授予")
                // 权限已授予，可以进行定位操作
            } else {
                Log.d(TAG, "位置权限被拒绝")
                Toast.makeText(this, "需要位置权限才能使用此功能", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 获取用户实际位置
     */
    private fun showUserActualLocation() {
        try {
            Log.d(TAG, "开始获取用户实际位置")
            
            // 检查定位权限
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "没有精确定位权限，无法获取实际位置")
                Toast.makeText(this, "需要位置权限才能获取实际位置", Toast.LENGTH_SHORT).show()
                requestLocationPermission()
                return
            }
            
            // 使用高德地图的定位功能获取当前位置
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val locationClient = com.amap.api.location.AMapLocationClient(applicationContext)
                    val locationOption = com.amap.api.location.AMapLocationClientOption()
                    locationOption.isOnceLocation = true
                    locationOption.isNeedAddress = true
                    locationClient.setLocationOption(locationOption)
                    
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var userLocation: com.amap.api.location.AMapLocation? = null
                    
                    locationClient.setLocationListener { location ->
                        if (location != null && location.errorCode == 0) {
                            userLocation = location
                        } else {
                            Log.e(TAG, "定位失败: ${location?.errorInfo}")
                        }
                        latch.countDown()
                    }
                    
                    locationClient.startLocation()
                    latch.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    locationClient.stopLocation()
                    locationClient.onDestroy()
                    
                    withContext(Dispatchers.Main) {
                        if (userLocation != null) {
                            val latLng = LatLng(userLocation!!.latitude, userLocation!!.longitude)
                            Log.d(TAG, "获取到用户实际位置: $latLng, 地址: ${userLocation!!.address}")
                            
                            // 更新选中位置
                            locationViewModel.setSelectedLocation(latLng)
                            locationViewModel.setLocationAddress(userLocation!!.address ?: "未知地址")
                            
                            // 移动地图到用户实际位置
                            mapComponentRef?.let { mapComponent ->
                                Log.d(TAG, "移动地图到用户实际位置")
                                mapComponent.moveToLocation(latLng, true)
                            } ?: run {
                                Log.e(TAG, "地图组件引用为空，无法移动地图")
                                Toast.makeText(this@MainActivity, "地图组件未准备好，请稍后再试", Toast.LENGTH_SHORT).show()
                            }
                            
                            Toast.makeText(this@MainActivity, "已定位到您的当前位置", Toast.LENGTH_SHORT).show()
                        } else {
                            Log.e(TAG, "获取用户实际位置失败")
                            Toast.makeText(this@MainActivity, "无法获取您的当前位置", Toast.LENGTH_SHORT).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "获取用户实际位置时出错: ${e.message}")
                    e.printStackTrace()
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "获取位置时出错: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理实际位置时出错: ${e.message}")
            e.printStackTrace()
            Toast.makeText(this, "处理位置时出错: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
fun MainScreen(
    selectedLocation: LatLng?,
    isMockingLocation: Boolean,
    locationAddress: String,
    onStartMock: () -> Unit,
    onStopMock: () -> Unit
) {
    // 主屏幕布局
}

@Composable
fun StatusCard(
    mockStatus: String,
    isMockingLocation: Boolean,
    rootStatus: Boolean,
    mockDetailedStatus: String = "",
    errorMessage: String? = null,
    lastUpdateTime: Long = 0L
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(
                width = 1.dp,
                color = if (isMockingLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                shape = MaterialTheme.shapes.medium
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "模拟状态",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                
                Box(
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .background(
                            color = if (isMockingLocation) Color.Green else Color.Red,
                            shape = MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = if (isMockingLocation) "活跃" else "未激活",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // 状态信息
            Text(
                text = mockStatus,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = if (isMockingLocation) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
            )
            
            // 详细状态信息
            if (mockDetailedStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = mockDetailedStatus,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center
                )
            }
            
            // 错误信息
            if (!errorMessage.isNullOrEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "错误: $errorMessage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Root状态
            Text(
                text = if (rootStatus) "Root权限: 已获取" else "Root权限: 未获取",
                style = MaterialTheme.typography.bodySmall,
                color = if (rootStatus) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
            
            // 最后更新时间
            if (lastUpdateTime > 0) {
                Text(
                    text = "上次更新: ${formatDateTime(lastUpdateTime)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}