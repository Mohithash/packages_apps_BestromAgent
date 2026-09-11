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

package com.bestrom.agent.miniapps

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.bestrom.agent.AgentPrefs
import com.bestrom.agent.R

/** Simple weight log — part of BestROM Agent, removable in settings. */
class WeightTrackerActivity : Activity() {

    private lateinit var store: WeightStore
    private lateinit var status: TextView
    private lateinit var input: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AgentPrefs.weightEnabled(this)) {
            Toast.makeText(this, R.string.miniapp_disabled, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_weight)
        store = WeightStore(this)
        status = findViewById(R.id.weight_status)
        input = findViewById(R.id.weight_input)
        findViewById<Button>(R.id.weight_save).setOnClickListener {
            val kg = input.text.toString().toFloatOrNull()
            if (kg == null || kg < 20f || kg > 400f) {
                Toast.makeText(this, R.string.weight_invalid, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            store.log(kg)
            refreshWidgets()
            render()
        }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        render()
    }

    private fun render() {
        val latest = store.latest()
        val goal = store.goalKg()
        status.text =
            if (latest == null) getString(R.string.weight_empty)
            else
                getString(
                    R.string.weight_status_fmt,
                    latest.kg,
                    latest.day,
                    goal?.toString() ?: "—",
                )
    }

    private fun refreshWidgets() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, WeightWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            sendBroadcast(
                Intent(this, WeightWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}
