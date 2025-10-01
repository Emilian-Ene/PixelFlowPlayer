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
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    // Reliability
    private data class Pending(val id: String, val json: String)
    private val pending = mutableListOf<Pending>()
    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var shouldReconnect = true
    private var reconnectRunnable: Runnable? = null

    // Watchdog: reconnect if no activity
    private var lastActivityMs: Long = System.currentTimeMillis()
    private var watchdogRunnable: Runnable? = null
    private var watchdogTimeoutMs: Long = 60_000L
    private var watchdogIntervalMs: Long = 30_000L

    fun configureWatchdog(timeoutMs: Long = 60_000L, intervalMs: Long = 30_000L) {
        watchdogTimeoutMs = timeoutMs
        watchdogIntervalMs = intervalMs
    }

    private fun startWatchdog() {
        stopWatchdog()
        watchdogRunnable = object : Runnable {
            override fun run() {
                val idle = System.currentTimeMillis() - lastActivityMs
                if (idle > watchdogTimeoutMs) {
                    Log.w(TAG, "Watchdog: no WS activity for ${idle}ms, forcing reconnect()")
                    try { connect() } catch (_: Exception) {}
                    lastActivityMs = System.currentTimeMillis()
                }
                handler.postDelayed(this, watchdogIntervalMs)
            }
        }
        handler.postDelayed(watchdogRunnable!!, watchdogIntervalMs)
        Log.d(TAG, "Watchdog started (timeout=${watchdogTimeoutMs} interval=${watchdogIntervalMs})")
    }
    private fun stopWatchdog() {
        watchdogRunnable?.let { handler.removeCallbacks(it) }
        watchdogRunnable = null
    }

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
    private val pendingIncoming = mutableListOf<Triple<String, String, Map<String, Any?>>>()
    fun setCommandHandler(handlerFn: (id: String, command: String, params: Map<String, Any?>) -> Unit) {
        commandHandler = handlerFn
        // Flush any queued commands that arrived before the handler was set
        val toDeliver: List<Triple<String, String, Map<String, Any?>>> = synchronized(pendingIncoming) {
            val copy = pendingIncoming.toList()
            pendingIncoming.clear()
            copy
        }
        if (toDeliver.isNotEmpty()) {
            Log.i(TAG, "Delivering ${toDeliver.size} queued command(s) after handler set")
            for ((id, cmd, params) in toDeliver) {
                try { commandHandler?.invoke(id, cmd, params) } catch (e: Exception) { Log.e(TAG, "Handler error", e) }
            }
        }
    }

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
                    lastActivityMs = System.currentTimeMillis()
                    reconnectAttempts = 0
                    // Send queued messages first-in-first-out
                    // Also send register on open if provided
                    registerPayload?.let { sendJson("register", it) }
                    flushQueue()
                    startWatchdog()
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    lastActivityMs = System.currentTimeMillis()
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
                                val h = commandHandler
                                if (h != null) {
                                    try { h.invoke(id, cmd, params) } catch (e: Exception) { Log.e(TAG, "Handler error", e) }
                                } else {
                                    // Queue until the Activity sets a handler
                                    synchronized(pendingIncoming) { pendingIncoming.add(Triple(id, cmd, params)) }
                                    Log.i(TAG, "Queued command id=$id cmd=$cmd (no handler yet)")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.d(TAG, "WS message: $text")
                    }
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) { lastActivityMs = System.currentTimeMillis(); Log.d(TAG, "WS binary message ${bytes.size}") }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) { Log.e(TAG, "WS failure: ${t.message}"); ws = null; stopWatchdog(); scheduleReconnect() }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { Log.d(TAG, "WS closed: $code $reason"); ws = null; stopWatchdog(); scheduleReconnect() }
            })
        } catch (e: Exception) {
            Log.e(TAG, "connect error", e); ws = null; stopWatchdog(); scheduleReconnect()
        }
    }

    private fun scheduleReconnect() {
        if (!shouldReconnect) return
        val delay = (1000L shl reconnectAttempts).coerceAtMost(15_000L)
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
        stopWatchdog()
    }

    private fun isOpen(): Boolean = try { ws != null && ws!!.send("") } catch (_: Exception) { false }

    private fun flushQueue() {
        val copy: List<Pending>; synchronized(pending) { copy = pending.toList() }
        copy.forEach { p -> try { ws?.send(p.json) } catch (_: Exception) {} }
    }

    // Allow callers to ensure a connection exists; if not, reconnect immediately
    fun ensureConnected() { if (!isOpen()) connect() }

    fun sendJson(type: String, payload: Map<String, Any?> = emptyMap()) {
        val id = UUID.randomUUID().toString()
        val body = HashMap<String, Any?>(); body["type"] = type; body["id"] = id
        // Prevent payload from overriding reserved keys
        for ((k, v) in payload) if (k != "type" && k != "id") body[k] = v
        val json = gson.toJson(body)
        synchronized(pending) { pending.add(Pending(id, json)); if (pending.size > 200) pending.removeAt(0) }
        lastActivityMs = System.currentTimeMillis()
        try { ws?.send(json) ?: run { scheduleReconnect() } } catch (_: Exception) { scheduleReconnect() }
    }
}
