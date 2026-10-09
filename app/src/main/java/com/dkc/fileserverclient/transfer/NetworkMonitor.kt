package com.dkc.fileserverclient.transfer

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

object NetworkMonitor {

    fun observe(context: Context): Flow<Boolean> = callbackFlow {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        fun connected(): Boolean {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        trySend(connected())

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(connected()) }
            override fun onLost(network: Network) { trySend(connected()) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                trySend(connected())
            }
        }

        // registerDefaultNetworkCallback 是 API 24+，改用兼容 API 21+ 的 registerNetworkCallback。
        // 注册"有 INTERNET 能力"的网络；具体是否已连接由 connected() 通过 activeNetwork 判断。
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            cm.registerNetworkCallback(request, callback)
        } catch (e: Exception) {
            // 注册失败（极少见），保守认为无网络
            trySend(false)
        }

        awaitClose {
            try { cm.unregisterNetworkCallback(callback) } catch (_: Exception) {}
        }
    }.distinctUntilChanged()
}