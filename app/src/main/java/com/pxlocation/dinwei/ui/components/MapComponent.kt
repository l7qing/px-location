package com.pxlocation.dinwei.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle

// 定义统一的地图缩放级别常量
private const val MAP_ZOOM_LEVEL = 17f // 增加缩放级别显示约100米范围
private const val TAG = "MapComponent"

/**
 * 地图组件类，用于外部控制地图
 */
class MapComponent {
    private var mapInstance: AMap? = null
    private val currentMarker = mutableMapOf<String, Marker?>()
    private var lastZoomLevel: Float = MAP_ZOOM_LEVEL // 使用统一的缩放级别常量
    
    fun setMapInstance(map: AMap) {
        this.mapInstance = map
    }
    
    /**
     * 移动到指定位置
     */
    fun moveToLocation(latLng: LatLng, showMarker: Boolean = true) {
        Log.d(TAG, "移动到位置: $latLng")
        mapInstance?.let { map ->
            try {
                // 记录传入的坐标值，以便于调试
                val lat = latLng.latitude
                val lng = latLng.longitude
                Log.d(TAG, "准备移动地图到坐标: 纬度=$lat, 经度=$lng")
                
                // 清除所有现有标记
                map.clear()
                currentMarker.clear()
                
                // 使用统一的缩放级别常量
                Log.d(TAG, "使用缩放级别: $MAP_ZOOM_LEVEL")
                val cameraUpdate = CameraUpdateFactory.newLatLngZoom(latLng, MAP_ZOOM_LEVEL)
                map.animateCamera(cameraUpdate, 1000, object : AMap.CancelableCallback {
                    override fun onFinish() {
                        Log.d(TAG, "相机动画完成，当前缩放级别: ${map.cameraPosition.zoom}")
                    }
                    
                    override fun onCancel() {
                        Log.d(TAG, "相机动画被取消")
                    }
                })
                
                // 如果需要显示标记
                if (showMarker) {
                    // 添加新标记 - 确保使用传入的坐标
                    val marker = map.addMarker(
                        MarkerOptions()
                            .position(latLng)  // 使用传入的坐标
                            .title("选中位置")
                            .snippet("${latLng.latitude}, ${latLng.longitude}")
                            .draggable(true)
                            // 使用更明显的标记图标
                            .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                            // 设置锚点，使标记底部中心对准位置
                            .anchor(0.5f, 1.0f)
                    )
                    
                    // 设置标记的缩放比例，使其更大更明显
                    marker?.setFlat(false) // 不平铺在地图上
                    marker?.isClickable = true
                    
                    // 添加到跟踪列表
                    currentMarker["selected"] = marker
                    
                    // 显示信息窗口
                    marker?.showInfoWindow()
                    
                    // 添加圆形区域标记，使位置更明显
                    map.addCircle(
                        com.amap.api.maps.model.CircleOptions()
                            .center(latLng)
                            .radius(50.0) // 设置为50米半径，在高缩放级别下更合适
                            .fillColor(android.graphics.Color.argb(50, 255, 0, 0)) // 半透明红色填充
                            .strokeColor(android.graphics.Color.RED) // 红色边框
                            .strokeWidth(2f) // 边框宽度
                    )
                    
                    Log.d(TAG, "已更新选中位置标记: 纬度=${latLng.latitude}, 经度=${latLng.longitude}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "移动到位置时出错: ${e.message}")
                e.printStackTrace()
            }
        } ?: Log.e(TAG, "地图实例为空，无法移动")
    }
}

/**
 * 地图组件Composable函数
 */
@Composable
fun MapComponent(
    modifier: Modifier = Modifier,
    onMapLoaded: () -> Unit = {},
    onLocationSelected: (LatLng) -> Unit = {}
): MapComponent {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // 创建地图控制器
    val mapController = remember { MapComponent() }
    
    // 创建地图视图
    val mapView = remember {
        MapView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
    }
    
    // 记录当前标记点
    val currentMarker = remember { 
        mutableMapOf<String, Marker?>() 
    }
    
    // 处理地图生命周期
    DisposableEffect(lifecycleOwner) {
        val bundle: Bundle? = null
        mapView.onCreate(bundle)
        
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_CREATE -> mapView.onCreate(bundle)
                Lifecycle.Event.ON_START -> {}
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> {}
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }
    
    // 渲染地图
    AndroidView(
        factory = { mapView },
        modifier = modifier.fillMaxSize()
    ) { view ->
        // 获取AMap实例
        val aMap = view.map 
        
        if (aMap == null) {
            Log.e(TAG, "地图实例为空，无法显示地图")
            return@AndroidView
        }
        
        // 将地图实例保存到控制器
        mapController.setMapInstance(aMap)
        
        try {
            Log.d(TAG, "开始设置地图...")
            setupMap(aMap, context, onMapLoaded)
            
            // 设置地图点击事件
            aMap.setOnMapClickListener { latLng ->
                Log.d(TAG, "地图点击事件: $latLng")
                updateSelectedLocation(aMap, latLng, currentMarker)
                onLocationSelected(latLng)
            }
            
            // 设置标记拖动事件
            aMap.setOnMarkerDragListener(object : AMap.OnMarkerDragListener {
                override fun onMarkerDrag(marker: Marker) {
                    // 拖动中
                }
                
                override fun onMarkerDragEnd(marker: Marker) {
                    // 拖动结束
                    val newPosition = marker.position
                    Log.d(TAG, "标记拖动结束，新位置: $newPosition")
                    onLocationSelected(newPosition)
                }
                
                override fun onMarkerDragStart(marker: Marker) {
                    // 开始拖动
                }
            })
            
            // 设置点击POI的事件
            aMap.setOnPOIClickListener { poi ->
                Log.d(TAG, "点击POI: ${poi.name}, 位置: ${poi.coordinate}")
                updateSelectedLocation(aMap, poi.coordinate, currentMarker)
                onLocationSelected(poi.coordinate)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up map: ${e.message}")
            e.printStackTrace()
        }
    }
    
    return mapController
}

/**
 * 设置地图
 */
private fun setupMap(aMap: AMap, context: Context, onMapLoaded: () -> Unit) {
    try {
        Log.d(TAG, "正在配置地图...")
        
        // 设置地图样式
        aMap.mapType = AMap.MAP_TYPE_NORMAL
        
        // 设置最大和最小缩放级别
        aMap.maxZoomLevel = 21f  // 确保支持最大缩放级别
        aMap.minZoomLevel = 3f   // 最小缩放级别，可以看到整个国家
        
        // 启用室内地图，在高缩放级别时能看到建筑内部结构
        aMap.showIndoorMap(true)
        
        // 启用楼层切换控件（如果支持）
        aMap.showIndoorMap(true)
        
        // 启用卫星图层（可选，取决于需求）
        // aMap.mapType = AMap.MAP_TYPE_SATELLITE
        
        // 启用交通流量图层（可选）
        // aMap.setTrafficEnabled(true)
        
        // 设置地图UI设置
        val uiSettings = aMap.uiSettings
        uiSettings.isZoomControlsEnabled = true    // 显示缩放按钮
        uiSettings.isCompassEnabled = true         // 显示指南针
        uiSettings.isMyLocationButtonEnabled = true // 显示定位按钮
        uiSettings.isScaleControlsEnabled = true   // 显示比例尺
        uiSettings.isZoomGesturesEnabled = true    // 允许手势缩放
        uiSettings.isScrollGesturesEnabled = true  // 允许滑动手势
        uiSettings.isRotateGesturesEnabled = true  // 允许旋转手势
        uiSettings.isTiltGesturesEnabled = true    // 允许倾斜手势
        
        // 开启定位图层
        val myLocationStyle = MyLocationStyle()
        myLocationStyle.myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
        myLocationStyle.showMyLocation(true)
        
        // 设置定位精度圈的填充颜色
        myLocationStyle.radiusFillColor(android.graphics.Color.argb(50, 0, 0, 180))
        // 设置定位精度圈的边框颜色
        myLocationStyle.strokeColor(android.graphics.Color.argb(100, 0, 0, 220))
        // 设置圆形的边框粗细
        myLocationStyle.strokeWidth(1.0f)
        
        aMap.setMyLocationStyle(myLocationStyle)
        aMap.isMyLocationEnabled = true
        
        // 仅在地图首次加载时设置默认视图为中国全图
        // 不保存任何默认位置，避免干扰用户选择
        
        // 设置地图加载完成监听器
        aMap.setOnMapLoadedListener {
            Log.d(TAG, "地图加载完成")
            // 地图加载完成后，显示中国全图
            val chinaCenter = LatLng(35.86166, 104.195397) // 中国中心点
            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(chinaCenter, 4f))
            
            // 添加提示，告诉用户可以直接点击地图选择位置
            val toast = android.widget.Toast.makeText(
                context,
                "请点击地图选择位置，或使用\"定位到我的实际位置\"按钮",
                android.widget.Toast.LENGTH_LONG
            )
            toast.show()
            
            onMapLoaded()
        }
        
        // 检查定位权限
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "定位权限未授予，地图定位功能将不可用")
        } else {
            Log.d(TAG, "定位权限已授予，启用地图定位")
        }
        
        // 设置缩放变化监听器，记录缩放级别变化
        aMap.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition?) {
                // 相机位置变化中
            }
            
