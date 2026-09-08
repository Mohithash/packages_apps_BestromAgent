/*
 * Copyright (C) 2026 The BestROM Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package com.bestrom.agent.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.bestrom.agent.AgentState
import com.bestrom.agent.R
import com.bestrom.agent.audit.AuditLog
import com.bestrom.agent.functions.AppFunctionsClient
import com.bestrom.agent.toggle.AgentToggle
import com.bestrom.agent.ui.AgentSettingsActivity
import java.io.BufferedInputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * The bridge: a unix abstract socket speaking newline-delimited JSON-RPC.
 *
 * It is a foreground service on purpose. Agent mode is never invisible: an
 * ongoing notification is up for the whole session, it carries a Stop action,
 * and the socket lives exactly as long as the service does. Nothing here is
 * reachable from the network - the socket has no address outside the device and
 * the app holds no INTERNET permission.
 */
class AgentBridgeService : Service(), Methods.Host {

    companion object {
        private const val TAG = "BestromAgent"

        const val ACTION_START = "com.bestrom.agent.action.START"
        const val ACTION_STOP = "com.bestrom.agent.action.STOP"
        const val ACTION_NEW_CODE = "com.bestrom.agent.action.NEW_CODE"

        const val SOCKET_NAME = "bestrom_agent"
        const val CHANNEL_ID = "agent"
        const val NOTIFICATION_ID = 1

        const val MAX_CONNECTIONS = 4
        const val CONNECTION_IDLE_MS = 120_000
        const val BRIDGE_IDLE_MS = Methods.IDLE_TIMEOUT_S * 1000L
        private const val IDLE_WARN_MS = 60_000L
    }

    override val auth = Auth()
    override val rateLimiter = RateLimiter(capacity = Methods.RATE_LIMIT_PER_S)
    override lateinit var audit: AuditLog
    override lateinit var functions: AppFunctionsClient
    override val context: Context
        get() = this
    override val callbackExecutor = Executors.newCachedThreadPool()

    private val running = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    private val connectionCount = AtomicInteger(0)
    private val lastRequestMs = java.util.concurrent.atomic.AtomicLong(0)

    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    private var idleThread: Thread? = null
    private val openSockets =
        Collections.synchronizedSet(java.util.HashSet<LocalSocket>())

