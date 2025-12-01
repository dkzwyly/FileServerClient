package com.dkc.fileserverclient

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var recyclerViewSettings: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 启用返回按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "设置"

        // 使用 findViewById 初始化视图
        recyclerViewSettings = findViewById(R.id.recyclerViewSettings)

        setupSettingsList()
    }

    private fun setupSettingsList() {
        val settingsList = listOf(
            SettingsItem("关于", "查看应用信息") { showAboutDialog() },
            SettingsItem("开源许可证", "查看使用的开源库") { showOpenSourceLicenses() },
            SettingsItem("清除历史记录", "清除连接历史") { clearHistory() },
            SettingsItem("检查更新", "检查最新版本") { checkForUpdates() },
            SettingsItem("隐私政策", "查看隐私政策") { showPrivacyPolicy() },
            SettingsItem("使用帮助", "查看使用说明") { showHelp() }
        )

        settingsAdapter = SettingsAdapter(settingsList)

        recyclerViewSettings.layoutManager = LinearLayoutManager(this)
        recyclerViewSettings.adapter = settingsAdapter

        val divider = DividerItemDecoration(this, LinearLayoutManager.VERTICAL)
        recyclerViewSettings.addItemDecoration(divider)
    }

    private fun showAboutDialog() {
        Toast.makeText(this, "文件服务器客户端 v1.0\n版权所有 © 2024", Toast.LENGTH_LONG).show()
    }

    private fun showOpenSourceLicenses() {
        val intent = Intent(this, OssLicensesMenuActivity::class.java)
        intent.putExtra("title", "开源许可证")
        startActivity(intent)
    }

    private fun clearHistory() {
        val prefs = getSharedPreferences("FileServerPrefs", MODE_PRIVATE)
        prefs.edit().remove("connection_history").apply()
        Toast.makeText(this, "历史记录已清除", Toast.LENGTH_SHORT).show()
    }

    private fun checkForUpdates() {
        Toast.makeText(this, "当前已是最新版本", Toast.LENGTH_SHORT).show()
    }

    private fun showPrivacyPolicy() {
        Toast.makeText(this, "隐私政策页面", Toast.LENGTH_SHORT).show()
    }

    private fun showHelp() {
        Toast.makeText(this, "使用帮助页面", Toast.LENGTH_SHORT).show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    data class SettingsItem(
        val title: String,
        val description: String,
        val onClick: () -> Unit
    )
}