            override fun onCameraChangeFinish(position: CameraPosition?) {
                position?.let {
                    Log.d(TAG, "相机位置变化完成，当前缩放级别: ${it.zoom}, 位置: ${it.target}")
                }
            }
        })
        
    } catch (e: Exception) {
        Log.e(TAG, "设置地图时出错: ${e.message}")
        e.printStackTrace()
    }
}

/**
 * 更新选中的位置
 */
private fun updateSelectedLocation(
    aMap: AMap,
    latLng: LatLng,
    currentMarker: MutableMap<String, Marker?>
) {
    try {
        // 详细记录接收到的位置信息
        val lat = latLng.latitude
        val lng = latLng.longitude
        Log.d(TAG, "更新选中位置: 纬度=$lat, 经度=$lng")
        
        // 清除所有标记，不仅是selected标记
        aMap.clear()
        currentMarker.clear()
        
        // 移动相机到选中位置并使用统一的缩放级别常量
        Log.d(TAG, "设置地图缩放级别: $MAP_ZOOM_LEVEL")
        val cameraUpdate = CameraUpdateFactory.newLatLngZoom(latLng, MAP_ZOOM_LEVEL)
        aMap.animateCamera(cameraUpdate, 1000, object : AMap.CancelableCallback {
            override fun onFinish() {
                Log.d(TAG, "地图缩放动画完成，当前缩放级别: ${aMap.cameraPosition.zoom}")
            }
            
            override fun onCancel() {
                Log.d(TAG, "地图缩放动画被取消")
            }
        })
        
        // 添加新标记 - 使用更醒目的标记
        val marker = aMap.addMarker(
            MarkerOptions()
                .position(latLng)  // 使用传入的真实位置
                .title("选中位置")
                .snippet("${latLng.latitude}, ${latLng.longitude}")
                .draggable(true)
                // 使用明亮的红色标记
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED))
                // 设置锚点，使标记底部中心对准位置
                .anchor(0.5f, 1.0f)
        )
        
        // 设置标记的Z轴高度，确保它显示在所有其他元素之上
        marker.zIndex = 10f
        marker.isClickable = true
        
        // 添加圆形区域标记，使位置更明显
        aMap.addCircle(
            com.amap.api.maps.model.CircleOptions()
                .center(latLng)
                .radius(50.0) // 设置为50米半径，在高缩放级别下更合适
                .fillColor(android.graphics.Color.argb(50, 255, 0, 0)) // 半透明红色填充
                .strokeColor(android.graphics.Color.RED) // 红色边框
                .strokeWidth(2f) // 边框宽度
        )
        
        currentMarker["selected"] = marker
        
        // 显示信息窗口
        marker.showInfoWindow()
        
        Log.d(TAG, "已更新选中位置标记: 纬度=$lat, 经度=$lng")
    } catch (e: Exception) {
        Log.e(TAG, "更新选中位置时出错: ${e.message}")
        e.printStackTrace()
    }
} 