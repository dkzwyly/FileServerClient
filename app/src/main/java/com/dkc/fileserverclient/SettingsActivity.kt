package com.dkc.fileserverclient

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dkc.fileserverclient.backup.BackupManager
import com.google.android.gms.oss.licenses.OssLicensesMenuActivity
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var recyclerViewSettings: RecyclerView
    private lateinit var settingsAdapter: SettingsAdapter

    // 导出：用户选择保存 zip 的位置
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = BackupManager.exportAll(this@SettingsActivity, uri)
            Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
        }
    }

    // 恢复：用户选择 zip
    private val restoreLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val result = BackupManager.restoreAll(this@SettingsActivity, uri)
            Toast.makeText(this@SettingsActivity, result.message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        // 启用返回按钮
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.title = "设置"

        recyclerViewSettings = findViewById(R.id.recyclerViewSettings)

        setupSettingsList()
    }

    private fun setupSettingsList() {
        val settingsList = listOf(
            SettingsItem("备份全部数据", "一键导出所有进度与设置为 zip") {
                exportAllData()
            },
            SettingsItem("恢复全部数据", "从 zip 恢复所有进度与设置") {
                restoreAllData()
            },
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

    // ==================== 备份 / 恢复 ====================

    private fun exportAllData() {
        val fileName = "fileserver_backup_${System.currentTimeMillis()}.zip"
        exportLauncher.launch(fileName)
    }

    private fun restoreAllData() {
        AlertDialog.Builder(this)
            .setTitle("恢复全部数据")
            .setMessage("恢复操作会覆盖当前所有进度与设置，且不可撤销。\n确定继续吗？")
            .setPositiveButton("继续") { _, _ ->
                restoreLauncher.launch(
                    arrayOf("application/zip", "application/octet-stream", "*/*")
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 其他原有入口 ====================

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