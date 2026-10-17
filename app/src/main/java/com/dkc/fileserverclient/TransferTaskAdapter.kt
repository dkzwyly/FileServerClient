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
        /** 仅进度/总量变化的 payload 标记，走轻量绑定，不触发 change 动画 */
        const val PAYLOAD_PROGRESS = 1

        private val DIFF = object : DiffUtil.ItemCallback<TransferTask>() {
            override fun areItemsTheSame(o: TransferTask, n: TransferTask) = o.id == n.id

            /**
             * ★ 不比较 updatedAt：每次 updateTask 都会刷新它，
             *   原实现 o == n 会认为所有 item 内容都变了，导致全列表 change 动画 → 闪烁。
             */
            override fun areContentsTheSame(o: TransferTask, n: TransferTask): Boolean =
                o.fileName == n.fileName &&
                        o.type == n.type &&
                        o.state == n.state &&
                        o.transferredBytes == n.transferredBytes &&
                        o.totalBytes == n.totalBytes &&
                        o.errorMessage == n.errorMessage &&
                        o.retryCount == n.retryCount

            /**
             * areContentsTheSame 返回 false 后才会调用。
             * 仅「进度 / 总量」变化、其他字段全同时返回 PAYLOAD_PROGRESS，
             * AsyncListDiffer 会走 notifyItemChanged(pos, payload)，
             * RecyclerView 不触发 change 动画。
             */
            override fun getChangePayload(o: TransferTask, n: TransferTask): Any? {
                val onlyProgress =
                    o.fileName == n.fileName &&
                            o.type == n.type &&
                            o.state == n.state &&
                            o.errorMessage == n.errorMessage &&
                            o.retryCount == n.retryCount &&
                            (o.transferredBytes != n.transferredBytes || o.totalBytes != n.totalBytes)
                return if (onlyProgress) PAYLOAD_PROGRESS else null
            }
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
        VH(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_transfer_task, parent, false)
        )

    override fun onBindViewHolder(holder: VH, position: Int) {
        bindFull(holder, getItem(position))
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PROGRESS)) {
            bindProgressOnly(holder, getItem(position))
        } else {
            bindFull(holder, getItem(position))
        }
    }

    /**
     * 完整绑定：只在 item 首次出现、状态变化、错误信息变化时调用。
     */
    private fun bindFull(holder: VH, task: TransferTask) {
        holder.name.text = when (task.type) {
            TransferType.UPLOAD -> "上传: ${task.fileName}"
            TransferType.DOWNLOAD -> "下载: ${task.fileName}"
            TransferType.SERVER_MOVE -> "移动: ${task.fileName}"
            TransferType.SERVER_COPY -> "复制: ${task.fileName}"
        }

        // 状态文字：进度相关的部分交给 bindProgressOnly 统一处理
        holder.state.text = when (task.state) {
            TaskState.QUEUED -> "排队中"
            TaskState.RUNNING -> "${task.progress}%"
            TaskState.PAUSED -> task.errorMessage ?: "已暂停"
            TaskState.RETRYING -> task.errorMessage ?: "重试中"
            TaskState.COMPLETED -> "完成"
            TaskState.FAILED -> task.errorMessage ?: "失败"
            TaskState.CANCELLED -> "已取消"
        }

        val isServerTask = task.type == TransferType.SERVER_MOVE ||
                task.type == TransferType.SERVER_COPY

        holder.btnPause.visibility = if (isServerTask) View.GONE else View.VISIBLE
        holder.btnRemove.text = "移除"

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

        // 进度条与 RUNNING 百分比文字
        bindProgressOnly(holder, task)
    }

    /**
     * 仅进度绑定：payload 分支调用，只碰进度条和 RUNNING 状态的百分比文字。
     * 不触碰按钮、点击监听、名称等一次性内容，不触发 change 动画。
     */
    private fun bindProgressOnly(holder: VH, task: TransferTask) {
        holder.progress.progress = task.progress
        if (task.state == TaskState.RUNNING) {
            holder.state.text = "${task.progress}%"
        }
    }
}