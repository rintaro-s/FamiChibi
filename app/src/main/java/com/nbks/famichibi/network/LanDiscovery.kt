package com.nbks.famichibi.network

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.net.*

data class DiscoveredServer(
    val host: String,
    val port: Int,
    val name: String,
    val version: String
)

class LanDiscovery {
    companion object {
        private const val TAG = "com.nbks.famichibi"
        private const val DISCOVER_PORT = 8001
        private const val DISCOVER_TIMEOUT = 3000
        private const val BROADCAST_PACKET = "FamiChibi-discover"
    }

    private val _servers = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = _servers

    private var discoveryJob: Job? = null

    fun startDiscovery(scope: CoroutineScope) {
        discoveryJob?.cancel()
        discoveryJob = scope.launch(Dispatchers.IO) {
            _servers.value = emptyList()
            val found = mutableSetOf<String>()
            try {
                val socket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = DISCOVER_TIMEOUT
                }
                val packetData = BROADCAST_PACKET.toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(packetData, packetData.size, broadcastAddr, DISCOVER_PORT)

                socket.send(packet)

                val buffer = ByteArray(4096)
                val deadline = System.currentTimeMillis() + DISCOVER_TIMEOUT
                while (System.currentTimeMillis() < deadline && isActive) {
                    try {
                        val recvPacket = DatagramPacket(buffer, buffer.size)
                        socket.receive(recvPacket)
                        val response = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                        val host = recvPacket.address.hostAddress ?: continue
                        val key = "$host:$response"
                        if (key in found) continue
                        found.add(key)

                        // Try HTTP GET /discover to confirm
                        val server = try {
                            val url = URL("http://$host:8000/discover")
                            val conn = url.openConnection() as HttpURLConnection
                            conn.connectTimeout = 2000
                            conn.readTimeout = 2000
                            conn.requestMethod = "GET"
                            val code = conn.responseCode
                            if (code == 200) {
                                val body = conn.inputStream.bufferedReader().use { it.readText() }
                                val json = JSONObject(body)
                                DiscoveredServer(
                                    host = host,
                                    port = json.optInt("port", 8000),
                                    name = json.optString("name", "Unknown"),
                                    version = json.optString("version", "")
                                )
                            } else null
                        } catch (_: Exception) { null }

                        if (server != null) {
                            _servers.value = _servers.value + server
                        }
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                }
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Discovery error", e)
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
    }
}
