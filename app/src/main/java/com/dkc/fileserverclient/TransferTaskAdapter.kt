package com.dkc.fileserverclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.dkc.fileserverclient.transfer.TaskState
import com.dkc.fileserverclient.transfer.TransferManager
import com.dkc.fileserverclient.transfer.TransferTask
import com.dkc.fileserverclient.transfer.TransferType

class TransferTaskAdapter :
    ListAdapter<TransferTask, TransferTaskAdapter.VH>(DIFF) {

    companion object {
        private val DIFF = object : DiffUtil.ItemCallback<TransferTask>() {
            override fun areItemsTheSame(o: TransferTask, n: TransferTask) = o.id == n.id
            override fun areContentsTheSame(o: TransferTask, n: TransferTask) = o == n
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.taskName)
        val progress: ProgressBar = v.findViewById(R.id.taskProgress)
        val state: TextView = v.findViewById(R.id.taskState)
        val btnPause: Button = v.findViewById(R.id.btnPause)
        val btnRemove: Button = v.findViewById(R.id.btnRemove)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context)
            .inflate(R.layout.item_transfer_task, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        val task = getItem(position)

        holder.name.text = task.fileName
        holder.progress.progress = task.progress

        holder.state.text = when (task.state) {
            TaskState.QUEUED -> "排队中"
            TaskState.RUNNING -> "${task.progress}%"
            TaskState.PAUSED -> task.errorMessage ?: "已暂停"
            TaskState.RETRYING -> task.errorMessage ?: "重试中"
            TaskState.COMPLETED -> "完成"
            TaskState.FAILED -> task.errorMessage ?: "失败"
            TaskState.CANCELLED -> "已取消"
        }
        val isServerTask = task.type == TransferType.SERVER_MOVE || task.type == TransferType.SERVER_COPY

        holder.btnPause.visibility = if (isServerTask) View.GONE else View.VISIBLE

        if (isServerTask) {
            // 服务端任务不允许重试（服务端自己会重试）
            holder.btnRemove.text = "移除"
        } else {
            holder.btnRemove.text = "移除"
        }
        holder.name.text = when (task.type) {
            TransferType.UPLOAD -> "上传: ${task.fileName}"
            TransferType.DOWNLOAD -> "下载: ${task.fileName}"
            TransferType.SERVER_MOVE -> "移动: ${task.fileName}"
            TransferType.SERVER_COPY -> "复制: ${task.fileName}"
        }
        holder.btnPause.text = when (task.state) {
            TaskState.RUNNING, TaskState.QUEUED -> "暂停"
            TaskState.PAUSED, TaskState.FAILED -> "重试"
            else -> "—"
        }
        holder.btnPause.isEnabled = task.state in setOf(
            TaskState.RUNNING, TaskState.QUEUED, TaskState.PAUSED, TaskState.FAILED
        )
        holder.btnPause.setOnClickListener {
            when (task.state) {
                TaskState.RUNNING, TaskState.QUEUED -> TransferManager.pauseTask(task.id)
                TaskState.PAUSED, TaskState.FAILED -> TransferManager.resumeTask(task.id)
                else -> {}
            }
        }

        holder.btnRemove.setOnClickListener { TransferManager.removeTask(task.id) }
    }
}