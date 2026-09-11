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

package com.bestrom.agent.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bestrom.agent.AgentState
import com.bestrom.agent.R
import com.bestrom.agent.alert.ActivityTracker
import com.bestrom.agent.brain.BrainPrefs
import com.bestrom.agent.runner.PendingChoice
import com.bestrom.agent.runner.PendingConfirm
import com.bestrom.agent.runner.TaskState
import com.bestrom.agent.security.SettingsWatch
import com.bestrom.agent.ui.MarkdownLite
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * On-device agent chat: composer at the bottom, bubbles above, goal chips
 * when empty.
 *
 * Also answers android.intent.action.ASSIST. It reads NOTHING from the intent —
 * no extras, no data, no clipboard. A task only starts from text typed here.
 */
class AgentTaskActivity : Activity() {

    companion object {
        private const val REFRESH_MS = 350L
    }

    private lateinit var chatList: RecyclerView
    private lateinit var emptyState: View
    private lateinit var composerField: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var stopButton: ImageButton
    private lateinit var statusChip: TextView
    private lateinit var setupBanner: View
    private lateinit var setupMessage: TextView
    private lateinit var setupButton: Button
    private lateinit var adapter: ChatAdapter

    private val main = Handler(Looper.getMainLooper())
    private var sheet: AlertDialog? = null
    private var shownConfirm: PendingConfirm? = null
    private var shownChoice: PendingChoice? = null
    private var lastCount = -1
    private var sessionId: String? = null
    private lateinit var historyStore: ChatHistoryStore

    private val refresh =
        object : Runnable {
            override fun run() {
                render()
                main.postDelayed(this, REFRESH_MS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )
        setContentView(R.layout.activity_agent_task)
        historyStore = ChatHistoryStore(this)
        SettingsWatch.sync(this)

        chatList = findViewById(R.id.chat_list)
        emptyState = findViewById(R.id.empty_state)
        composerField = findViewById(R.id.composer_field)
        sendButton = findViewById(R.id.send_button)
        stopButton = findViewById(R.id.stop_button)
        statusChip = findViewById(R.id.status_chip)
        setupBanner = findViewById(R.id.setup_banner)
        setupMessage = findViewById(R.id.setup_message)
        setupButton = findViewById(R.id.setup_button)

        adapter = ChatAdapter()
        val layout = LinearLayoutManager(this).also { it.stackFromEnd = true }
        chatList.layoutManager = layout
        chatList.adapter = adapter

        sendButton.setOnClickListener { send() }
        stopButton.setOnClickListener { AgentState.bridge?.stopTask() }
        findViewById<ImageButton>(R.id.btn_new_chat).setOnClickListener { newChat() }
        findViewById<ImageButton>(R.id.btn_settings).setOnClickListener {
            startActivity(Intent(this, AgentSettingsActivity::class.java))
        }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showMenu() }

        bindChip(R.id.chip_settings, R.string.chip_open_settings)
        bindChip(R.id.chip_wifi, R.string.chip_toggle_wifi)
        bindChip(R.id.chip_order_food, R.string.chip_order_food)
        bindChip(R.id.chip_summarize, R.string.chip_summarize_screen)
        bindChip(R.id.chip_maps, R.string.chip_maps)
        bindChip(R.id.chip_reminder, R.string.chip_reminder)

