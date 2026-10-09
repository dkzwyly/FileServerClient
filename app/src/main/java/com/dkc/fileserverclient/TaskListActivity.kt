package com.dkc.fileserverclient

import android.os.Bundle
import android.view.MenuItem
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
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

        findViewById<Button>(R.id.btnClearFinished).setOnClickListener {
            TransferManager.clearFinished()
        }

        lifecycleScope.launch {
            TransferManager.tasks.collect { tasks ->
                adapter.submitList(tasks)
                val active = tasks.count { it.isActive }
                val total = tasks.size
                taskSummary.text = when {
                    total == 0 -> "暂无任务"
                    active > 0 -> "$active 个进行中 · 共 $total 个"
                    else -> "共 $total 个任务（无进行中）"
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