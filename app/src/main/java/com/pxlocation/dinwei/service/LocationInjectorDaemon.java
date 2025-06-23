package com.pxlocation.dinwei.service;

import android.os.Process;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;

/**
 * 位置注入守护进程
 * 通过app_process方式启动，注入系统服务
 */
public class LocationInjectorDaemon {
    private static final String TAG = "LocationInjectorDaemon";
    private static final String LOCATION_SERVICE = "location";
    
    // 注入位置的系统文件路径
    private static final String[] LOCATION_FILES = {
            "/data/misc/location/gps.conf",
            "/data/misc/location/gps_debug.conf",
            "/data/misc/location/nmea.txt",
            "/data/misc/gps/gps.conf",
            "/data/misc/gps/nmea.txt",
            "/etc/gps.conf",
            "/system/etc/gps.conf"
    };
    
    // 当前注入的位置信息
    private static double currentLatitude = 0.0;
    private static double currentLongitude = 0.0;
    private static double currentAccuracy = 10.0;
    private static boolean isRunning = false;
    
    /**
     * 守护进程入口点
     */
    public static void main(String[] args) {
        try {
            Log.i(TAG, "LocationInjectorDaemon starting...");
            
            // 检查参数
            if (args.length < 1) {
                Log.e(TAG, "Missing package name argument");
                return;
            }
            
            String packageName = args[0];
            Log.i(TAG, "Package name: " + packageName);
            
            // 设置进程名称 - 使用反射调用setArgV0方法
            try {
                Method setArgV0Method = Process.class.getDeclaredMethod("setArgV0", String.class);
                setArgV0Method.invoke(null, "location_injector");
            } catch (Exception e) {
                Log.e(TAG, "Failed to set process name: " + e.getMessage());
            }
            
            // 获取系统服务管理器
            Object serviceManager = getSystemServiceManager();
            if (serviceManager == null) {
                Log.e(TAG, "Failed to get system service manager");
                return;
            }
            
            // 获取位置服务
            Object locationService = getSystemService(serviceManager, LOCATION_SERVICE);
            if (locationService == null) {
                Log.e(TAG, "Failed to get location service");
                return;
            }
            
            Log.i(TAG, "Successfully got location service: " + locationService);
            
            // 启动监听循环
            isRunning = true;
            startListeningLoop();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in daemon: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 开始监听循环，等待位置更新命令
     */
    private static void startListeningLoop() {
        Log.i(TAG, "Starting listening loop");
        
        // 创建命令监听文件
        File commandFile = new File("/data/local/tmp/location_command");
        
        try {
            while (isRunning) {
                // 检查命令文件是否存在
                if (commandFile.exists()) {
                    // 读取命令
                    String command = readCommandFile(commandFile);
                    processCommand(command);
                    
                    // 删除命令文件
                    commandFile.delete();
                }
                
                // 休眠一段时间
                Thread.sleep(1000);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in listening loop: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 读取命令文件
     */
    private static String readCommandFile(File file) {
        try {
            java.util.Scanner scanner = new java.util.Scanner(file);
            String content = scanner.useDelimiter("\\Z").next();
            scanner.close();
            return content;
        } catch (Exception e) {
            Log.e(TAG, "Error reading command file: " + e.getMessage());
            return "";
        }
    }
    
    /**
     * 处理命令
     */
    private static void processCommand(String command) {
        Log.i(TAG, "Processing command: " + command);
        
        try {
            String[] parts = command.split(",");
            if (parts.length >= 3 && parts[0].equals("LOCATION")) {
                // 解析位置信息
                double latitude = Double.parseDouble(parts[1]);
                double longitude = Double.parseDouble(parts[2]);
                double accuracy = parts.length > 3 ? Double.parseDouble(parts[3]) : 10.0;
                
                // 更新当前位置
                currentLatitude = latitude;
                currentLongitude = longitude;
                currentAccuracy = accuracy;
                
                // 注入位置
                injectLocation(latitude, longitude, accuracy);
                
                Log.i(TAG, "Location updated: " + latitude + ", " + longitude + ", " + accuracy);
            } else if (command.equals("STOP")) {
                // 停止守护进程
                isRunning = false;
                Log.i(TAG, "Received stop command, exiting...");
                System.exit(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error processing command: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 注入位置信息
     */
    private static void injectLocation(double latitude, double longitude, double accuracy) {
        try {
            // 注入位置到系统文件
            injectLocationToFiles(latitude, longitude, accuracy);
            
            // 发送位置更新广播
            sendLocationBroadcast(latitude, longitude, accuracy);
            
            // 调用系统服务设置位置
            setMockLocation(latitude, longitude, accuracy);
            
        } catch (Exception e) {
            Log.e(TAG, "Error injecting location: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 注入位置到系统文件
     */
    private static void injectLocationToFiles(double latitude, double longitude, double accuracy) {
        for (String filePath : LOCATION_FILES) {
            try {
                File file = new File(filePath);
                if (!file.exists()) {
                    File parent = file.getParentFile();
                    if (parent != null && !parent.exists()) {
                        parent.mkdirs();
                    }
                }
                
                // 写入位置数据
                BufferedWriter writer = new BufferedWriter(new FileWriter(file));
                writer.write("# Generated by LocationInjectorDaemon\n");
                writer.write("latitude=" + latitude + "\n");
                writer.write("longitude=" + longitude + "\n");
                writer.write("accuracy=" + accuracy + "\n");
                writer.write("provider=gps\n");
                writer.write("time=" + System.currentTimeMillis() + "\n");
                writer.close();
                
                Log.i(TAG, "Wrote location data to " + filePath);
                
                // 设置文件权限
                executeCommand("chmod 644 " + filePath);
                
            } catch (IOException e) {
                Log.e(TAG, "Error writing to " + filePath + ": " + e.getMessage());
            }
        }
    }
    
    /**
     * 发送位置更新广播
     */
    private static void sendLocationBroadcast(double latitude, double longitude, double accuracy) {
        try {
            // 构建广播命令
            String command = "am broadcast -a android.location.GPS_ENABLED_CHANGE --ez enabled true";
            executeCommand(command);
            
            command = "am broadcast -a android.location.GPS_FIX_CHANGE --ez enabled true";
            executeCommand(command);
            
            command = "am broadcast -a android.intent.action.PROVIDER_CHANGED " +
                      "--es provider gps " +
                      "--ez available true " +
                      "--ez valid true " +
                      "--ef latitude " + latitude + " " +
                      "--ef longitude " + longitude + " " +
                      "--ef accuracy " + accuracy;
            executeCommand(command);
            
        } catch (Exception e) {
            Log.e(TAG, "Error sending broadcast: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 通过系统服务设置模拟位置
     */
    private static void setMockLocation(double latitude, double longitude, double accuracy) {
        try {
            // 使用service call命令设置位置
            String serviceCall = "service call location 13 i32 0 f " + 
                                 Float.floatToIntBits((float)latitude) + " f " + 
                                 Float.floatToIntBits((float)longitude) + " f " + 
                                 Float.floatToIntBits((float)accuracy);
            executeCommand(serviceCall);
            
            // 尝试另一种service call格式（适用于某些设备）
            serviceCall = "service call location 16 i32 1 i32 0 f " + 
                          Float.floatToIntBits((float)latitude) + " f " + 
                          Float.floatToIntBits((float)longitude) + " f " + 
                          Float.floatToIntBits((float)accuracy);
            executeCommand(serviceCall);
            
        } catch (Exception e) {
            Log.e(TAG, "Error setting mock location: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 执行Shell命令
     */
    private static void executeCommand(String command) {
        try {
            Log.i(TAG, "Executing: " + command);
            java.lang.Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            int exitCode = process.waitFor();
            Log.i(TAG, "Command exit code: " + exitCode);
        } catch (Exception e) {
            Log.e(TAG, "Error executing command: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 获取系统服务管理器
     */
    private static Object getSystemServiceManager() {
        try {
            Class<?> serviceManagerClass = Class.forName("android.os.ServiceManager");
            Method getServiceMethod = serviceManagerClass.getDeclaredMethod("getService", String.class);
            return getServiceMethod;
        } catch (Exception e) {
            Log.e(TAG, "Error getting service manager: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
    
    /**
     * 获取系统服务
     */
    private static Object getSystemService(Object serviceManager, String serviceName) {
        try {
            if (serviceManager instanceof Method) {
                Method getServiceMethod = (Method) serviceManager;
                return getServiceMethod.invoke(null, serviceName);
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error getting service: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }
} 