    override fun onCreate() {
        super.onCreate()
        audit = AuditLog.get(filesDir)
        functions = AppFunctionsClient(this, callbackExecutor)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_NEW_CODE) {
            if (running.get()) regenerateCode()
            return START_NOT_STICKY
        }
        if (running.get()) return START_NOT_STICKY

        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.notification_text)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        val socket =
            try {
                LocalServerSocket(SOCKET_NAME)
            } catch (e: Exception) {
                Log.e(TAG, "cannot bind the agent socket")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        server = socket
        running.set(true)
        auth.start(System.currentTimeMillis())
        AgentState.pairingCode = auth.currentCode()
        AgentState.paired.set(false)
        lastRequestMs.set(SystemClock.uptimeMillis())
        // Only now is the bridge live; the accessibility component is enabled
        // after this flag is set, never before.
        AgentState.bridgeLive.set(true)

        acceptThread = Thread({ acceptLoop(socket) }, "agent-accept").also { it.start() }
        idleThread = Thread(::idleLoop, "agent-idle").also { it.start() }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        shutdown()
        callbackExecutor.shutdownNow()
        super.onDestroy()
    }

    // ------------------------------------------------------------ Methods.Host

    override fun onAuthenticated() {
        AgentState.paired.set(true)
        noteRequest()
    }

    override fun noteRequest() {
        lastRequestMs.set(SystemClock.uptimeMillis())
    }

    override fun stopAgent() {
        stopRequested.set(true)
    }

    // ------------------------------------------------------------------ socket

    private fun acceptLoop(socket: LocalServerSocket) {
        while (running.get()) {
            val client =
                try {
                    socket.accept()
                } catch (e: Exception) {
                    if (running.get()) Log.w(TAG, "accept failed")
                    return
                }
            if (connectionCount.get() >= MAX_CONNECTIONS) {
                refuse(client)
                continue
            }
            connectionCount.incrementAndGet()
            openSockets.add(client)
            Thread({ serve(client) }, "agent-conn").start()
        }
    }

    private fun refuse(client: LocalSocket) {
        try {
            Framing.writeLine(
                client.outputStream,
                JsonRpc.error(null, JsonRpc.INTERNAL_ERROR, "too many connections").toString(),
            )
        } catch (e: Exception) {
            // The peer is going away either way.
        } finally {
            closeQuietly(client)
        }
    }

    private fun serve(client: LocalSocket) {
        val session = Methods.Session()
        try {
            client.soTimeout = CONNECTION_IDLE_MS
            val input = BufferedInputStream(client.inputStream)
            val output: OutputStream = client.outputStream

            while (running.get()) {
                val line =
                    try {
                        Framing.readLine(input, Framing.MAX_REQUEST_LINE_BYTES) ?: break
                    } catch (e: Framing.LineTooLongException) {
                        Framing.writeLine(
                            output,
                            JsonRpc.error(null, JsonRpc.INVALID_REQUEST, "request line too long")
                                .toString(),
                        )
                        break
                    }
                if (line.isBlank()) continue

                val response =
                    try {
                        val request = JsonRpc.parse(line)
                        Methods.dispatch(this, session, request)
                    } catch (e: JsonRpc.RpcException) {
                        JsonRpc.error(null, e.code, e.message ?: "error", e.data)
                    }

                writeResponse(output, response)

                if (session.strikes >= Auth.MAX_STRIKES) {
                    AgentState.pairingCode = auth.currentCode()
                    break
                }
                if (stopRequested.get()) {
                    // The reply is out; now run the whole off sequence.
                    callbackExecutor.execute { AgentToggle(applicationContext).turnOff() }
                    break
                }
            }
        } catch (e: Exception) {
            // A dropped connection is normal; it is not a bridge failure.
        } finally {
            closeQuietly(client)
            openSockets.remove(client)
            connectionCount.decrementAndGet()
        }
    }

    private fun writeResponse(output: OutputStream, response: JSONObject) {
        val text = response.toString()
        try {
            Framing.writeLine(output, text, Framing.MAX_RESPONSE_LINE_BYTES)
        } catch (e: Framing.LineTooLongException) {
            Framing.writeLine(
                output,
                JsonRpc.error(
                        response.opt("id"),
                        JsonRpc.INTERNAL_ERROR,
                        "the response is larger than the frame limit",
                    )
                    .toString(),
                Framing.MAX_RESPONSE_LINE_BYTES,
            )
        }
    }

    // -------------------------------------------------------------- idle timer

    private fun idleLoop() {
        var warned = false
        while (running.get()) {
            try {
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                return
            }
            val idle = SystemClock.uptimeMillis() - lastRequestMs.get()
            if (!warned && idle > BRIDGE_IDLE_MS - IDLE_WARN_MS) {
                warned = true
                updateNotification(getString(R.string.notification_text_idle))
            }
            if (idle > BRIDGE_IDLE_MS) {
                Log.i(TAG, "bridge idle; stopping Agent mode")
                callbackExecutor.execute { AgentToggle(applicationContext).turnOff() }
                return
            }
        }
    }

    // ------------------------------------------------------------- notification

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, AgentSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, AgentBridgeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_agent_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(
                        null as android.graphics.drawable.Icon?,
                        getString(R.string.notification_stop),
                        stop,
                    )
                    .build()
            )
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: Exception) {
            // A notification that cannot be updated is not worth a crash.
        }
    }

    // ---------------------------------------------------------------- shutdown

    private fun shutdown() {
        if (!running.getAndSet(false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        AgentState.bridgeLive.set(false)
        AgentState.paired.set(false)
        AgentState.pairingCode = null
        auth.clear()
        rateLimiter.reset()

        try {
            server?.close()
        } catch (e: Exception) {
        }
        server = null

        synchronized(openSockets) {
            for (socket in ArrayList(openSockets)) closeQuietly(socket)
            openSockets.clear()
        }
        connectionCount.set(0)
        idleThread?.interrupt()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeQuietly(socket: LocalSocket) {
        try {
            socket.shutdownInput()
        } catch (e: Exception) {
        }
        try {
            socket.close()
        } catch (e: Exception) {
        }
    }

    /** Regenerates the code the settings screen shows. */
    fun regenerateCode(): String {
        val code = auth.newCode(System.currentTimeMillis())
        AgentState.pairingCode = code
        return code
    }
}
