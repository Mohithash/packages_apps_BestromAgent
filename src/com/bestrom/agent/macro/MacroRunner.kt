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

package com.bestrom.agent.macro

import android.content.Context
import com.bestrom.agent.AgentState
import com.bestrom.agent.diag.DrainTools
import com.bestrom.agent.diag.LogTools
import com.bestrom.agent.runner.ToolDispatch
import com.bestrom.agent.runner.ToolSchema
import com.bestrom.agent.schedule.ReminderTools
import org.json.JSONObject

/** Executes a macro's steps. Never turns Agent mode on. */
object MacroRunner {

    fun run(context: Context, entry: MacroEntry): String {
        val lines = ArrayList<String>()
        for (step in entry.steps) {
            lines.add(runStep(context, entry, step))
        }
        return lines.joinToString("\n")
    }

    private fun runStep(context: Context, entry: MacroEntry, step: MacroStep): String {
        return when (step.tool) {
            MacroLimits.STEP_MEASURE_IDLE_DRAIN -> {
                val tool = ToolSchema.tool(ToolSchema.MEASURE_IDLE_DRAIN) ?: return "no tool"
                val out = DrainTools.run(context, ToolSchema.ToolCall("m", tool, JSONObject()))
                val r = (out as? ToolDispatch.Outcome.Ok)?.result
                "measure_idle_drain: level=${r?.optInt("level_pct")}%"
            }
            MacroLimits.STEP_CRASH_SCAN -> {
                val tool = ToolSchema.tool(ToolSchema.CRASH_SCAN) ?: return "no tool"
                val args = JSONObject().put("max", step.args.optInt("max", 8))
                val out = LogTools.run(context, ToolSchema.ToolCall("m", tool, args))
                val r = (out as? ToolDispatch.Outcome.Ok)?.result
                "crash_scan: exits=${r?.optInt("count")}"
            }
            MacroLimits.STEP_LOG_GREP -> {
                val tool = ToolSchema.tool(ToolSchema.LOG_GREP) ?: return "no tool"
                val out = LogTools.run(context, ToolSchema.ToolCall("m", tool, step.args))
                val r = (out as? ToolDispatch.Outcome.Ok)?.result
                "log_grep: matched=${r?.optInt("matched")} chars=${r?.optInt("chars")}"
            }
            MacroLimits.STEP_NOTIFY -> {
                val msg = step.args.optString("message").trim().ifEmpty { "(empty notify)" }
                val delivery = step.args.optString("delivery")
                com.bestrom.agent.alert.AlertPoster.post(
                    context,
                    entry.name,
                    msg,
                    delivery = delivery.ifEmpty { null },
                    salt = (entry.id + msg).hashCode(),
                )
                "notify: $msg"
            }
            MacroLimits.STEP_SCHEDULE_REMINDER -> {
                val tool = ToolSchema.tool(ToolSchema.SCHEDULE_REMINDER) ?: return "no tool"
                val out = ReminderTools.run(context, ToolSchema.ToolCall("m", tool, step.args))
                when (out) {
                    is ToolDispatch.Outcome.Ok ->
                        "schedule_reminder: id=${out.result.optString("id")} at=${out.result.optString("fire_at_utc")}"
                    is ToolDispatch.Outcome.Failed -> "schedule_reminder: ${out.text}"
                    else -> "schedule_reminder: failed"
                }
            }
            MacroLimits.STEP_RUN_GOAL -> {
                val goal = step.args.optString("goal").trim()
                val bridge = AgentState.bridge
                if (AgentState.bridgeLive.get() && bridge != null) {
                    val err = bridge.startTask(goal)
                    if (err == null) "run_goal: started"
                    else "run_goal: $err"
                } else {
                    "run_goal: Agent mode off — open Agent to run: $goal"
                }
            }
            else -> "skipped unknown step"
        }
    }
}
