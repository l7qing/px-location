package com.pxlocation.dinwei.ui.components

import android.content.Context
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay

private const val TAG = "SearchBar"
private const val MAX_SEARCH_HISTORY = 5

/**
 * 位置搜索栏，参考主流导航软件设计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSearchBar(
    onLocationSelected: (LatLng) -> Unit,
    onGetCurrentLocation: () -> Unit = {}
) {
    // 状态管理
    var searchQuery by remember { mutableStateOf("") }
    var isSearchMode by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchHistory = remember { mutableStateListOf<SearchResult>() }
    val context = LocalContext.current
    
    // 创建更详细的地点数据库，包括知名地点
    val allLocations = remember {
        listOf(
            // 省会城市和主要城市
            SearchResult("北京市", "中国首都", LatLng(39.90923, 116.397428)),
            SearchResult("上海市", "中国金融中心", LatLng(31.22967, 121.4762)),
            SearchResult("广州市", "广东省省会", LatLng(23.12908, 113.26436)),
            SearchResult("深圳市", "广东省特区", LatLng(22.54286, 114.05956)),
            SearchResult("杭州市", "浙江省省会", LatLng(30.27415, 120.15515)),
            SearchResult("成都市", "四川省省会", LatLng(30.57285, 104.06669)),
            SearchResult("重庆市", "直辖市", LatLng(29.56301, 106.55073)),
            SearchResult("西安市", "陕西省省会", LatLng(34.34127, 108.93984)),
            SearchResult("武汉市", "湖北省省会", LatLng(30.59276, 114.30525)),
            SearchResult("南京市", "江苏省省会", LatLng(32.05838, 118.79647)),
            SearchResult("天津市", "直辖市", LatLng(39.08496, 117.20100)),
            SearchResult("苏州市", "江苏省", LatLng(31.29834, 120.58319)),
            SearchResult("南宁市", "广西壮族自治区首府", LatLng(22.81673, 108.36660)),
            
            // 特别添加汕头市和下属区县
            SearchResult("汕头市", "广东省地级市", LatLng(23.35379, 116.68146)),
            SearchResult("潮阳区", "广东省汕头市下辖区", LatLng(23.26489, 116.60157)),
            SearchResult("潮南区", "广东省汕头市下辖区", LatLng(23.25026, 116.43388)),
            SearchResult("澄海区", "广东省汕头市下辖区", LatLng(23.46593, 116.75608)),
            SearchResult("金平区", "广东省汕头市下辖区", LatLng(23.36521, 116.70345)),
            SearchResult("龙湖区", "广东省汕头市下辖区", LatLng(23.37195, 116.71914)),
            SearchResult("濠江区", "广东省汕头市下辖区", LatLng(23.28588, 116.72664)),
            SearchResult("南澳县", "广东省汕头市下辖县", LatLng(23.42129, 117.02340)),
            
            // 广东省其他主要城市和区县
            SearchResult("东莞市", "广东省地级市", LatLng(23.02067, 113.75178)),
            SearchResult("佛山市", "广东省地级市", LatLng(23.02185, 113.12192)),
            SearchResult("珠海市", "广东省地级市", LatLng(22.27073, 113.57668)),
            SearchResult("中山市", "广东省地级市", LatLng(22.51595, 113.39277)),
            SearchResult("惠州市", "广东省地级市", LatLng(23.11075, 114.41679)),
            SearchResult("梅州市", "广东省地级市", LatLng(24.28844, 116.12264)),
            SearchResult("汕尾市", "广东省地级市", LatLng(22.78566, 115.37514)),
            SearchResult("河源市", "广东省地级市", LatLng(23.74365, 114.70065)),
            SearchResult("阳江市", "广东省地级市", LatLng(21.85829, 111.98256)),
            SearchResult("清远市", "广东省地级市", LatLng(23.68201, 113.05615)),
            SearchResult("湛江市", "广东省地级市", LatLng(21.27134, 110.35894)),
            SearchResult("茂名市", "广东省地级市", LatLng(21.66329, 110.92542)),
            SearchResult("肇庆市", "广东省地级市", LatLng(23.04789, 112.46528)),
            SearchResult("韶关市", "广东省地级市", LatLng(24.81039, 113.59723)),
            SearchResult("江门市", "广东省地级市", LatLng(22.57865, 113.08161)),
            SearchResult("云浮市", "广东省地级市", LatLng(22.91525, 112.04453)),
            SearchResult("揭阳市", "广东省地级市", LatLng(23.54972, 116.37271)),
            SearchResult("潮州市", "广东省地级市", LatLng(23.65695, 116.62262)),
            
            // 中国其他省级行政区
            SearchResult("福建省", "中国省份", LatLng(26.10078, 119.29659)),
            SearchResult("江西省", "中国省份", LatLng(28.67417, 115.91004)),
            SearchResult("湖南省", "中国省份", LatLng(28.11266, 112.98626)),
            SearchResult("湖北省", "中国省份", LatLng(30.54539, 114.34234)),
            SearchResult("河南省", "中国省份", LatLng(34.76571, 113.75322)),
            SearchResult("河北省", "中国省份", LatLng(38.04276, 114.52940)),
            SearchResult("山东省", "中国省份", LatLng(36.66853, 117.02076)),
            SearchResult("山西省", "中国省份", LatLng(37.87343, 112.56272)),
            SearchResult("陕西省", "中国省份", LatLng(34.26486, 108.95424)),
            SearchResult("甘肃省", "中国省份", LatLng(36.06138, 103.83417)),
            SearchResult("青海省", "中国省份", LatLng(36.62087, 101.78011)),
            SearchResult("四川省", "中国省份", LatLng(30.65089, 104.07572)),
            SearchResult("云南省", "中国省份", LatLng(25.04915, 102.71225)),
            SearchResult("贵州省", "中国省份", LatLng(26.59765, 106.70722)),
            SearchResult("安徽省", "中国省份", LatLng(31.86157, 117.28565)),
            SearchResult("浙江省", "中国省份", LatLng(30.26555, 120.15251)),
            SearchResult("江苏省", "中国省份", LatLng(32.06167, 118.77778)),
            SearchResult("黑龙江省", "中国省份", LatLng(45.74208, 126.66285)),
            SearchResult("吉林省", "中国省份", LatLng(43.89933, 125.32599)),
            SearchResult("辽宁省", "中国省份", LatLng(41.83571, 123.42925)),
            SearchResult("内蒙古自治区", "中国自治区", LatLng(40.81733, 111.76522)),
            SearchResult("宁夏回族自治区", "中国自治区", LatLng(38.47117, 106.25867)),
            SearchResult("新疆维吾尔自治区", "中国自治区", LatLng(43.82663, 87.61688)),
            SearchResult("西藏自治区", "中国自治区", LatLng(29.64725, 91.11748)),
            SearchResult("广西壮族自治区", "中国自治区", LatLng(22.81521, 108.32754)),
            SearchResult("海南省", "中国省份", LatLng(20.01997, 110.34863)),
            
            // 知名大学
            SearchResult("复旦大学", "上海市杨浦区", LatLng(31.29834, 121.50266)),
            SearchResult("清华大学", "北京市海淀区", LatLng(40.00059, 116.32689)),
            SearchResult("北京大学", "北京市海淀区", LatLng(39.99252, 116.30662)),
            SearchResult("浙江大学", "杭州市西湖区", LatLng(30.26578, 120.12508)),
            SearchResult("上海交通大学", "上海市闵行区", LatLng(31.02425, 121.43066)),
            SearchResult("南京大学", "南京市鼓楼区", LatLng(32.05374, 118.77800)),
            SearchResult("武汉大学", "武汉市武昌区", LatLng(30.53844, 114.36733)),
            SearchResult("中山大学", "广州市海珠区", LatLng(23.09687, 113.29681)),
            SearchResult("四川大学", "成都市武侯区", LatLng(30.63053, 104.08296)),
            SearchResult("华中科技大学", "武汉市洪山区", LatLng(30.51024, 114.41020)),
            SearchResult("西安交通大学", "西安市碑林区", LatLng(34.24528, 108.98348)),
            SearchResult("同济大学", "上海市杨浦区", LatLng(31.28745, 121.50387)),
            
            // 知名购物中心和商圈
            SearchResult("万达广场(北京CBD)", "北京市朝阳区", LatLng(39.90858, 116.47097)),
            SearchResult("万达广场(上海)", "上海市宝山区", LatLng(31.33053, 121.48333)),
            SearchResult("万达广场(广州番禺)", "广州市番禺区", LatLng(23.00781, 113.35317)),
            SearchResult("万达广场(成都)", "成都市锦江区", LatLng(30.57077, 104.06557)),
            SearchResult("大悦城(北京)", "北京市朝阳区", LatLng(39.92483, 116.51434)),
            SearchResult("大悦城(上海)", "上海市普陀区", LatLng(31.23736, 121.41741)),
            SearchResult("大悦城(深圳)", "深圳市南山区", LatLng(22.53731, 113.95064)),
            SearchResult("恒隆广场(上海)", "上海市静安区", LatLng(31.22820, 121.45942)),
            SearchResult("嘉里中心(上海)", "上海市浦东新区", LatLng(31.22450, 121.55954)),
            SearchResult("太古汇(广州)", "广州市天河区", LatLng(23.13282, 113.32670)),
            SearchResult("国贸商城(北京)", "北京市朝阳区", LatLng(39.90871, 116.46063)),
            SearchResult("环球港(上海)", "上海市普陀区", LatLng(31.23509, 121.40687)),
            
            // 知名医院
            SearchResult("北京协和医院", "北京市东城区", LatLng(39.91303, 116.41732)),
            SearchResult("上海瑞金医院", "上海市黄浦区", LatLng(31.21669, 121.46739)),
            SearchResult("中山大学附属第一医院", "广州市越秀区", LatLng(23.13955, 113.26394)),
            SearchResult("华西医院", "成都市武侯区", LatLng(30.64592, 104.06486)),
            SearchResult("北京大学第一医院", "北京市西城区", LatLng(39.93029, 116.36957)),
            SearchResult("上海儿童医学中心", "上海市浦东新区", LatLng(31.19097, 121.52231)),
            
            // 知名地点/景点
            SearchResult("天安门", "北京著名地标", LatLng(39.90865, 116.39749)),
            SearchResult("故宫", "北京紫禁城", LatLng(39.91447, 116.39094)),
            SearchResult("长城", "八达岭长城", LatLng(40.35666, 116.01548)),
            SearchResult("外滩", "上海外滩", LatLng(31.23846, 121.49028)),
            SearchResult("东方明珠", "上海地标", LatLng(31.23952, 121.49983)),
            SearchResult("西湖", "杭州西湖", LatLng(30.25283, 120.14744)),
            SearchResult("泰山", "山东泰山", LatLng(36.26465, 117.10091)),
            SearchResult("黄山", "安徽黄山", LatLng(30.13311, 118.17573)),
            SearchResult("张家界", "湖南张家界", LatLng(29.13259, 110.47839)),
            SearchResult("九寨沟", "四川九寨沟", LatLng(33.16026, 103.91687)),
            SearchResult("颐和园", "北京颐和园", LatLng(39.99932, 116.27186)),
            SearchResult("兵马俑", "西安兵马俑", LatLng(34.38485, 109.27817)),
            SearchResult("雷峰塔", "杭州雷峰塔", LatLng(30.23555, 120.14796)),
            SearchResult("夫子庙", "南京夫子庙", LatLng(32.02359, 118.79532)),
            SearchResult("鼓浪屿", "厦门鼓浪屿", LatLng(24.44641, 118.06438)),
            SearchResult("三亚湾", "海南三亚", LatLng(18.24813, 109.50896)),
            SearchResult("珠峰", "珠穆朗玛峰", LatLng(28.00231, 86.92874)),
            SearchResult("布达拉宫", "西藏拉萨", LatLng(29.65649, 91.11789)),
            SearchResult("长江三峡", "重庆三峡", LatLng(30.82539, 110.9953)),
            SearchResult("大理古城", "云南大理", LatLng(25.69222, 100.16014)),
            
            // 热门商圈
            SearchResult("王府井", "北京王府井商圈", LatLng(39.91472, 116.40827)),
            SearchResult("南京路", "上海南京路步行街", LatLng(31.23610, 121.47337)),
            SearchResult("太古里", "成都春熙路太古里", LatLng(30.65295, 104.08248)),
            SearchResult("西单", "北京西单商圈", LatLng(39.90991, 116.32390)),
            SearchResult("天河城", "广州天河城", LatLng(23.13287, 113.32343)),
            SearchResult("解放碑", "重庆解放碑", LatLng(29.55560, 106.57349)),
            
            // 交通枢纽
            SearchResult("北京首都国际机场", "北京市顺义区", LatLng(40.07949, 116.60347)),
            SearchResult("上海浦东国际机场", "上海市浦东新区", LatLng(31.14340, 121.80805)),
            SearchResult("广州白云国际机场", "广州市白云区", LatLng(23.39191, 113.29893)),
            SearchResult("成都双流国际机场", "成都市双流区", LatLng(30.57850, 103.94913)),
            SearchResult("北京南站", "北京市丰台区", LatLng(39.86518, 116.37352)),
            SearchResult("上海虹桥火车站", "上海市闵行区", LatLng(31.19440, 121.31800)),
            
            // 知名村庄和乡镇
            SearchResult("乌镇", "浙江桐乡古镇", LatLng(30.74717, 120.49103)),
            SearchResult("周庄", "江苏昆山水乡古镇", LatLng(31.11541, 120.85448)),
            SearchResult("西塘", "浙江嘉善古镇", LatLng(30.94084, 120.88970)),
            SearchResult("丽江古城", "云南丽江", LatLng(26.87721, 100.24212)),
            SearchResult("婺源县", "江西上饶黄村", LatLng(29.24841, 117.86144)),
            SearchResult("宏村", "安徽黄山徽派古村", LatLng(29.92799, 117.93097)),
            SearchResult("黄姚古镇", "广西贺州", LatLng(24.27432, 110.75167)),
            SearchResult("凤凰古城", "湖南湘西", LatLng(27.94547, 109.59919)),
            SearchResult("平遥古城", "山西晋中", LatLng(37.20181, 112.17450)),
            SearchResult("阳朔西街", "广西桂林", LatLng(24.77925, 110.49364)),
            SearchResult("束河古镇", "云南丽江", LatLng(26.91865, 100.23501)),
            SearchResult("洪安古镇", "重庆巫溪", LatLng(31.65043, 109.34031)),
            SearchResult("烟墩角村", "浙江嘉兴", LatLng(30.77548, 120.76138)),
            SearchResult("芦沟村", "浙江嘉兴", LatLng(30.70566, 120.73693)),
            SearchResult("柘林镇", "江西上饶", LatLng(28.76159, 118.29520)),
            SearchResult("潼南区", "重庆市", LatLng(30.18098, 105.83901)),
            SearchResult("布依族村寨", "贵州黔南", LatLng(25.87135, 107.52177)),
            SearchResult("苗族村寨", "贵州黔东南", LatLng(26.58382, 108.07636)),
            SearchResult("西沟村", "山西阳泉", LatLng(38.07944, 113.60149)),
            SearchResult("上里古镇", "四川眉山", LatLng(29.98397, 103.81278)),
            SearchResult("稻城县", "四川甘孜", LatLng(29.03704, 100.29769)),
            SearchResult("白族村", "云南大理", LatLng(25.71021, 100.16466)),
            SearchResult("石舍村", "浙江安吉", LatLng(30.65195, 119.54474)),
            SearchResult("塔川村", "江西婺源", LatLng(29.34889, 117.87750)),
            SearchResult("香格里拉", "云南迪庆", LatLng(27.82508, 99.70854)),
            SearchResult("藏族村落", "青海玉树", LatLng(33.00528, 97.00861)),
            SearchResult("北庄村", "河北正定", LatLng(38.18073, 114.57211)),
            SearchResult("水磨村", "四川都江堰", LatLng(31.00361, 103.61917)),
            SearchResult("龙门古镇", "广东惠州", LatLng(23.73866, 114.24959)),
            SearchResult("青龙镇", "重庆巴南区", LatLng(29.40153, 106.53951)),
            SearchResult("江津区", "重庆市", LatLng(29.29014, 106.25939)),
            SearchResult("磐安县", "浙江金华", LatLng(29.05403, 120.45022)),
            SearchResult("古北口村", "北京市密云区", LatLng(40.70798, 117.16554)),
            SearchResult("仙居县", "浙江台州", LatLng(28.84681, 120.72881)),
            SearchResult("云和县", "浙江丽水", LatLng(28.11663, 119.57325)),
            SearchResult("婺城区", "浙江金华", LatLng(29.08624, 119.65250)),
        )
    }
    
    // 执行本地搜索（作为备选方案）
    fun performLocalSearch(searchQuery: String) {
        if (searchQuery.isNotEmpty()) {
            isSearching = true
            try {
                Log.d(TAG, "开始本地搜索: $searchQuery")
                
                // 创建分词器，将搜索词分解为多个关键词
                val keywords = searchQuery.split(" ", "，", ",", "、")
                    .filter { it.isNotEmpty() }
                    .map { it.trim() }
                
                Log.d(TAG, "搜索关键词: $keywords")
                
                // 优先精确匹配市、区、县等行政区划
                val adminResults = allLocations.filter { location ->
                    location.title.contains(searchQuery, ignoreCase = true) &&
                    (location.title.endsWith("市") || location.title.endsWith("区") || 
                     location.title.endsWith("县") || location.title.endsWith("省") || 
                     location.title.endsWith("自治区"))
                }
                
                if (adminResults.isNotEmpty()) {
                    Log.d(TAG, "找到行政区划匹配: ${adminResults.size} 个结果")
                    searchResults = adminResults
                    isSearching = false
                    return
                }
                
                // 更智能的匹配逻辑，支持部分匹配和多关键词匹配
                val filteredResults = allLocations.filter { location ->
                    // 检查是否有任何关键词匹配位置标题或描述
                    keywords.any { keyword ->
                        location.title.contains(keyword, ignoreCase = true) || 
                        location.snippet.contains(keyword, ignoreCase = true)
                    }
                }
                
                // 如果找到匹配结果就使用
                if (filteredResults.isNotEmpty()) {
                    // 按相关性排序：标题匹配优先于描述匹配
                    val sortedResults = filteredResults.sortedWith(compareByDescending { location ->
                        // 计算匹配分数
                        var score = 0
                        keywords.forEach { keyword ->
                            // 标题完全匹配得分最高
                            if (location.title == keyword) score += 100
                            // 标题包含关键词得分次之
                            else if (location.title.contains(keyword, ignoreCase = true)) score += 50
                            // 描述包含关键词得分最低
                            else if (location.snippet.contains(keyword, ignoreCase = true)) score += 20
                        }
                        
                        // 额外加分：行政区划优先
                        if (location.title.endsWith("市") || location.title.endsWith("区") || 
                            location.title.endsWith("县") || location.title.endsWith("省") || 
                            location.title.endsWith("自治区")) {
                            score += 30
                        }
                        score
                    })
                    
                    searchResults = sortedResults
                    Log.d(TAG, "找到 ${filteredResults.size} 个匹配结果，排序后最佳匹配: ${sortedResults.firstOrNull()?.title}")
                } else {
                    // 搜索结果为空，尝试更宽松的匹配
                    val looseResults = allLocations.filter { location ->
                        // 检查任何关键词的任何部分是否包含在位置标题或描述中
                        keywords.any { keyword ->
                            location.title.contains(keyword.take(2), ignoreCase = true) ||
                            keyword.length >= 2 && location.title.contains(keyword.take(keyword.length / 2), ignoreCase = true) ||
                            location.snippet.contains(keyword.take(2), ignoreCase = true)
                        }
                    }
                    
                    if (looseResults.isNotEmpty()) {
                        searchResults = looseResults
                        Log.d(TAG, "宽松匹配找到 ${looseResults.size} 个结果")
                    } else {
                        // 搜索结果为空
                        searchResults = emptyList()
                        Log.d(TAG, "未找到匹配的位置结果")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "搜索位置时出错: ${e.message}")
                e.printStackTrace()
            } finally {
                isSearching = false
            }
        } else {
            searchResults = emptyList()
        }
    }
    
    // 使用高德地图API进行搜索
    suspend fun searchWithAmapAPI(keyword: String) {
        if (keyword.isBlank()) return
        
        try {
            withContext(Dispatchers.IO) {
                // 1. 使用输入提示API获取搜索建议
                val tipResults = searchInputTips(context, keyword)
                Log.d(TAG, "高德地图输入提示API返回 ${tipResults.size} 条结果")
                
                // 2. 使用POI搜索API获取更多详细结果
                val poiResults = searchPOI(context, keyword)
                Log.d(TAG, "高德地图POI搜索API返回 ${poiResults.size} 条结果")
                
                // 3. 合并结果并去重
                val amapResults = mutableListOf<SearchResult>()
                
                // 添加输入提示结果
                amapResults.addAll(tipResults.filter { tip -> 
                    tip.point != null // 确保有位置信息
                }.map { tip ->
                    SearchResult(
                        title = tip.name ?: "",
                        snippet = tip.district ?: (tip.address ?: ""),
                        latLng = LatLng(tip.point.latitude, tip.point.longitude)
                    )
                })
                
                // 添加POI搜索结果
                amapResults.addAll(poiResults.map { poi ->
                    SearchResult(
                        title = poi.title ?: "",
                        snippet = poi.snippet ?: poi.adName ?: "",
                        latLng = LatLng(poi.latLonPoint.latitude, poi.latLonPoint.longitude)
                    )
                })
                
                // 去重 - 按标题去重
                val uniqueResults = amapResults.distinctBy { it.title }
                
                // 合并在线API结果和预设地点数据
                val combinedResults = mutableListOf<SearchResult>()
                
                // 检查本地数据库是否有精确匹配
                val exactLocalMatches = allLocations.filter { location ->
                    location.title.contains(keyword, ignoreCase = true) || 
                    location.snippet.contains(keyword, ignoreCase = true)
                }
                
                if (exactLocalMatches.isNotEmpty()) {
                    Log.d(TAG, "本地数据库中找到精确匹配: ${exactLocalMatches.size} 条")
                    combinedResults.addAll(exactLocalMatches)
                }
                
                // 添加API搜索结果
                if (uniqueResults.isNotEmpty()) {
                    combinedResults.addAll(uniqueResults)
                    Log.d(TAG, "合并后高德API结果: ${uniqueResults.size} 条")
                } 
                
                // 最终去重
                val finalResults = combinedResults.distinctBy { it.title }
                
                withContext(Dispatchers.Main) {
                    if (finalResults.isNotEmpty()) {
                        searchResults = finalResults
                        Log.d(TAG, "最终搜索结果: ${finalResults.size} 条")
                    } else {
                        // 如果没有找到结果，尝试使用本地数据进行模糊匹配
                        val fuzzyResults = allLocations.filter { location ->
                            location.title.contains(keyword.take(2), ignoreCase = true) ||
                            location.snippet.contains(keyword.take(2), ignoreCase = true)
                        }
                        
                        if (fuzzyResults.isNotEmpty()) {
                            searchResults = fuzzyResults
                            Log.d(TAG, "使用模糊匹配找到 ${fuzzyResults.size} 条结果")
                        } else {
                            searchResults = emptyList()
                            Log.d(TAG, "未找到任何匹配结果")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "使用高德地图API搜索时出错: ${e.message}")
            withContext(Dispatchers.Main) {
                // 出错时回退到本地搜索
                performLocalSearch(keyword)
            }
        }
    }
    
    // 添加到搜索历史
    fun addToHistory(result: SearchResult) {
        // 移除已有的相同项
        searchHistory.removeIf { it.title == result.title }
        
        // 添加到列表开头
        searchHistory.add(0, result)
        
        // 限制历史记录数量
        if (searchHistory.size > MAX_SEARCH_HISTORY) {
            searchHistory.removeAt(searchHistory.size - 1)
        }
    }
    
    // 处理位置选择
    fun handleLocationSelected(result: SearchResult) {
        Log.d(TAG, "选择位置: ${result.title}, 坐标: ${result.latLng}")
        
        // 添加到历史记录
        addToHistory(result)
        
        // 触发位置选择回调
        onLocationSelected(result.latLng)
        
        // 更新搜索框并退出搜索模式
        searchQuery = result.title
        isSearchMode = false
    }
    
    // 延迟搜索逻辑（输入过程中自动搜索）
    LaunchedEffect(searchQuery) {
        if (searchQuery.length >= 2) {
            delay(300) // 输入防抖
            isSearching = true
            
            // 优先使用高德API搜索
            try {
                searchWithAmapAPI(searchQuery)
            } catch (e: Exception) {
                Log.e(TAG, "高德API搜索失败，回退到本地搜索: ${e.message}")
                performLocalSearch(searchQuery)
            } finally {
                isSearching = false
            }
        } else if (searchQuery.isEmpty()) {
            searchResults = emptyList()
        }
    }
    
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.background,
        shadowElevation = if (isSearchMode) 4.dp else 1.dp
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 搜索框
                TextField(
                    value = searchQuery,
                    onValueChange = { 
                        searchQuery = it
                        if (!isSearchMode) {
                            isSearchMode = true
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    colors = TextFieldDefaults.textFieldColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        unfocusedIndicatorColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(24.dp),
                    placeholder = { Text("搜索城市、景点、大学、商场、村庄、乡镇等") },
                    leadingIcon = { 
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "搜索",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { 
                                searchQuery = "" 
                                if (isSearchMode) {
                                    isSearchMode = false
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "清除",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    singleLine = true
                )
                
                // 添加获取当前位置按钮
                IconButton(
                    onClick = onGetCurrentLocation,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "获取当前位置",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            
            // 搜索模式下显示结果或历史记录
            if (isSearchMode) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                ) {
                    if (isSearching) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(16.dp)
                                .align(Alignment.Center)
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            // 搜索结果
                            if (searchResults.isNotEmpty()) {
                                items(searchResults) { result ->
                                    SearchResultItem(
                                        result = result,
                                        onItemClick = { handleLocationSelected(result) }
                                    )
                                }
                            } 
                            // 搜索历史
                            else if (searchHistory.isNotEmpty() && searchQuery.isEmpty()) {
                                item {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "搜索历史",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        
                                        Spacer(modifier = Modifier.weight(1f))
                                        
                                        TextButton(onClick = { searchHistory.clear() }) {
                                            Text("清除")
                                        }
                                    }
                                }
                                
                                items(searchHistory) { result ->
                                    SearchHistoryItem(
                                        result = result,
                                        onItemClick = { handleLocationSelected(result) }
                                    )
                                }
                            }
                            // 无结果提示
                            else if (searchQuery.length >= 2) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text("未找到相关位置")
                                    }
                                }
                            }
                            // 热门推荐
                            else if (searchQuery.isEmpty()) {
                                item {
                                    Text(
                                        text = "热门城市",
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                    )
                                }
                                
                                // 显示几个热门城市
                                val hotCities = allLocations.take(10)
                                items(hotCities) { result ->
                                    SearchResultItem(
                                        result = result,
                                        onItemClick = { handleLocationSelected(result) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 搜索结果项
 */
