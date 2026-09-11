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
import com.bestrom.agent.runner.ToolDispatch
import com.bestrom.agent.runner.ToolSchema
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object MacroTools {

    fun run(context: Context, call: ToolSchema.ToolCall): ToolDispatch.Outcome =
        when (call.name) {
            ToolSchema.SAVE_MACRO -> save(context, call.args)
            ToolSchema.DELETE_MACRO -> delete(context, call.args.optString("id"))
            ToolSchema.LIST_MACROS -> list(context)
            ToolSchema.RUN_MACRO -> runNow(context, call.args.optString("id"))
            else -> ToolDispatch.Outcome.Failed(-32602, "unknown macro tool", null)
        }

    private fun save(context: Context, args: JSONObject): ToolDispatch.Outcome {
        val name = args.optString("name")
        val trigger = args.optString("trigger")
        val interval = args.optInt("interval_minutes", 0)
        val at = args.optLong("at_unix_ms", 0L)
        val batteryBelow = args.optInt("battery_below_pct", 0)
        val stepsRaw = MacroLimits.parseSteps(args.opt("steps"))
        MacroLimits.rejectName(name)?.let {
            return ToolDispatch.Outcome.Failed(-32602, it, null)
        }
        MacroLimits.rejectTrigger(trigger)?.let {
            return ToolDispatch.Outcome.Failed(-32602, it, null)
        }
        MacroLimits.rejectSteps(stepsRaw)?.let {
            return ToolDispatch.Outcome.Failed(-32602, it, null)
        }
        val now = System.currentTimeMillis()
        MacroLimits.rejectSchedule(trigger, interval, at, batteryBelow, now)?.let {
            return ToolDispatch.Outcome.Failed(-32602, it, null)
        }
        val steps = ArrayList<MacroStep>()
        for (i in 0 until stepsRaw!!.length()) {
            steps.add(MacroStep.fromJson(stepsRaw.getJSONObject(i))!!)
        }
        val store = MacroStore(context)
        val entry =
            store.addNew(name, trigger, interval, at, steps, now, batteryBelow)
                ?: return ToolDispatch.Outcome.Failed(
                    -32602,
                    "could not save macro (full; max ${MacroLimits.MAX_MACROS})",
                    null,
                )
        MacroScheduler.cancel(context, entry.id)
        MacroScheduler.arm(context, entry)
        return ToolDispatch.Outcome.Ok(
            JSONObject()
                .put("ok", true)
                .put("id", entry.id)
                .put("name", entry.name)
                .put("trigger", entry.trigger)
                .put("battery_below_pct", entry.batteryBelowPct)
                .put("next_fire_ms", entry.nextFireMs)
                .put("next_fire_utc", if (entry.nextFireMs > 0) utc(entry.nextFireMs) else "")
                .put("steps", entry.steps.size)
                .put(
                    "note",
                    "Macro never turns Agent mode on. run_goal steps need Agent already on. " +
                        "battery_below fires once per dip below the threshold.",
                )
        )
    }

    private fun delete(context: Context, id: String): ToolDispatch.Outcome {
        if (id.isEmpty()) {
            return ToolDispatch.Outcome.Failed(-32602, "id must not be empty", null)
        }
        val removed =
            MacroStore(context).remove(id)
                ?: return ToolDispatch.Outcome.Failed(-32602, "no macro with that id", null)
        MacroScheduler.cancel(context, id)
        return ToolDispatch.Outcome.Ok(
            JSONObject().put("ok", true).put("id", removed.id).put("deleted", true)
        )
    }

    private fun list(context: Context): ToolDispatch.Outcome {
        val arr = JSONArray()
        for (e in MacroStore(context).list()) {
            val steps = JSONArray()
            for (s in e.steps) steps.put(s.toJson())
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("name", e.name)
                    .put("trigger", e.trigger)
                    .put("interval_minutes", e.intervalMinutes)
                    .put("battery_below_pct", e.batteryBelowPct)
                    .put("battery_latched", e.batteryLatched)
                    .put("next_fire_ms", e.nextFireMs)
                    .put("enabled", e.enabled)
                    .put("steps", steps)
            )
        }
        return ToolDispatch.Outcome.Ok(JSONObject().put("macros", arr).put("count", arr.length()))
    }

    private fun runNow(context: Context, id: String): ToolDispatch.Outcome {
        if (id.isEmpty()) {
            return ToolDispatch.Outcome.Failed(-32602, "id must not be empty", null)
        }
        val entry =
            MacroStore(context).get(id)
                ?: return ToolDispatch.Outcome.Failed(-32602, "no macro with that id", null)
        val result = MacroRunner.run(context, entry)
        return ToolDispatch.Outcome.Ok(
            JSONObject().put("ok", true).put("id", entry.id).put("result", result)
        )
    }

    private fun utc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }
}
