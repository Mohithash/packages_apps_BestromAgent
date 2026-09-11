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
import android.widget.TextView
import android.widget.Toast
import com.bestrom.agent.AgentPrefs
import com.bestrom.agent.R

/** Simple daily water tracker — part of BestROM Agent, removable in settings. */
class WaterTrackerActivity : Activity() {

    private lateinit var store: WaterStore
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AgentPrefs.waterEnabled(this)) {
            Toast.makeText(this, R.string.miniapp_disabled, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContentView(R.layout.activity_water)
        store = WaterStore(this)
        status = findViewById(R.id.water_status)
        findViewById<Button>(R.id.water_add).setOnClickListener {
            store.addCup(1)
            refreshWidgets()
            render()
        }
        findViewById<Button>(R.id.water_undo).setOnClickListener {
            store.addCup(-1)
            refreshWidgets()
            render()
        }
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        render()
    }

    private fun render() {
        val cups = store.todayCups()
        val goal = store.goal()
        status.text = getString(R.string.water_status_fmt, cups, goal)
    }

    private fun refreshWidgets() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, WaterWidgetProvider::class.java))
        if (ids.isNotEmpty()) {
            sendBroadcast(
                Intent(this, WaterWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
            )
        }
    }
}