        composerField.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_SEND ||
                (event != null &&
                    event.keyCode == KeyEvent.KEYCODE_ENTER &&
                    event.action == KeyEvent.ACTION_DOWN &&
                    !event.isShiftPressed)
            ) {
                send()
                true
            } else {
                false
            }
        }
    }

    private fun bindChip(id: Int, textRes: Int) {
        findViewById<TextView>(id).setOnClickListener {
            composerField.setText(getString(textRes))
            composerField.setSelection(composerField.text.length)
            send()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // launch_app (and any leave while a task runs) — shrink instead of die.
        maybeEnterPip()
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            AgentState.keepVisible.set(false)
            findViewById<View>(R.id.composer_row).visibility = View.GONE
            findViewById<View>(R.id.btn_menu).visibility = View.GONE
            findViewById<View>(R.id.btn_settings).visibility = View.GONE
            findViewById<View>(R.id.btn_new_chat).visibility = View.GONE
        } else {
            findViewById<View>(R.id.composer_row).visibility = View.VISIBLE
            findViewById<View>(R.id.btn_menu).visibility = View.VISIBLE
            findViewById<View>(R.id.btn_settings).visibility = View.VISIBLE
            findViewById<View>(R.id.btn_new_chat).visibility = View.VISIBLE
        }
    }

    private fun maybeEnterPip() {
        if (isInPictureInPictureMode) return
        val task = AgentState.task
        val running = task != null && !task.finished()
        if (!running && !AgentState.keepVisible.get()) return
        try {
            val params =
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(9, 16))
                    .build()
            enterPictureInPictureMode(params)
        } catch (_: Exception) {
            // Device / OEM may refuse PiP; task keeps running in the service.
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // singleTask assist gesture: still bring nothing with it.
    }

    override fun onResume() {
        super.onResume()
        ActivityTracker.onResume(this)
        main.post(refresh)
    }

    override fun onPause() {
        ActivityTracker.onPause(this)
        persistChat()
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        sheet?.dismiss()
        sheet = null
        super.onDestroy()
    }

    private fun newChat() {
        if (AgentState.task != null && AgentState.task?.finished() == false) {
            AgentState.bridge?.stopTask()
        }
        persistChat()
        sessionId = null
        ChatStore.clear()
        lastCount = -1
        render()
    }

    private fun persistChat() {
        try {
            sessionId = historyStore.saveCurrent(ChatStore.durableSnapshot(), sessionId)
        } catch (_: Exception) {
        }
    }

    private fun exportChat() {
        val durable = ChatStore.durableSnapshot()
        if (durable.isEmpty()) {
            Toast.makeText(this, R.string.chat_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        for (m in durable) {
            val who = if (m.role == ChatStore.Role.USER) "You" else "Agent"
            sb.append(who).append(":\n")
            sb.append(
                if (m.role == ChatStore.Role.ASSISTANT) MarkdownLite.toPlain(m.text)
                else m.text
            )
            sb.append("\n\n")
        }
        val send =
            Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.chat_export_subject))
                .putExtra(Intent.EXTRA_TEXT, sb.toString().trim())
        startActivity(Intent.createChooser(send, getString(R.string.chat_menu_export)))
    }

    private fun showHistory() {
        val sessions = historyStore.listSessions()
        if (sessions.isEmpty()) {
            Toast.makeText(this, R.string.chat_history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels =
            sessions
                .map {
                    val whenStr =
                        SimpleDateFormat("MMM d HH:mm", Locale.getDefault())
                            .format(Date(it.updatedMs))
                    "$whenStr · ${it.title}"
                }
                .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.chat_history_title)
            .setItems(labels) { _, which ->
                val s = sessions[which]
                persistChat()
                sessionId = s.id
                ChatStore.replaceAll(s.messages)
                lastCount = -1
                render()
            }
            .show()
    }

    private fun showMenu() {
        val items =
            arrayOf(
                getString(R.string.chat_menu_settings),
                getString(R.string.chat_menu_brain),
                getString(R.string.chat_menu_history),
                getString(R.string.chat_menu_export),
                getString(R.string.chat_new),
            )
        AlertDialog.Builder(this)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, AgentSettingsActivity::class.java))
                    1 -> startActivity(Intent(this, BrainSettingsActivity::class.java))
                    2 -> showHistory()
                    3 -> exportChat()
                    4 -> newChat()
                }
            }
            .show()
    }

    private fun send() {
        val bridge = AgentState.bridge
        if (bridge == null) {
            Toast.makeText(this, R.string.task_agent_off, Toast.LENGTH_LONG).show()
            showSetup(R.string.task_off_message, R.string.task_off_button) {
                startActivity(Intent(this, AgentSettingsActivity::class.java))
            }
            return
        }
        val goal = composerField.text.toString().trim()
        if (goal.isEmpty()) {
            Toast.makeText(this, R.string.task_needs_a_goal, Toast.LENGTH_SHORT).show()
            return
        }
        ChatStore.addUser(goal)
        val error = bridge.startTask(goal)
        if (error != null) {
            ChatStore.addAssistantError(error)
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            render()
            return
        }
        composerField.setText("")
        lastCount = -1
        render()
    }

    private fun render() {
        val on = AgentState.bridgeLive.get()
        val config = BrainPrefs.read(this)
        val task = AgentState.task
        val running = task != null && !task.finished()
        val waiting = task?.state == TaskState.WAITING

        if (!on) {
            showSetup(R.string.task_off_message, R.string.task_off_button) {
                startActivity(Intent(this, AgentSettingsActivity::class.java))
            }
        } else if (!config.configured()) {
            showSetup(R.string.task_no_brain_message, R.string.task_no_brain_button) {
                startActivity(Intent(this, BrainSettingsActivity::class.java))
            }
        } else {
            setupBanner.visibility = View.GONE
        }

        when {
            !on -> {
                statusChip.text = getString(R.string.chat_status_off)
                statusChip.setTextColor(getColor(R.color.agent_status_warn))
            }
            !config.configured() -> {
                statusChip.text = getString(R.string.chat_status_no_brain)
                statusChip.setTextColor(getColor(R.color.agent_status_warn))
            }
            waiting -> {
                statusChip.text = getString(R.string.chat_status_waiting)
                statusChip.setTextColor(getColor(R.color.agent_status_warn))
            }
            running -> {
                statusChip.text = getString(R.string.chat_status_acting)
                statusChip.setTextColor(getColor(R.color.agent_accent))
            }
            else -> {
                statusChip.text = getString(R.string.chat_status_ready, config.host())
                statusChip.setTextColor(getColor(R.color.agent_on_surface_muted))
            }
        }

        ChatStore.syncSteps(AgentState.steps())

        val snap = ChatStore.snapshot()
        emptyState.visibility = if (snap.isEmpty()) View.VISIBLE else View.GONE
        chatList.visibility = if (snap.isEmpty()) View.INVISIBLE else View.VISIBLE
        if (snap.size != lastCount) {
            adapter.submit(snap)
            lastCount = snap.size
            if (snap.isNotEmpty()) chatList.scrollToPosition(snap.size - 1)
        } else {
            adapter.submit(snap)
        }

        composerField.isEnabled = on && config.configured() && !running
        val pip = isInPictureInPictureMode
        sendButton.visibility = if (running || pip) View.GONE else View.VISIBLE
        sendButton.isEnabled = on && config.configured()
        stopButton.visibility = if (running) View.VISIBLE else View.GONE

        if (AgentState.keepVisible.get() && running) maybeEnterPip()

        maybeShowSheet(waiting)
    }

    private fun showSetup(message: Int, button: Int, action: () -> Unit) {
        setupBanner.visibility = View.VISIBLE
        setupMessage.setText(message)
        setupButton.setText(button)
        setupButton.setOnClickListener { action() }
    }

    private fun maybeShowSheet(waiting: Boolean) {
        val pendingConfirm = AgentState.confirm
        val pendingChoice = AgentState.choice
        if (!waiting || (pendingConfirm == null && pendingChoice == null)) {
            if (shownConfirm != null || shownChoice != null) {
                sheet?.dismiss()
                sheet = null
                shownConfirm = null
                shownChoice = null
            }
            return
        }
        if (pendingChoice != null) {
            if (pendingChoice === shownChoice) return
            sheet?.dismiss()
            shownConfirm = null
            shownChoice = pendingChoice
            sheet = ChoiceSheet.show(this, pendingChoice)
            return
        }
        if (pendingConfirm == null || pendingConfirm === shownConfirm) return
        sheet?.dismiss()
        shownChoice = null
        shownConfirm = pendingConfirm
        sheet = ConfirmSheet.show(this, pendingConfirm)
    }
}
