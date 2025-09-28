package com.example.pixelflowplayer.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class WebSocketManager private constructor(val baseHost: String, val port: Int, val deviceId: String) {
    companion object {
        @Volatile private var INSTANCE: WebSocketManager? = null
        @Synchronized fun getInstance(baseHost: String, port: Int, deviceId: String): WebSocketManager {
            val cur = INSTANCE
            if (cur != null && cur.baseHost == baseHost && cur.port == port && cur.deviceId == deviceId) return cur
            try { cur?.disconnect() } catch (_: Exception) {}
            val inst = WebSocketManager(baseHost, port, deviceId)
            INSTANCE = inst
            return inst
        }
    }

    private val TAG = "WebSocketManager"
    private var ws: WebSocket? = null
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val gson = Gson()

    // Reliability
    private data class Pending(val id: String, val json: String)
    private val pending = mutableListOf<Pending>()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    private var reconnectRunnable: Runnable? = null

    // Deduplicate commands by id (bounded memory)
    private val handledIds = LinkedHashSet<String>()
    private fun markHandled(id: String): Boolean {
        if (id.isEmpty()) return true
        synchronized(handledIds) {
            if (handledIds.contains(id)) return false
            handledIds.add(id)
            // Trim to last 256 ids
            while (handledIds.size > 256) {
                val it = handledIds.iterator(); if (it.hasNext()) { it.next(); it.remove() } else break
            }
            return true
        }
    }

    // Optional register payload to send on open
    private var registerPayload: Map<String, Any?>? = null
    fun setRegisterPayload(payload: Map<String, Any?>) { registerPayload = payload }

    // command handler callback (single listener)
    private var commandHandler: ((id: String, command: String, params: Map<String, Any?>) -> Unit)? = null
    fun setCommandHandler(handlerFn: (id: String, command: String, params: Map<String, Any?>) -> Unit) { commandHandler = handlerFn }

    fun connect() {
        shouldReconnect = true
        try {
            // Avoid opening multiple sockets unnecessarily
            try { ws?.close(1000, "reconnecting") } catch (_: Exception) {}
            ws = null
            val url = "ws://$baseHost:$port/ws?role=device&deviceId=$deviceId"
            val req = Request.Builder().url(url).build()
            ws = client.newWebSocket(req, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                    Log.d(TAG, "WS connected")
                    reconnectAttempts = 0
                    // Send queued messages first-in-first-out
                    // Also send register on open if provided
                    registerPayload?.let { sendJson("register", it) }
                    flushQueue()
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val obj = JSONObject(text)
                        when (obj.optString("type")) {
                            "ack" -> {
                                val id = obj.optString("id", "")
                                if (id.isNotEmpty()) synchronized(pending) { pending.removeAll { it.id == id } }
                            }
                            "command" -> {
                                val id = obj.optString("id", UUID.randomUUID().toString())
                                // Deduplicate by id: if already seen, ignore completely
                                if (!markHandled(id)) { Log.d(TAG, "Duplicate command ignored: id=$id"); return }
                                val cmd = obj.optString("command", "")
                                val params = mutableMapOf<String, Any?>()
                                val p = obj.optJSONObject("params")
                                if (p != null) {
                                    val it = p.keys(); while (it.hasNext()) { val k = it.next(); params[k] = p.get(k) }
                                }
                                try { sendJson("ack", mapOf("of" to "command", "id" to id)) } catch (_: Exception) {}
                                // Log a single friendly line per command; downgrade noisy ones
                                if (cmd == "request_now_playing") {
                                    Log.d(TAG, "Command received: $cmd (id=$id)")
                                } else if (cmd == "set_volume") {
                                    val lvl = params["level"] ?: params["volume"]
                                    Log.i(TAG, "Command received: set_volume level=${lvl}")
                                } else {
                                    Log.i(TAG, "Command received: $cmd (id=$id)")
                                }
                                commandHandler?.invoke(id, cmd, params)
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "WS message: $text")
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { Log.d(TAG, "WS binary message ${bytes.size}") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { Log.e(TAG, "WS failure: ${t.message}"); ws = null; scheduleReconnect() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { Log.d(TAG, "WS closed: $code $reason"); ws = null; scheduleReconnect() }
            })
        } catch (e: Exception) {
            Log.e(TAG, "connect error", e); ws = null; scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val delay = (1000L shl reconnectAttempts).coerceAtMost(30_000L)
        reconnectAttempts = (reconnectAttempts + 1).coerceAtMost(5)
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = Runnable { connect() }
        handler.postDelayed(reconnectRunnable!!, delay)
        Log.d(TAG, "Reconnect scheduled in ${delay}ms")
    }

    fun disconnect() {
        shouldReconnect = false
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    private fun isOpen(): Boolean = try { ws != null && ws!!.send("") } catch (_: Exception) { false }

    private fun flushQueue() {
        val copy: List<Pending>; synchronized(pending) { copy = pending.toList() }
        copy.forEach { p -> try { ws?.send(p.json) } catch (_: Exception) {} }
    }

    fun sendJson(type: String, payload: Map<String, Any?> = emptyMap()) {
        val id = UUID.randomUUID().toString()
        val body = HashMap<String, Any?>(); body["type"] = type; body["id"] = id
        // Prevent payload from overriding reserved keys
        for ((k, v) in payload) if (k != "type" && k != "id") body[k] = v
        val json = gson.toJson(body)
        synchronized(pending) { pending.add(Pending(id, json)); if (pending.size > 200) pending.removeAt(0) }
        try { ws?.send(json) ?: run { scheduleReconnect() } } catch (_: Exception) { scheduleReconnect() }
    }
}
