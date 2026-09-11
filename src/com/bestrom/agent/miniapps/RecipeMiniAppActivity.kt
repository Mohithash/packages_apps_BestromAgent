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
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.bestrom.agent.R

/** Renders one vibecode recipe (counter / checklist / daily_log / timer). */
class RecipeMiniAppActivity : Activity() {

    companion object {
        const val EXTRA_ID = "recipe_id"
    }

    private lateinit var recipes: MiniAppRecipeStore
    private lateinit var runtime: MiniAppRuntimeStore
    private lateinit var recipe: MiniAppRecipe
    private lateinit var status: TextView
    private lateinit var body: LinearLayout
    private var logInput: EditText? = null
    private val main = Handler(Looper.getMainLooper())
    private val tick =
        object : Runnable {
            override fun run() {
                if (::recipe.isInitialized && recipe.kind == MiniAppLimits.KIND_TIMER) {
                    renderTimer()
                    main.postDelayed(this, 1000)
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recipe_miniapp)
        recipes = MiniAppRecipeStore(this)
        runtime = MiniAppRuntimeStore(this)
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val found = recipes.get(id)
        if (found == null) {
            Toast.makeText(this, R.string.miniapp_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        recipe = found
        findViewById<TextView>(R.id.recipe_title).text = recipe.name
        findViewById<TextView>(R.id.recipe_goal).text =
            recipe.goal.ifEmpty { getString(R.string.miniapp_kind_fmt, recipe.kind) }
        status = findViewById(R.id.recipe_status)
        body = findViewById(R.id.recipe_body)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }
        buildBody()
        render()
    }

    override fun onResume() {
        super.onResume()
        if (recipe.kind == MiniAppLimits.KIND_TIMER) main.post(tick)
    }

    override fun onPause() {
        main.removeCallbacks(tick)
        super.onPause()
    }

    private fun buildBody() {
        body.removeAllViews()
        when (recipe.kind) {
            MiniAppLimits.KIND_COUNTER -> {
                addButton(R.string.miniapp_plus) {
                    runtime.bumpCounter(recipe.id, 1)
                    refreshWidget()
                    render()
                }
                addButton(R.string.miniapp_minus) {
                    runtime.bumpCounter(recipe.id, -1)
                    refreshWidget()
                    render()
                }
            }
            MiniAppLimits.KIND_CHECKLIST -> {
                val checked = runtime.checklistChecked(recipe.id, recipe)
                for ((i, item) in recipe.items.withIndex()) {
                    val box = CheckBox(this)
                    box.text = item
                    box.isChecked = checked.getOrElse(i) { false }
                    val index = i
                    box.setOnClickListener {
                        runtime.toggleChecklist(recipe.id, recipe, index)
                        val next = runtime.checklistChecked(recipe.id, recipe)
                        box.isChecked = next.getOrElse(index) { false }
                        refreshWidget()
                        render()
                    }
                    body.addView(box)
                }
            }
            MiniAppLimits.KIND_DAILY_LOG -> {
                val input = EditText(this)
                input.hint = getString(R.string.miniapp_log_hint)
                logInput = input
                body.addView(input)
                addButton(R.string.miniapp_log_add) {
                    runtime.appendLog(recipe.id, logInput?.text?.toString().orEmpty())
                    logInput?.setText("")
                    refreshWidget()
                    render()
                }
            }
            MiniAppLimits.KIND_TIMER -> {
                addButton(R.string.miniapp_timer_start) {
                    runtime.startTimer(recipe.id, recipe.timerMinutes)
                    refreshWidget()
                    render()
                }
                addButton(R.string.miniapp_timer_clear) {
                    runtime.clearTimer(recipe.id)
                    refreshWidget()
                    render()
                }
            }
        }
    }

    private fun addButton(label: Int, action: () -> Unit) {
        val b = Button(this)
        b.setText(label)
        b.setOnClickListener { action() }
        body.addView(b)
    }

    private fun render() {
        status.text =
            when (recipe.kind) {
                MiniAppLimits.KIND_COUNTER -> {
                    val unit = recipe.unit.ifEmpty { "count" }
                    getString(
                        R.string.miniapp_counter_fmt,
                        runtime.counterValue(recipe.id),
                        unit,
                    )
                }
                MiniAppLimits.KIND_CHECKLIST -> {
                    val c = runtime.checklistChecked(recipe.id, recipe)
                    getString(R.string.miniapp_check_fmt, c.count { it }, c.size)
                }
                MiniAppLimits.KIND_DAILY_LOG -> {
                    val lines = runtime.todayLog(recipe.id)
                    if (lines.isEmpty()) getString(R.string.miniapp_log_empty)
                    else lines.joinToString("\n") { "• $it" }
                }
                MiniAppLimits.KIND_TIMER -> timerText()
                else -> recipe.kind
            }
    }

    private fun renderTimer() {
        status.text = timerText()
    }

    private fun timerText(): String {
        val ends = runtime.timerEndsAtMs(recipe.id)
        if (ends <= 0L) {
            return getString(R.string.miniapp_timer_idle, recipe.timerMinutes)
        }
        val left = ends - System.currentTimeMillis()
        if (left <= 0) return getString(R.string.miniapp_timer_done)
        val sec = left / 1000
        return getString(R.string.miniapp_timer_left, sec / 60, sec % 60)
    }

    private fun refreshWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        val ids = mgr.getAppWidgetIds(ComponentName(this, RecipeWidgetProvider::class.java))
        if (ids.isEmpty()) return
        sendBroadcast(
            Intent(this, RecipeWidgetProvider::class.java)
                .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
        )
    }
}
