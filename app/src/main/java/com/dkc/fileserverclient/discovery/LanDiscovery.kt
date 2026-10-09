package com.dkc.fileserverclient.discovery

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

data class DiscoveredServer(
    val name: String,
    val httpPort: Int,
    val httpsPort: Int,
    val host: String,
    val version: String
) {
    fun toUrl(useHttps: Boolean): String {
        val port = if (useHttps) httpsPort else httpPort
        val scheme = if (useHttps) "https" else "http"
        return "$scheme://$host:$port"
    }

    // ===== 修改：优先 https 端口，没有就退回 http =====
    fun toEditableAddress(): String {
        return if (httpsPort > 0) "$host:$httpsPort" else "$host:$httpPort"
    }
}

object LanDiscovery {

    private const val TAG = "LanDiscovery"
    private const val PORT = 38080
    private const val REQUEST = "FILESERVER_DISCOVER"
    private const val TIMEOUT_MS = 2000L

    suspend fun discover(): List<DiscoveredServer> = withContext(Dispatchers.IO) {
        val results = LinkedHashMap<String, DiscoveredServer>()

        val broadcastAddresses = collectBroadcastAddresses()
        if (broadcastAddresses.isEmpty()) {
            Log.w(TAG, "没有可用的广播地址")
            return@withContext emptyList()
        }
        Log.d(TAG, "广播地址: $broadcastAddresses")

        val socket = try {
            DatagramSocket().apply {
                broadcast = true
                soTimeout = 300
            }
        } catch (e: Exception) {
            Log.e(TAG, "创建 socket 失败", e)
            return@withContext emptyList()
        }

        try {
            val payload = REQUEST.toByteArray(Charsets.UTF_8)
            for (addr in broadcastAddresses) {
                try {
                    socket.send(DatagramPacket(payload, payload.size, addr, PORT))
                    Log.d(TAG, "已发送到 $addr:$PORT")
                } catch (e: Exception) {
                    Log.w(TAG, "发送到 $addr 失败: ${e.message}")
                }
            }

            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            val buffer = ByteArray(4096)
            while (System.currentTimeMillis() < deadline) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val json = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    val server = parseReply(json, packet.address.hostAddress) ?: continue
                    val key = "${server.host}:${server.httpPort}"
                    if (!results.containsKey(key)) {
                        results[key] = server
                        Log.d(TAG, "发现: ${server.name} @ ${server.host}")
                    }
                } catch (e: java.net.SocketTimeoutException) {
                    // 单次超时，继续等总超时
                } catch (e: Exception) {
                    Log.w(TAG, "接收异常: ${e.message}")
                }
            }
        } finally {
            socket.close()
        }

        results.values.toList()
    }

    private fun collectBroadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return result
            for (iface in Collections.list(ifaces)) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val address = ia.address
                    if (address !is Inet4Address) continue

                    // 优先用系统给的广播地址
                    val bcast = ia.broadcast
                    if (bcast != null && bcast is Inet4Address) {
                        result.add(bcast)
                    } else {
                        // 部分 ROM 不给 broadcast，用 IP + 前缀自己算
                        val computed = computeBroadcast(address, ia.networkPrefixLength.toInt())
                        if (computed != null) result.add(computed)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "枚举网卡失败", e)
        }
        return result.distinctBy { it.hostAddress }
    }

    private fun computeBroadcast(ip: InetAddress, prefixLength: Int): InetAddress? {
        if (prefixLength < 1 || prefixLength > 32) return null
        val ipBytes = ip.address
        if (ipBytes.size != 4) return null
        val mask = IntArray(4)
        for (i in 0 until 4) {
            val bits = when {
                prefixLength >= (i + 1) * 8 -> 8
                prefixLength <= i * 8 -> 0
                else -> prefixLength - i * 8
            }
            mask[i] = (0xFF shl (8 - bits)) and 0xFF
        }
        val bcast = ByteArray(4)
        for (i in 0 until 4) {
            val ipVal = ipBytes[i].toInt() and 0xFF
            val maskVal = mask[i]
            bcast[i] = ((ipVal and maskVal) or (maskVal.inv() and 0xFF)).toByte()
        }
        return try {
            InetAddress.getByAddress(bcast)
        } catch (e: Exception) {
            null
        }
    }

    private fun parseReply(json: String, sourceIp: String): DiscoveredServer? {
        return try {
            val obj = JSONObject(json)
            if (obj.optString("type") != "FILESERVER") return null
            DiscoveredServer(
                name = obj.optString("name", "未知服务器"),
                httpPort = obj.optInt("http", 8080),
                httpsPort = obj.optInt("https", 8081),
                host = sourceIp,
                version = obj.optString("version", "")
            )
        } catch (e: Exception) {
            Log.w(TAG, "解析失败: $json")
            null
        }
    }
}