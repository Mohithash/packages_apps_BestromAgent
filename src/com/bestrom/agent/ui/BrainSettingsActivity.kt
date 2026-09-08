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
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bestrom.agent.R
import com.bestrom.agent.brain.ApiKeyStore
import com.bestrom.agent.brain.BrainConfig
import com.bestrom.agent.brain.BrainPreset
import com.bestrom.agent.brain.BrainPrefs
import com.bestrom.agent.brain.BrainUrl
import com.bestrom.agent.brain.ChatMessage
import com.bestrom.agent.brain.OpenAiCompatClient
import java.util.concurrent.Executors

/**
 * Where the endpoint, the key and the limits live.
 *
 * Not exported. This is where the API key is typed, and an activity another
 * app can start is an activity another app can put a window over.
 *
 * The key is written straight into [ApiKeyStore] and is never read back into a
 * field: the row says Set or Not set, and Clear is the only other thing that
 * can be done to it.
 */
class BrainSettingsActivity : Activity() {

    private lateinit var presetRow: TextView
    private lateinit var baseUrlRow: TextView
    private lateinit var keyRow: TextView
    private lateinit var workspaceGroup: View
    private lateinit var workspaceRow: TextView
    private lateinit var modelRow: TextView
    private lateinit var screenshotsRow: TextView
    private lateinit var confirmRow: TextView
    private lateinit var confirmWarning: TextView
    private lateinit var stepRow: TextView
    private lateinit var tokenRow: TextView
    private lateinit var testResult: TextView

    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var config = BrainConfig()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_brain_settings)
        setTitle(R.string.brain_title)
        actionBar?.setDisplayHomeAsUpEnabled(true)

        presetRow = findViewById(R.id.preset_summary)
        baseUrlRow = findViewById(R.id.base_url_summary)
        keyRow = findViewById(R.id.key_summary)
        workspaceGroup = findViewById(R.id.workspace_group)
        workspaceRow = findViewById(R.id.workspace_summary)
        modelRow = findViewById(R.id.model_summary)
        screenshotsRow = findViewById(R.id.screenshots_summary)
        confirmRow = findViewById(R.id.confirm_summary)
        confirmWarning = findViewById(R.id.confirm_warning)
        stepRow = findViewById(R.id.step_summary)
        tokenRow = findViewById(R.id.token_summary)
        testResult = findViewById(R.id.test_result)

        config = BrainPrefs.read(this)

        findViewById<View>(R.id.preset_row).setOnClickListener { pickPreset() }
        findViewById<View>(R.id.base_url_row).setOnClickListener { editBaseUrl() }
        findViewById<View>(R.id.key_row).setOnClickListener { editKey() }
        findViewById<View>(R.id.workspace_row).setOnClickListener { editWorkspace() }
        findViewById<View>(R.id.model_row).setOnClickListener { editModel() }
        findViewById<View>(R.id.screenshots_row).setOnClickListener { toggleScreenshots() }
        findViewById<View>(R.id.confirm_row).setOnClickListener { pickConfirmation() }
        findViewById<View>(R.id.step_row).setOnClickListener { editStepCap() }
        findViewById<View>(R.id.token_row).setOnClickListener { editTokenCap() }
        findViewById<Button>(R.id.key_clear).setOnClickListener { clearKey() }
        findViewById<Button>(R.id.test_button).setOnClickListener { test() }
        render()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun save() {
        BrainPrefs.write(this, config)
        render()
    }

    private fun render() {
        presetRow.text = config.preset.label
        baseUrlRow.text =
            if (config.baseUrl.isEmpty()) getString(R.string.brain_not_set) else config.baseUrl
        // Set or Not set. Never the value, and never the first characters of
        // it either - a masked key is still a key in a screenshot.
        keyRow.setText(
            if (ApiKeyStore.isSet(this)) R.string.brain_key_set else R.string.brain_key_not_set
        )
        workspaceGroup.visibility =
            if (config.preset == BrainPreset.ANTHROPIC) View.VISIBLE else View.GONE
        workspaceRow.text =
            if (config.workspaceId.isEmpty()) getString(R.string.brain_not_set)
            else config.workspaceId
        modelRow.text =
            if (config.model.isEmpty()) getString(R.string.brain_not_set) else config.model
        screenshotsRow.setText(
            if (config.screenshots) R.string.brain_screenshots_on
            else R.string.brain_screenshots_off
        )
        confirmRow.setText(
            if (config.autonomous) R.string.brain_confirm_autonomous
            else R.string.brain_confirm_each
        )
        confirmWarning.visibility = if (config.autonomous) View.VISIBLE else View.GONE
        stepRow.text = config.stepCap.toString()
        tokenRow.text = config.tokenCap.toString()
    }

    // ------------------------------------------------------------------- rows

    private fun pickPreset() {
        val presets = BrainPreset.values()
        val labels = presets.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.brain_preset)
            .setSingleChoiceItems(labels, presets.indexOf(config.preset)) { dialog, which ->
                val preset = presets[which]
                config =
                    config.copy(
                        preset = preset,
                        // Both stay editable; this only fills them in.
                        baseUrl = preset.defaultBaseUrl,
                        model = preset.models.firstOrNull()?.takeIf { !it.startsWith("(") } ?: "",
                    )
                dialog.dismiss()
                save()
            }
            .setNegativeButton(R.string.brain_cancel, null)
            .show()
    }

    private fun editBaseUrl() {
        text(R.string.brain_base_url, config.baseUrl, InputType.TYPE_TEXT_VARIATION_URI) {
            val rejection = BrainUrl.reject(it)
            if (rejection != null) {
                Toast.makeText(this, rejection, Toast.LENGTH_LONG).show()
            } else {
                config = config.copy(baseUrl = it.trim())
                save()
            }
        }
    }

    private fun editKey() {
        text(R.string.brain_key, "", InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            if (!ApiKeyStore.put(this, it.trim())) {
                Toast.makeText(this, R.string.brain_key_failed, Toast.LENGTH_LONG).show()
            }
            render()
        }
    }

    private fun clearKey() {
        ApiKeyStore.clear(this)
        render()
    }

    private fun editWorkspace() {
        text(R.string.brain_workspace, config.workspaceId, InputType.TYPE_CLASS_TEXT) {
            config = config.copy(workspaceId = it.trim())
            save()
        }
    }

    private fun editModel() {
        val suggestions = config.preset.models.filter { !it.startsWith("(") }
        text(
            R.string.brain_model,
            config.model,
            InputType.TYPE_CLASS_TEXT,
            if (suggestions.isEmpty()) "" else getString(
                R.string.brain_model_suggestions,
                suggestions.joinToString(", "),
            ),
        ) {
            config = config.copy(model = it.trim())
            save()
        }
    }

    private fun toggleScreenshots() {
        if (config.screenshots) {
            config = config.copy(screenshots = false)
            save()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.brain_screenshots)
            .setMessage(R.string.brain_screenshots_warning)
            .setNegativeButton(R.string.brain_cancel, null)
            .setPositiveButton(R.string.brain_turn_on) { _, _ ->
                config = config.copy(screenshots = true)
                save()
            }
            .show()
    }

    private fun pickConfirmation() {
        val labels =
            arrayOf(
                getString(R.string.brain_confirm_each),
                getString(R.string.brain_confirm_autonomous),
            )
        AlertDialog.Builder(this)
            .setTitle(R.string.brain_confirm)
            .setSingleChoiceItems(labels, if (config.autonomous) 1 else 0) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    config = config.copy(autonomous = false)
                    save()
                    return@setSingleChoiceItems
                }
                // Turning the asking off is a deliberate act, said in words
                // once, and the warning under the row stays there afterwards.
                AlertDialog.Builder(this)
                    .setTitle(R.string.brain_confirm_autonomous)
                    .setMessage(R.string.brain_confirm_autonomous_warning)
                    .setNegativeButton(R.string.brain_cancel, null)
                    .setPositiveButton(R.string.brain_turn_on) { _, _ ->
                        config = config.copy(autonomous = true)
                        save()
                    }
                    .show()
            }
            .setNegativeButton(R.string.brain_cancel, null)
            .show()
    }

    private fun editStepCap() {
        number(R.string.brain_steps, config.stepCap) {
            config =
                config.copy(
                    stepCap = it.coerceIn(BrainConfig.MIN_STEP_CAP, BrainConfig.MAX_STEP_CAP)
                )
            save()
        }
    }

    private fun editTokenCap() {
        number(R.string.brain_tokens, config.tokenCap) {
            config =
                config.copy(
                    tokenCap = it.coerceIn(BrainConfig.MIN_TOKEN_CAP, BrainConfig.MAX_TOKEN_CAP)
                )
            save()
        }
    }

    // ------------------------------------------------------------------- test

    /**
     * One minimal call: no tools, one token of room.
     *
     * It reports the status and the model the endpoint says answered, and
     * never the body - providers put request identifiers and sometimes echoed
     * parameters in there.
     */
    private fun test() {
        if (!config.configured()) {
            testResult.setText(R.string.brain_test_not_configured)
            return
        }
        testResult.setText(R.string.brain_test_running)
        val snapshot = config
        worker.execute {
            val client = OpenAiCompatClient(snapshot, { ApiKeyStore.lookup(this) })
            val outcome =
                client.complete(
                    "",
                    listOf(ChatMessage(ChatMessage.USER, "ping")),
                    null,
                    maxTokens = 1,
                )
            val text =
                when (outcome) {
                    is OpenAiCompatClient.Outcome.Ok ->
                        getString(
                            R.string.brain_test_ok,
                            outcome.response.model ?: snapshot.model,
                        )
                    is OpenAiCompatClient.Outcome.Fail -> outcome.error.sentence
                }
            // Sixty seconds is long enough for this screen to be gone.
            main.post { if (!isDestroyed && !isFinishing) testResult.text = text }
        }
    }

    // ---------------------------------------------------------------- dialogs

    private fun text(
        title: Int,
        current: String,
        inputType: Int,
        message: String = "",
        onSave: (String) -> Unit,
    ) {
        val field = EditText(this)
        field.setText(current)
        field.inputType = InputType.TYPE_CLASS_TEXT or inputType
        field.setSingleLine(true)
        val padding = (resources.displayMetrics.density * 24).toInt()
        val frame = LinearLayout(this)
        frame.orientation = LinearLayout.VERTICAL
        frame.setPadding(padding, padding / 2, padding, 0)
        frame.addView(field)

        val builder =
            AlertDialog.Builder(this)
                .setTitle(title)
                .setView(frame)
                .setNegativeButton(R.string.brain_cancel, null)
                .setPositiveButton(R.string.brain_save) { _, _ ->
                    onSave(field.text.toString())
                }
        if (message.isNotEmpty()) builder.setMessage(message)
        builder.show()
    }

    private fun number(title: Int, current: Int, onSave: (Int) -> Unit) {
        val field = EditText(this)
        field.setText(current.toString())
        field.inputType = InputType.TYPE_CLASS_NUMBER
        field.setSingleLine(true)
        val padding = (resources.displayMetrics.density * 24).toInt()
        val frame = LinearLayout(this)
        frame.orientation = LinearLayout.VERTICAL
        frame.setPadding(padding, padding / 2, padding, 0)
        frame.addView(field)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(frame)
            .setNegativeButton(R.string.brain_cancel, null)
            .setPositiveButton(R.string.brain_save) { _, _ ->
                val value = field.text.toString().toIntOrNull() ?: return@setPositiveButton
                onSave(value)
            }
            .show()
    }
}
