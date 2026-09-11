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
import com.bestrom.agent.AgentPrefs
import com.bestrom.agent.AgentState
import com.bestrom.agent.R
import com.bestrom.agent.audit.AuditLog
import com.bestrom.agent.brain.BrainPrefs
import com.bestrom.agent.functions.AppFunctionsClient
import com.bestrom.agent.runner.AgentRunner
import com.bestrom.agent.runner.Task
import com.bestrom.agent.toggle.AgentToggle
import com.bestrom.agent.ui.AgentSettingsActivity
import com.bestrom.agent.ui.AgentTaskActivity
import java.io.BufferedInputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * On-device agent host, and optionally the adb JSON-RPC bridge.
 *
 * Agent mode itself does not need a cable or adb. Chat and tool calls go through
 * Accessibility. The unix abstract socket (`localabstract:bestrom_agent`) is
 * only bound when [AgentPrefs.remoteAdb] is on, for a computer that forwards
 * over adb.
 *
 * It is a foreground service on purpose: an ongoing notification is up for the
 * whole session and carries Stop. Nothing here is reachable from the network.
 */
class AgentBridgeService : Service(), Methods.Host {

    companion object {
        private const val TAG = "BestromAgent"

        const val ACTION_START = "com.bestrom.agent.action.START"
        const val ACTION_STOP = "com.bestrom.agent.action.STOP"
        const val ACTION_NEW_CODE = "com.bestrom.agent.action.NEW_CODE"

        /** Ends the running task and leaves Agent mode on. */
        const val ACTION_STOP_TASK = "com.bestrom.agent.action.STOP_TASK"

        const val SOCKET_NAME = "bestrom_agent"
        const val CHANNEL_ID = "agent"
        const val NOTIFICATION_ID = 1

        const val MAX_CONNECTIONS = 4

        /**
         * How many of the four slots an unauthenticated peer may hold. Two are
         * kept back so a peer that never pairs cannot lock the maintainer out.
         */
        const val MAX_UNAUTHENTICATED_CONNECTIONS = 2

        const val CONNECTION_IDLE_MS = 120_000

        /** The deadline before a connection authenticates. Then it gets the full one. */
        const val HANDSHAKE_IDLE_MS = 10_000

        /** Requests a connection may send before pairing or authenticating. */
        const val MAX_PREAUTH_REQUESTS = 8

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
    private val unauthenticatedCount = AtomicInteger(0)
    private val connectionSeq = AtomicInteger(0)
    private val lastRequestMs = java.util.concurrent.atomic.AtomicLong(0)

    private var server: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    private var idleThread: Thread? = null

    /** The one task that may be running, and the thread it runs on. */
    @Volatile
    private var runner: AgentRunner? = null

    private var taskThread: Thread? = null
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
            if (running.get() && AgentPrefs.remoteAdb(this) && server != null) {
                regenerateCode()
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP_TASK) {
            stopTask()
            return START_NOT_STICKY
        }
        if (running.get()) return START_NOT_STICKY

        createChannel()
        val remote = AgentPrefs.remoteAdb(this)
        startForeground(
            NOTIFICATION_ID,
            buildNotification(
                getString(
                    if (remote) R.string.notification_text
                    else R.string.notification_text_native
                ),
            ),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )

        running.set(true)
        AgentState.paired.set(false)
        lastRequestMs.set(SystemClock.uptimeMillis())
        // Live before a11y is enabled (see AgentToggle). Chat and tools need
        // this flag; the adb socket is optional.
        AgentState.bridgeLive.set(true)
        AgentState.bridge = this

        if (remote) {
            val socket =
                try {
                    LocalServerSocket(SOCKET_NAME)
                } catch (e: Exception) {
                    Log.e(TAG, "cannot bind the agent socket", e)
                    // Native chat still works; remote just did not come up.
                    AgentState.pairingCode = null
                    return START_NOT_STICKY
                }
            server = socket
            auth.setCodeListener { code, cooldownUntilMs ->
                AgentState.pairingCode = code.ifEmpty { null }
                AgentState.pairingCooldownUntilMs = cooldownUntilMs
            }
            auth.start(System.currentTimeMillis())
            acceptThread = Thread({ acceptLoop(socket) }, "agent-accept").also { it.start() }
            idleThread = Thread(::idleLoop, "agent-idle").also { it.start() }
        } else {
            // No pairing code and no idle kill: on-device use stays up until
            // the user turns Agent mode off.
            AgentState.pairingCode = null
            AgentState.pairingCooldownUntilMs = 0
        }
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

    // -------------------------------------------------------------------- task

    /**
     * Starts one task on one thread.
     *
     * Returns null when it started, or the sentence to show when it did not.
     * The caps and the confirmation policy are read here and copied into the
     * task, so changing a setting under a running task cannot widen it.
     */
    fun startTask(goal: String): String? {
        val text = goal.trim()
        if (text.isEmpty()) return getString(R.string.task_needs_a_goal)
        if (!running.get() || !AgentState.bridgeLive.get()) {
            return getString(R.string.task_agent_off)
        }
        if (AgentState.a11y == null) return getString(R.string.task_no_accessibility)
        // The thread as well as the state: a task that has been cancelled but
        // is still winding down would otherwise let a second one start beside
        // it, both writing the same step list and the same confirm slot.
        if (AgentState.task != null || taskThread?.isAlive == true) {
            return getString(R.string.task_already_running)
        }

        val config = BrainPrefs.read(this)
        if (!config.configured()) return getString(R.string.task_no_brain)

        noteRequest()

        val task =
            Task(
                java.util.UUID.randomUUID().toString(),
                text,
                config.autonomy,
                config.stepCap,
                config.tokenCap,
                config.sendScreenshots(),
                System.currentTimeMillis(),
            )
        AgentState.clearSteps()
        AgentState.task = task
        val created =
            AgentRunner(this, task, config, ::onRunnerNotify) { onTaskFinished() }
        runner = created
        taskThread = Thread(created, "agent-task").also { it.start() }
        return null
    }

    /** Ends the task without touching the switch. */
    fun stopTask() {
        runner?.cancel()
    }

    private fun onTaskFinished() {
        // What the task read goes with the task; the ending line stays.
        AgentState.keepEndingOnly()
        AgentState.task = null
        AgentState.confirm = null
        AgentState.choice = null
        runner = null
        taskThread = null
        if (running.get()) updateNotification(getString(R.string.notification_text))
    }

    /** The three states the ongoing notification moves between while a task runs. */
    private fun onRunnerNotify(mode: AgentRunner.Mode, text: String, step: Int) {
        if (!running.get()) return
        try {
            val manager = getSystemService(NotificationManager::class.java)
            val notification =
                when (mode) {
                    AgentRunner.Mode.IDLE -> buildNotification(getString(R.string.notification_text))
                    AgentRunner.Mode.WAITING ->
                        buildTaskNotification(getString(R.string.notification_waiting), step, true)
                    AgentRunner.Mode.ACTING -> buildTaskNotification(text, step, false)
                }
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // A notification that cannot be posted is not worth ending a task.
        }
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
            val uid = peerUid(client)
            if (!Peer.isAllowed(uid)) {
                // Not adbd and not root. Nothing is written to the audit log
                // for it: the counter on the settings screen is the record.
                AgentState.peerRefusals.incrementAndGet()
                Log.w(TAG, "refused a connection from uid $uid")
                closeQuietly(client)
                continue
            }
            if (connectionCount.get() >= MAX_CONNECTIONS) {
                refuse(client, "too many connections")
                continue
            }
            // Counted here rather than on the connection thread: the accept
            // loop is one thread, so check and increment cannot interleave.
            if (unauthenticatedCount.incrementAndGet() > MAX_UNAUTHENTICATED_CONNECTIONS) {
                unauthenticatedCount.decrementAndGet()
                refuse(client, "too many unauthenticated connections")
                continue
            }
            connectionCount.incrementAndGet()
            openSockets.add(client)
            val id = connectionSeq.incrementAndGet()
            Thread({ serve(client, id, uid) }, "agent-conn").start()
        }
    }

