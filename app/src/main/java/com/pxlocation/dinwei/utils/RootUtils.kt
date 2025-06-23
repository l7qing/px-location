package com.pxlocation.dinwei.utils

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader

/**
 * Root相关工具类
 */
object RootUtils {
    
    private const val TAG = "RootUtils"
    private var appContext: Context? = null
    
    init {
        // 配置libsu日志
        Shell.enableVerboseLogging = true
        // 注意：不再设置默认Builder，因为已经在PXLocationApp中设置
    }
    
    /**
     * 设置应用上下文
     */
    fun setAppContext(context: Context) {
        appContext = context.applicationContext
    }
    
    /**
     * 获取应用上下文
     */
    fun getAppContext(): Context {
        return appContext ?: throw IllegalStateException("Application context is not initialized. Call setAppContext first.")
    }
    
    /**
     * 获取当前应用的包名
     */
    fun getPackageName(): String {
        return appContext?.packageName ?: "com.pxlocation.dinwei"
    }
    
    /**
     * 检查设备是否已Root
     */
    fun isDeviceRooted(): Boolean {
        return try {
            Shell.rootAccess()
        } catch (e: Exception) {
            Log.e(TAG, "检查Root权限时出错: ${e.message}")
            false
        }
    }
    
    /**
     * 检查是否有Root权限
     */
    fun isRootAvailable(): Boolean {
        return try {
            val result = Shell.cmd("id").exec()
            val output = result.out.joinToString("\n")
            Log.d(TAG, "Root检查结果: $output")
            result.isSuccess && output.contains("uid=0")
        } catch (e: Exception) {
            Log.e(TAG, "检查Root可用性时出错: ${e.message}")
            false
        }
    }
    
    /**
     * 执行单个Root命令
     */
    fun executeSuCommand(command: String): String {
        return try {
            Log.d(TAG, "执行Root命令: $command")
            val result = Shell.su(command).exec()
            val output = result.out.joinToString("\n")
            
            if (!result.isSuccess) {
                val error = result.err.joinToString("\n")
                Log.e(TAG, "Root命令执行失败: $error")
                throw IOException("Root命令执行失败: $error")
            }
            
            output
        } catch (e: Exception) {
            Log.e(TAG, "执行Root命令时出错: ${e.message}")
            throw e
        }
    }
    
    /**
     * 执行多个Root命令
     */
    fun executeSuCommands(commands: Array<String>) {
        try {
            Log.d(TAG, "执行多个Root命令: ${commands.joinToString("; ")}")
            val result = Shell.su(*commands).exec()
            
            if (!result.isSuccess) {
                val error = result.err.joinToString("\n")
                Log.e(TAG, "Root命令执行失败: $error")
                throw IOException("Root命令执行失败: $error")
            }
            
            val output = result.out.joinToString("\n")
            if (output.isNotEmpty()) {
                Log.d(TAG, "命令输出: $output")
            }
        } catch (e: Exception) {
            Log.e(TAG, "执行Root命令时出错: ${e.message}")
            throw e
        }
    }
    
    /**
     * 异步检查Root权限
     */
    suspend fun checkRootAsync(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = Shell.cmd("id").exec()
            val hasRoot = result.isSuccess && result.out.any { it.contains("uid=0") }
            Log.d(TAG, "checkRootAsync: $hasRoot, output: ${result.out}")
            return@withContext hasRoot
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkRootAsync: ${e.message}")
            e.printStackTrace()
            return@withContext false
        }
    }
    
    /**
     * 请求Root权限
     */
    suspend fun requestRoot(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = Shell.su("echo 'Root access granted'").exec()
            val hasRoot = result.isSuccess
            Log.d(TAG, "requestRoot: $hasRoot, output: ${result.out}")
            return@withContext hasRoot
        } catch (e: Exception) {
            Log.e(TAG, "Error in requestRoot: ${e.message}")
            e.printStackTrace()
            return@withContext false
        }
    }
    
    /**
     * 执行Shell命令（不一定需要Root权限）
     * @param command 要执行的命令
     * @return 命令执行结果
     */
    fun executeCommand(command: String): String {
        try {
            val result = Shell.cmd(command).exec()
            Log.d(TAG, "executeCommand: $command, success: ${result.isSuccess}")
            return result.out.joinToString("\n")
        } catch (e: Exception) {
            Log.e(TAG, "Error executing command: $command, error: ${e.message}")
            e.printStackTrace()
            return "Error: ${e.message}"
        }
    }
    
    /**
     * 执行多个Shell命令并返回结果
     * @param commands 要执行的命令数组
     * @return 命令执行结果
     */
    fun executeCommands(commands: Array<String>): String {
        try {
            val commandString = commands.joinToString(" && ")
            Log.d(TAG, "执行命令序列: $commandString")
            val result = Shell.cmd(commandString).exec()
            val output = result.out.joinToString("\n")
            
            if (!result.isSuccess) {
                val error = result.err.joinToString("\n")
                Log.e(TAG, "命令执行失败: $error")
                return "Error: $error"
            }
            
            Log.d(TAG, "命令执行成功，输出: $output")
            return output
        } catch (e: Exception) {
            Log.e(TAG, "执行命令序列时出错: ${e.message}")
            e.printStackTrace()
            return "Error: ${e.message}"
        }
    }
    
    /**
     * 执行多个Shell命令并返回结果
     * @param commands 要执行的命令列表
     * @return 命令执行结果
     */
    fun executeCommands(commands: List<String>): String {
        return executeCommands(commands.toTypedArray())
    }
    
    /**
     * 执行需要Root权限的命令
     * @param command 要执行的命令
     * @return 命令执行结果
     */
    suspend fun execRootCommand(command: String): Shell.Result = withContext(Dispatchers.IO) {
        try {
            val result = Shell.su(command).exec()
            Log.d(TAG, "execRootCommand: $command, success: ${result.isSuccess}, output: ${result.out}")
            return@withContext result
        } catch (e: Exception) {
            Log.e(TAG, "Error executing root command: $command, error: ${e.message}")
            e.printStackTrace()
            throw e
        }
    }
    
    /**
     * 显示Root权限状态提示
     */
    fun showRootStatus(context: Context, isRooted: Boolean) {
        try {
            val message = if (isRooted) {
                "已获取Root权限"
            } else {
                "未获取Root权限，部分功能将无法使用"
            }
            Log.d(TAG, "Root status: $isRooted")
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing root status: ${e.message}")
            e.printStackTrace()
        }
    }
} 