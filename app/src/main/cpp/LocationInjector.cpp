#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <android/log.h>
#include <dlfcn.h>
#include <sys/system_properties.h>

#define TAG "LocationInjector"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 系统属性名称
const char* LOCATION_PROVIDERS_ALLOWED = "persist.sys.mock_location";
const char* LOCATION_GPS_ENABLED = "persist.sys.gps.enabled";

// 系统服务相关路径
const char* LOCATION_SERVICE_SOCKET = "/dev/socket/location";
const char* LOCATION_DATA_PATH = "/data/misc/location";
const char* NMEA_FILE_PATH = "/data/misc/location/nmea.txt";

// 注入位置的守护进程名称
const char* INJECTOR_DAEMON_NAME = "location_injector";

// 函数声明
void generateNmeaData(double latitude, double longitude, char* buffer, size_t bufferSize);

// 获取系统属性
static std::string get_system_property(const char* key) {
    char value[PROP_VALUE_MAX] = {0};
    __system_property_get(key, value);
    return std::string(value);
}

// 设置系统属性
static int set_system_property(const char* key, const char* value) {
    return __system_property_set(key, value);
}

// 启动位置注入守护进程
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pxlocation_dinwei_service_LocationInjectorService_startInjectorDaemon(
        JNIEnv* env, jobject /* this */, jstring daemonPath, jstring packageName) {
    
    const char* daemon_path = env->GetStringUTFChars(daemonPath, nullptr);
    const char* package_name = env->GetStringUTFChars(packageName, nullptr);
    
    LOGI("Starting location injector daemon: %s", daemon_path);
    
    // 检查文件是否存在
    struct stat st;
    if (stat(daemon_path, &st) != 0) {
        LOGE("Daemon file not found: %s", daemon_path);
        env->ReleaseStringUTFChars(daemonPath, daemon_path);
        env->ReleaseStringUTFChars(packageName, package_name);
        return JNI_FALSE;
    }
    
    // 设置可执行权限
    if (chmod(daemon_path, 0755) != 0) {
        LOGE("Failed to set executable permission for daemon");
        env->ReleaseStringUTFChars(daemonPath, daemon_path);
        env->ReleaseStringUTFChars(packageName, package_name);
        return JNI_FALSE;
    }
    
    // 构建命令行
    char command[512];
    snprintf(command, sizeof(command),
             "su -c \"CLASSPATH=/data/app/%s-*/base.apk "
             "/system/bin/app_process /system/bin %s %s\"",
             package_name, INJECTOR_DAEMON_NAME, package_name);
    
    LOGI("Executing command: %s", command);
    
    // 执行命令
    int result = system(command);
    if (result != 0) {
        LOGE("Failed to start daemon, result: %d", result);
        env->ReleaseStringUTFChars(daemonPath, daemon_path);
        env->ReleaseStringUTFChars(packageName, package_name);
        return JNI_FALSE;
    }
    
    LOGI("Daemon started successfully");
    env->ReleaseStringUTFChars(daemonPath, daemon_path);
    env->ReleaseStringUTFChars(packageName, package_name);
    return JNI_TRUE;
}

// 注入位置信息到系统
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pxlocation_dinwei_service_LocationInjectorService_injectLocation(
        JNIEnv* env, jobject /* this */, jdouble latitude, jdouble longitude, jdouble accuracy) {
    
    LOGI("Injecting location: lat=%f, lng=%f, acc=%f", latitude, longitude, accuracy);
    
    // 尝试通过系统属性方式注入
    char location_value[128];
    snprintf(location_value, sizeof(location_value), "%f,%f,%f", latitude, longitude, accuracy);
    
    if (set_system_property("persist.sys.mock_location", "1") != 0) {
        LOGE("Failed to enable mock location");
    }
    
    if (set_system_property("persist.sys.mock.location", location_value) != 0) {
        LOGE("Failed to set mock location value");
    }
    
    // 尝试写入位置文件
    char location_data[256];
    snprintf(location_data, sizeof(location_data),
             "latitude=%f\nlongitude=%f\naccuracy=%f\nprovider=gps\ntime=%ld\n",
             latitude, longitude, accuracy, time(nullptr));
    
    int fd = open("/data/misc/location/gps.conf", O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        write(fd, location_data, strlen(location_data));
        close(fd);
        LOGI("Location data written to gps.conf");
    } else {
        LOGE("Failed to write to gps.conf: %d", errno);
    }
    
    // 生成NMEA数据并写入
    char nmea[512];
    generateNmeaData(latitude, longitude, nmea, sizeof(nmea));
    
    fd = open(NMEA_FILE_PATH, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        write(fd, nmea, strlen(nmea));
        close(fd);
        LOGI("NMEA data written to file");
    } else {
        LOGE("Failed to write NMEA data: %d", errno);
    }
    
    return JNI_TRUE;
}

