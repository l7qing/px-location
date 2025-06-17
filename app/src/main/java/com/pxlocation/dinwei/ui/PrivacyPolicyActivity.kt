package com.pxlocation.dinwei.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pxlocation.dinwei.databinding.ActivityPrivacyPolicyBinding

class PrivacyPolicyActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityPrivacyPolicyBinding
    private lateinit var prefs: SharedPreferences
    
    companion object {
        private const val TAG = "PrivacyPolicyActivity"
        private const val PREF_NAME = "pxlocation_prefs"
        private const val PREF_PRIVACY_ACCEPTED = "privacy_policy_accepted"
        
        /**
         * 检查用户是否已接受隐私政策
         */
        fun isPrivacyPolicyAccepted(context: Context): Boolean {
            try {
                val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                val accepted = prefs.getBoolean(PREF_PRIVACY_ACCEPTED, false)
                Log.d(TAG, "isPrivacyPolicyAccepted: $accepted")
                return accepted
            } catch (e: Exception) {
                Log.e(TAG, "Error checking privacy policy acceptance: ${e.message}")
                e.printStackTrace()
                return false
            }
        }
        
        /**
         * 启动隐私政策页面
         */
        fun start(context: Context) {
            try {
                Log.d(TAG, "Starting PrivacyPolicyActivity")
                val intent = Intent(context, PrivacyPolicyActivity::class.java)
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting PrivacyPolicyActivity: ${e.message}")
                e.printStackTrace()
                Toast.makeText(context, "无法打开隐私政策页面: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        try {
            binding = ActivityPrivacyPolicyBinding.inflate(layoutInflater)
            setContentView(binding.root)
            
            prefs = getSharedPreferences(PREF_NAME, MODE_PRIVATE)
            
            setSupportActionBar(binding.toolbar)
            supportActionBar?.setDisplayHomeAsUpEnabled(true)
            
            // 设置接受按钮点击事件
            binding.btnAccept.setOnClickListener {
                try {
                    // 保存隐私政策已接受
                    prefs.edit().putBoolean(PREF_PRIVACY_ACCEPTED, true).apply()
                    Log.d(TAG, "Privacy policy accepted")
                    
                    // 重新启动 MainActivity
                    val intent = Intent(this, com.pxlocation.dinwei.MainActivity::class.java)
                    intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
                    startActivity(intent)
                    finish()
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving privacy policy acceptance: ${e.message}")
                    e.printStackTrace()
                    Toast.makeText(this, "保存隐私政策接受状态失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in onCreate: ${e.message}")
            e.printStackTrace()
            Toast.makeText(this, "初始化隐私政策页面失败: ${e.message}", Toast.LENGTH_LONG).show()
            finish()
        }
    }
    
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        try {
            if (item.itemId == android.R.id.home) {
                onBackPressed()
                return true
            }
            return super.onOptionsItemSelected(item)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onOptionsItemSelected: ${e.message}")
            e.printStackTrace()
            return super.onOptionsItemSelected(item)
        }
    }
} 