    /** The peer's uid, or [Peer.UID_UNKNOWN] when the kernel gives us nothing. */
    private fun peerUid(client: LocalSocket): Int =
        try {
            client.peerCredentials.uid
        } catch (e: Exception) {
            Peer.UID_UNKNOWN
        }

    private fun refuse(client: LocalSocket, message: String) {
        try {
            Framing.writeLine(
                client.outputStream,
                JsonRpc.error(null, JsonRpc.INTERNAL_ERROR, message).toString(),
            )
        } catch (e: Exception) {
            // The peer is going away either way.
        } finally {
            closeQuietly(client)
        }
    }

    private fun serve(client: LocalSocket, connectionId: Int, peerUid: Int) {
        val session = Methods.Session()
        session.connectionId = connectionId
        session.peerUid = peerUid
        var holdingUnauthenticatedSlot = true
        try {
            // Short until the peer authenticates, so an idle handshake cannot
            // sit on a slot for two minutes at a time.
            client.soTimeout = HANDSHAKE_IDLE_MS
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

                if (!session.authenticated) {
                    session.preAuthRequests++
                    if (session.preAuthRequests > MAX_PREAUTH_REQUESTS) {
                        AgentState.preAuthRefusals.incrementAndGet()
                        Framing.writeLine(
                            output,
                            JsonRpc.error(
                                    null,
                                    JsonRpc.UNAUTHENTICATED,
                                    "too many requests before pairing",
                                )
                                .toString(),
                        )
                        break
                    }
                }

                val response =
                    try {
                        val request = JsonRpc.parse(line)
                        Methods.dispatch(this, session, request)
                    } catch (e: JsonRpc.RpcException) {
                        JsonRpc.error(null, e.code, e.message ?: "error", e.data)
                    }

                writeResponse(output, response)

                if (session.authenticated && holdingUnauthenticatedSlot) {
                    holdingUnauthenticatedSlot = false
                    unauthenticatedCount.decrementAndGet()
                    client.soTimeout = CONNECTION_IDLE_MS
                }

                if (session.strikes >= Auth.MAX_STRIKES) break
                if (stopRequested.get()) {
                    // The reply is out; now run the whole off sequence.
                    runOffSequence()
                    break
                }
            }
        } catch (e: Exception) {
            // A dropped connection is normal; it is not a bridge failure.
        } finally {
            if (holdingUnauthenticatedSlot) unauthenticatedCount.decrementAndGet()
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
                runOffSequence()
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
        // Ask only opens the task screen with an empty field. Nothing anywhere
        // starts a task without a goal typed in that moment.
        val ask =
            PendingIntent.getActivity(
                this,
                3,
                Intent(this, AgentTaskActivity::class.java)
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
                        getString(R.string.notification_ask),
                        ask,
                    )
                    .build()
            )
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

