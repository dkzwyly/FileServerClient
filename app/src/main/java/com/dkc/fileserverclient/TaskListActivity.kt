package com.dkc.fileserverclient

import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.dkc.fileserverclient.transfer.TransferManager
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

class TaskListActivity : AppCompatActivity() {

    private val adapter = TransferTaskAdapter()
    private lateinit var taskSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_task_list)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        taskSummary = findViewById(R.id.taskSummary)

        val rv = findViewById<RecyclerView>(R.id.taskRecyclerView)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        // ★ 关键：禁用 item 变化的淡出淡入动画
        //   进度以 100~250ms 频率刷新，每次 notifyItemChanged 都做 change 动画会闪
        //   带 payload 的 notifyItemChanged 不走动画，见 TransferTaskAdapter.getChangePayload
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        findViewById<Button>(R.id.btnClearFinished).setOnClickListener {
            TransferManager.clearFinished()
        }

        lifecycleScope.launch {
            TransferManager.tasks.collect { tasks ->
                adapter.submitList(tasks)
                val active = tasks.count { it.isActive }
                val total = tasks.size
                val newText = when {
                    total == 0 -> "暂无任务"
                    active > 0 -> "$active 个进行中 · 共 $total 个"
                    else -> "共 $total 个任务（无进行中）"
                }
                // 仅在文字真正变化时才 setText，避免无谓的 measure/layout
                if (taskSummary.text.toString() != newText) {
                    taskSummary.text = newText
                }
            }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}