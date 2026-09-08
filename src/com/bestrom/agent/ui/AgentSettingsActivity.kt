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
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bestrom.agent.AgentState
import com.bestrom.agent.Denylist
import com.bestrom.agent.R
import com.bestrom.agent.audit.AuditLog
import com.bestrom.agent.bridge.AgentBridgeService
import com.bestrom.agent.toggle.AgentToggle
import java.util.concurrent.Executors

/**
 * The Agent mode screen: one switch, the pairing code, the bridge status and the
 * last 500 requests.
 *
 * The switch state is derived, never stored. Opening the screen also reconciles:
 * after a reboot the bridge is not live, so whatever the components and the
 * secure setting were left as is undone here.
 */
class AgentSettingsActivity : Activity() {

    private lateinit var toggle: AgentToggle
    private lateinit var audit: AuditLog
    private lateinit var adapter: AuditAdapter

    private lateinit var masterSwitch: Switch
    private lateinit var statusBridge: TextView
    private lateinit var statusA11y: TextView
    private lateinit var statusSession: TextView
    private lateinit var pairingGroup: LinearLayout
    private lateinit var pairingCode: TextView
    private lateinit var emptyLabel: TextView
    private lateinit var clearButton: Button
    private lateinit var excludedSummary: TextView

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var busy = false

    private val refresh =
        object : Runnable {
            override fun run() {
                render()
                main.postDelayed(this, 1000)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_agent_settings)
        setTitle(R.string.agent_title)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        toggle = AgentToggle(applicationContext)
        audit = AuditLog.get(filesDir)
        adapter = AuditAdapter()

        masterSwitch = findViewById(R.id.master_switch)
        statusBridge = findViewById(R.id.status_bridge)
        statusA11y = findViewById(R.id.status_a11y)
        statusSession = findViewById(R.id.status_session)
        pairingGroup = findViewById(R.id.pairing_group)
        pairingCode = findViewById(R.id.pairing_code)
        emptyLabel = findViewById(R.id.activity_empty)
        clearButton = findViewById(R.id.clear_log)
        excludedSummary = findViewById(R.id.excluded_summary)

        val list: RecyclerView = findViewById(R.id.audit_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        masterSwitch.setOnCheckedChangeListener { button: CompoundButton, checked: Boolean ->
            if (busy || !button.isPressed) return@setOnCheckedChangeListener
            if (checked) turnOn() else turnOff()
        }

        findViewById<Button>(R.id.new_code).setOnClickListener {
            startService(
                Intent(this, AgentBridgeService::class.java)
                    .setAction(AgentBridgeService.ACTION_NEW_CODE)
            )
            main.postDelayed({ render() }, 150)
        }

        clearButton.setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.excluded_edit).setOnClickListener { editExcluded() }
    }

    override fun onResume() {
        super.onResume()
        // Reconcile: a reboot leaves the components enabled and the secure
        // setting written, with no bridge behind either.
        if (!AgentState.bridgeLive.get()) {
            worker.execute { toggle.reconcileOff() }
        }
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun turnOn() {
        if (!toggle.hasNotificationPermission()) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1,
            )
            masterSwitch.isChecked = false
            return
        }
        busy = true
        masterSwitch.isEnabled = false
        worker.execute {
            val result = toggle.turnOn()
            main.post {
                busy = false
                masterSwitch.isEnabled = true
                when (result) {
                    AgentToggle.Result.NEEDS_NOTIFICATION_PERMISSION ->
                        Toast.makeText(this, R.string.needs_notifications, Toast.LENGTH_LONG).show()
                    AgentToggle.Result.BRIDGE_DID_NOT_START ->
                        Toast.makeText(this, R.string.bridge_failed, Toast.LENGTH_LONG).show()
                    AgentToggle.Result.ON -> {}
                }
                render()
            }
        }
    }

    private fun turnOff() {
        busy = true
        masterSwitch.isEnabled = false
        worker.execute {
            toggle.turnOff()
            main.post {
                busy = false
                masterSwitch.isEnabled = true
                render()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && toggle.hasNotificationPermission()) turnOn()
    }

    private fun confirmClear() {
        val total = audit.size()
        AlertDialog.Builder(this)
            .setTitle(R.string.activity_clear_title)
            .setMessage(getString(R.string.activity_clear_message, total))
            .setNegativeButton(R.string.activity_clear_cancel, null)
            .setPositiveButton(R.string.activity_clear_confirm) { _, _ ->
                audit.clear()
                render()
            }
            .show()
    }

    /**
     * The excluded package list, edited as plain text: one package name per
     * line. It is short by design and nothing else on the phone writes it.
     */
    private fun editExcluded() {
        val field = EditText(this)
        field.setText(Denylist.read(this).joinToString("\n"))
        field.setSingleLine(false)
        field.setHint("com.example.app")
        val padding = (resources.displayMetrics.density * 24).toInt()
        val frame = LinearLayout(this)
        frame.orientation = LinearLayout.VERTICAL
        frame.setPadding(padding, padding / 2, padding, 0)
        frame.addView(field)

        AlertDialog.Builder(this)
            .setTitle(R.string.excluded_header)
            .setMessage(R.string.excluded_dialog_message)
            .setView(frame)
            .setNegativeButton(R.string.excluded_dialog_cancel, null)
            .setPositiveButton(R.string.excluded_dialog_save) { _, _ ->
                Denylist.write(this, field.text.toString().split('\n'))
                render()
            }
            .show()
    }

    private fun render() {
        val on = AgentState.bridgeLive.get()
        if (masterSwitch.isChecked != on && !busy) masterSwitch.isChecked = on

        statusBridge.setText(if (on) R.string.status_bridge_on else R.string.status_bridge_off)
        statusA11y.setText(
            if (toggle.accessibilityConnected()) R.string.status_a11y_on
            else R.string.status_a11y_off
        )
        statusSession.setText(
            if (AgentState.paired.get()) R.string.status_session_paired
            else R.string.status_session_none
        )

        val code = AgentState.pairingCode
        if (on && code != null) {
            pairingGroup.visibility = View.VISIBLE
            pairingCode.text = code
        } else {
            pairingGroup.visibility = View.GONE
        }

        val excluded = Denylist.read(this)
        excludedSummary.text =
            if (excluded.isEmpty()) getString(R.string.excluded_none)
            else getString(R.string.excluded_count, excluded.size) + "  " + excluded.joinToString(", ")

        val entries = audit.list(AuditLog.CAPACITY)
        adapter.submit(entries)
        emptyLabel.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
        clearButton.visibility = if (entries.isEmpty()) View.GONE else View.VISIBLE
    }
}