    /**
     * The acting notification.
     *
     * VISIBILITY_PRIVATE, because the text is the goal the user typed and the
     * lock screen is not the place for it. There is no full-screen intent even
     * while a confirm sheet is waiting: an agent that can put a window over
     * everything is the shape of the attack, not the defence.
     */
    private fun buildTaskNotification(text: String, step: Int, waiting: Boolean): Notification {
        val open =
            PendingIntent.getActivity(
                this,
                4,
                Intent(this, AgentTaskActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stopTask =
            PendingIntent.getService(
                this,
                2,
                Intent(this, AgentBridgeService::class.java).setAction(ACTION_STOP_TASK),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val stop =
            PendingIntent.getService(
                this,
                1,
                Intent(this, AgentBridgeService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_agent_notification)
                .setContentTitle(getString(R.string.notification_acting))
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(
                    Notification.Action.Builder(
                            null as android.graphics.drawable.Icon?,
                            getString(R.string.notification_stop_task),
                            stopTask,
                        )
                        .build()
                )
                .addAction(
                    Notification.Action.Builder(
                            null as android.graphics.drawable.Icon?,
                            getString(R.string.notification_stop),
                            stop,
                        )
                        .build()
                )
        val cap = AgentState.task?.stepCap ?: 0
        // The step count without a text update per step.
        if (!waiting && cap > 0) builder.setProgress(cap, step.coerceIn(0, cap), false)
        return builder.build()
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

    /**
     * Runs the off sequence on a thread of its own.
     *
     * Not on callbackExecutor: turnOff() ends in stopSelf(), whose onDestroy
     * calls shutdownNow() on that executor and would interrupt the very thread
     * still inside reconcileOff(), leaving the component enabled and our name
     * in ENABLED_ACCESSIBILITY_SERVICES.
     */
    private fun runOffSequence() {
        Thread({ AgentToggle(applicationContext).turnOff() }, "agent-off").start()
    }

    private fun shutdown() {
        if (!running.getAndSet(false)) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        AgentState.bridgeLive.set(false)
        // The task goes with the bridge: the runner checks the flag before
        // every call, and cancel drops a model call that is in flight. The
        // task itself is cleared by onTaskFinished, on the runner's own
        // thread - clearing it here let a second task start while the first
        // was still inside a backoff.
        runner?.cancel()
        AgentState.bridge = null
        AgentState.clearSteps()
        AgentState.paired.set(false)
        auth.clear()
        auth.setCodeListener(null)
        AgentState.pairingCode = null
        AgentState.pairingCooldownUntilMs = 0
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
        unauthenticatedCount.set(0)
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

    /**
     * Issues a fresh code and drops any pairing with it.
     *
     * This is the New code button, and the way to pair a second client: a code
     * pairs once, and agent.pair is refused while a token is out.
     */
    fun regenerateCode(): String {
        val code = auth.reissue(System.currentTimeMillis())
        AgentState.paired.set(false)
        return code
    }
}