// 生成NMEA格式的GPS数据
void generateNmeaData(double latitude, double longitude, char* buffer, size_t bufferSize) {
    time_t now;
    struct tm* timeinfo;
    char timeStr[7];
    
    time(&now);
    timeinfo = gmtime(&now);
    sprintf(timeStr, "%02d%02d%02d", timeinfo->tm_hour, timeinfo->tm_min, timeinfo->tm_sec);
    
    // 转换为度分格式
    double latDegrees = (int)latitude;
    double latMinutes = (latitude - latDegrees) * 60.0;
    char latDirection = (latitude >= 0) ? 'N' : 'S';
    if (latitude < 0) {
        latDegrees = -latDegrees;
        latMinutes = -latMinutes;
    }
    
    double lonDegrees = (int)longitude;
    double lonMinutes = (longitude - lonDegrees) * 60.0;
    char lonDirection = (longitude >= 0) ? 'E' : 'W';
    if (longitude < 0) {
        lonDegrees = -lonDegrees;
        lonMinutes = -lonMinutes;
    }
    
    // 生成GPGGA语句
    char sentence[256];
    sprintf(sentence, "GPGGA,%s,%02.0f%09.6f,%c,%03.0f%09.6f,%c,1,08,1.0,0.0,M,0.0,M,,",
            timeStr,
            latDegrees, latMinutes, latDirection,
            lonDegrees, lonMinutes, lonDirection);
    
    // 计算校验和
    unsigned char checksum = 0;
    for (int i = 0; sentence[i]; i++) {
        checksum ^= sentence[i];
    }
    
    // 组装完整的NMEA语句
    snprintf(buffer, bufferSize, "$%s*%02X\r\n", sentence, checksum);
}

// 检查注入是否成功
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pxlocation_dinwei_service_LocationInjectorService_checkInjectionSuccess(
        JNIEnv* env, jobject /* this */, jdouble latitude, jdouble longitude) {
    
    // 检查系统属性
    std::string mockEnabled = get_system_property(LOCATION_PROVIDERS_ALLOWED);
    if (mockEnabled.empty() || mockEnabled == "0") {
        LOGE("Mock location is not enabled in system properties");
        return JNI_FALSE;
    }
    
    // 尝试读取位置文件
    char buffer[1024] = {0};
    int fd = open("/data/misc/location/gps.conf", O_RDONLY);
    if (fd >= 0) {
        read(fd, buffer, sizeof(buffer) - 1);
        close(fd);
        
        // 简单检查文件内容是否包含我们设置的坐标
        char lat_str[32], lng_str[32];
        snprintf(lat_str, sizeof(lat_str), "latitude=%f", latitude);
        snprintf(lng_str, sizeof(lng_str), "longitude=%f", longitude);
        
        if (strstr(buffer, lat_str) && strstr(buffer, lng_str)) {
            LOGI("Injection verified via gps.conf file");
            return JNI_TRUE;
        }
    }
    
    LOGI("Could not verify injection success");
    return JNI_FALSE;
}

// 停止位置注入
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pxlocation_dinwei_service_LocationInjectorService_stopInjection(
        JNIEnv* env, jobject /* this */) {
    
    LOGI("Stopping location injection");
    
    // 重置系统属性
    set_system_property(LOCATION_PROVIDERS_ALLOWED, "0");
    
    // 尝试杀死守护进程
    system("su -c \"pkill -f location_injector\"");
    
    return JNI_TRUE;
} 