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
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bestrom.agent.AgentState
import com.bestrom.agent.R
import com.bestrom.agent.brain.BrainPrefs
import com.bestrom.agent.runner.PendingConfirm
import com.bestrom.agent.runner.TaskState

/**
 * The task screen: a field for the goal, a line per step, and Stop.
 *
 * It also answers android.intent.action.ASSIST, which anything on the device
 * can send, so it reads NOTHING it is handed - no EXTRA_ASSIST_TEXT, no
 * EXTRA_ASSIST_CONTEXT, no intent data, no clipboard. A task only ever starts
 * from text the user typed into the field in front of them. Honouring text
 * from a caller would make every installed app able to hand a goal to a
 * privileged agent.
 */
class AgentTaskActivity : Activity() {

    companion object {
        /**
         * The one prefill this screen honours, and only from this same app -
         * the goal row on the Agent mode screen.
         */
        const val EXTRA_GOAL = "com.bestrom.agent.extra.GOAL"

        private const val REFRESH_MS = 400L
    }

    private lateinit var offGroup: View
    private lateinit var offMessage: TextView
    private lateinit var offButton: Button
    private lateinit var taskGroup: View
    private lateinit var goalField: EditText
    private lateinit var runButton: Button
    private lateinit var goalHeading: TextView
    private lateinit var endpointNote: TextView
    private lateinit var stopButton: Button
    private lateinit var adapter: TranscriptAdapter
    private lateinit var list: RecyclerView

    private val main = Handler(Looper.getMainLooper())
    private var sheet: AlertDialog? = null
    private var shownConfirm: PendingConfirm? = null

    private val refresh =
        object : Runnable {
            override fun run() {
                render()
                main.postDelayed(this, REFRESH_MS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_task)
        setTitle(R.string.agent_task_title)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        offGroup = findViewById(R.id.off_group)
        offMessage = findViewById(R.id.off_message)
        offButton = findViewById(R.id.off_button)
        taskGroup = findViewById(R.id.task_group)
        goalField = findViewById(R.id.goal_field)
        runButton = findViewById(R.id.run_button)
        goalHeading = findViewById(R.id.goal_heading)
        endpointNote = findViewById(R.id.endpoint_note)
        stopButton = findViewById(R.id.stop_button)

        adapter = TranscriptAdapter()
        list = findViewById(R.id.step_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        runButton.setOnClickListener { start() }
        stopButton.setOnClickListener { AgentState.bridge?.stopTask() }

        prefillFromOurselves()
    }

    /**
     * The only text this screen ever accepts from an Intent.
     *
     * getCallingPackage() is non-null only for startActivityForResult, so the
     * Agent mode screen starts this one that way on purpose: an ordinary
     * startActivity, which is all another app can do through the assist
     * filter, leaves it null and the field stays empty.
     */
    private fun prefillFromOurselves() {
        if (callingPackage != packageName) return
        val goal = intent?.getStringExtra(EXTRA_GOAL) ?: return
        goalField.setText(goal)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // singleTask, so the assist gesture returns to this instance. It still
        // brings nothing with it.
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        sheet?.dismiss()
        sheet = null
        super.onDestroy()
    }

    private fun start() {
        val bridge = AgentState.bridge
        if (bridge == null) {
            Toast.makeText(this, R.string.task_agent_off, Toast.LENGTH_LONG).show()
            return
        }
        val error = bridge.startTask(goalField.text.toString())
        if (error != null) {
            Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            return
        }
        goalField.setText("")
        render()
    }

    private fun render() {
        val on = AgentState.bridgeLive.get()
        val config = BrainPrefs.read(this)
        val task = AgentState.task

        if (!on) {
            // The switch is never turned on from here. This screen offers the
            // way to the screen that has the switch, and nothing more.
            showOff(R.string.task_off_message, R.string.task_off_button) {
                startActivity(Intent(this, AgentSettingsActivity::class.java))
            }
            return
        }
        if (!config.configured()) {
            showOff(R.string.task_no_brain_message, R.string.task_no_brain_button) {
                startActivity(Intent(this, AgentSettingsActivity::class.java))
            }
            return
        }

        offGroup.visibility = View.GONE
        taskGroup.visibility = View.VISIBLE

        val running = task != null && !task.finished()
        goalField.isEnabled = !running
        runButton.isEnabled = !running
        runButton.visibility = if (running) View.GONE else View.VISIBLE
        stopButton.visibility = if (running) View.VISIBLE else View.GONE
        goalField.visibility = if (running) View.GONE else View.VISIBLE
        endpointNote.visibility = if (running) View.GONE else View.VISIBLE
        endpointNote.text = getString(R.string.task_endpoint_note, config.host())

        if (task != null) {
            goalHeading.visibility = View.VISIBLE
            goalHeading.text = task.goal
        } else {
            goalHeading.visibility = View.GONE
        }

        val steps = AgentState.steps()
        adapter.submit(steps)
        if (steps.isNotEmpty()) list.scrollToPosition(steps.size - 1)

        maybeShowSheet(task?.state == TaskState.WAITING)
    }

    private fun showOff(message: Int, button: Int, action: () -> Unit) {
        offGroup.visibility = View.VISIBLE
        taskGroup.visibility = View.GONE
        offMessage.setText(message)
        offButton.setText(button)
        offButton.setOnClickListener { action() }
    }

    /** Raises the sheet the runner is blocked on, once. */
    private fun maybeShowSheet(waiting: Boolean) {
        val pending = AgentState.confirm
        if (pending == null || !waiting) {
            if (shownConfirm != null) {
                sheet?.dismiss()
                sheet = null
                shownConfirm = null
            }
            return
        }
        if (pending === shownConfirm) return
        sheet?.dismiss()
        shownConfirm = pending
        sheet = ConfirmSheet.show(this, pending)
    }
}