@Composable
fun SearchResultItem(
    result: SearchResult,
    onItemClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 位置图标
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // 位置信息
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            
            Text(
                text = result.snippet,
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    fontSize = 13.sp
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    
    Divider(
        modifier = Modifier.padding(start = 56.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    )
}

/**
 * 搜索历史项
 */
@Composable
fun SearchHistoryItem(
    result: SearchResult,
    onItemClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onItemClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 历史图标 - 使用搜索图标代替
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(20.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        // 位置信息
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    
    Divider(
        modifier = Modifier.padding(start = 56.dp),
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
    )
}

/**
 * 搜索结果数据类
 */
data class SearchResult(
    val title: String,
    val snippet: String,
    val latLng: LatLng
)

/**
 * 使用高德地图输入提示API搜索
 */
private suspend fun searchInputTips(context: Context, keyword: String): List<Tip> = withContext(Dispatchers.IO) {
    try {
        val resultList = mutableListOf<Tip>()
        val query = InputtipsQuery(keyword, "")
        query.cityLimit = false // 不限制城市
        
        val inputTips = Inputtips(context, query)
        
        // 使用挂起函数等待结果
        val latch = java.util.concurrent.CountDownLatch(1)
        var tipResult: List<Tip> = emptyList()
        
        inputTips.setInputtipsListener { list, _ ->
            tipResult = list ?: emptyList()
            latch.countDown()
        }
        
        // 执行搜索
        inputTips.requestInputtipsAsyn()
        
        // 等待结果（最多3秒）
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        
        resultList.addAll(tipResult)
        Log.d(TAG, "输入提示API返回 ${resultList.size} 条结果")
        
        return@withContext resultList
    } catch (e: Exception) {
        Log.e(TAG, "输入提示API搜索出错: ${e.message}")
        return@withContext emptyList()
    }
}

/**
 * 使用高德地图POI搜索API搜索
 */
private suspend fun searchPOI(context: Context, keyword: String): List<PoiItem> = withContext(Dispatchers.IO) {
    try {
        val resultList = mutableListOf<PoiItem>()
        
        // 创建POI搜索查询
        val query = PoiSearch.Query(keyword, "", "") // 关键字，POI类型，城市
        query.pageSize = 20 // 设置每页最多返回多少条结果
        query.pageNum = 0 // 设置查询第几页，从0开始
        
        val poiSearch = PoiSearch(context, query)
        
        // 使用挂起函数等待结果
        val latch = java.util.concurrent.CountDownLatch(1)
        var poiResult: PoiResult? = null
        
        poiSearch.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
            override fun onPoiSearched(result: PoiResult?, errorCode: Int) {
                if (errorCode == AMapException.CODE_AMAP_SUCCESS) {
                    poiResult = result
                }
                latch.countDown()
            }
            
            override fun onPoiItemSearched(item: PoiItem?, errorCode: Int) {
                // 不需要处理单个POI搜索结果
                latch.countDown()
            }
        })
        
        // 执行搜索
        poiSearch.searchPOIAsyn()
        
        // 等待结果（最多3秒）
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        
        // 处理结果
        if (poiResult != null && poiResult!!.pois != null) {
            resultList.addAll(poiResult!!.pois)
            Log.d(TAG, "POI搜索API返回 ${resultList.size} 条结果")
        }
        
        return@withContext resultList
    } catch (e: Exception) {
        Log.e(TAG, "POI搜索API出错: ${e.message}")
        return@withContext emptyList()
    }
} 