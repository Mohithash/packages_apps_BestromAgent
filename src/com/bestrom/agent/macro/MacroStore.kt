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
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class MacroStep(val tool: String, val args: JSONObject) {
    fun toJson(): JSONObject = JSONObject().put("tool", tool).put("args", args)

    companion object {
        fun fromJson(o: JSONObject): MacroStep? {
            val tool = o.optString("tool")
            if (tool !in MacroLimits.STEP_TOOLS) return null
            val args = o.optJSONObject("args") ?: JSONObject()
            return MacroStep(tool, args)
        }
    }
}

data class MacroEntry(
    val id: String,
    val name: String,
    val trigger: String,
    val intervalMinutes: Int,
    val atUnixMs: Long,
    val nextFireMs: Long,
    val enabled: Boolean,
    val steps: List<MacroStep>,
    val batteryBelowPct: Int = 0,
    /** True after a battery_below fire until level recovers above the threshold. */
    val batteryLatched: Boolean = false,
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (s in steps) arr.put(s.toJson())
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("trigger", trigger)
            .put("interval_minutes", intervalMinutes)
            .put("at_unix_ms", atUnixMs)
            .put("next_fire_ms", nextFireMs)
            .put("enabled", enabled)
            .put("steps", arr)
            .put("battery_below_pct", batteryBelowPct)
            .put("battery_latched", batteryLatched)
    }

    companion object {
        fun fromJson(o: JSONObject): MacroEntry? {
            val id = o.optString("id")
            val name = o.optString("name")
            val trigger = o.optString("trigger")
            if (id.isEmpty() || MacroLimits.rejectName(name) != null) return null
            if (MacroLimits.rejectTrigger(trigger) != null) return null
            val stepsArr = o.optJSONArray("steps") ?: return null
            if (MacroLimits.rejectSteps(stepsArr) != null) return null
            val steps = ArrayList<MacroStep>(stepsArr.length())
            for (i in 0 until stepsArr.length()) {
                steps.add(MacroStep.fromJson(stepsArr.optJSONObject(i) ?: return null) ?: return null)
            }
            return MacroEntry(
                id = id,
                name = name.trim(),
                trigger = trigger,
                intervalMinutes = o.optInt("interval_minutes", 0),
                atUnixMs = o.optLong("at_unix_ms", 0L),
                nextFireMs = o.optLong("next_fire_ms", 0L),
                enabled = o.optBoolean("enabled", true),
                steps = steps,
                batteryBelowPct = o.optInt("battery_below_pct", 0),
                batteryLatched = o.optBoolean("battery_latched", false),
            )
        }
    }
}

class MacroStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "agent_macros.json"))

    @Synchronized
    fun list(): List<MacroEntry> = readAll().sortedBy { it.name.lowercase() }

    @Synchronized
    fun get(id: String): MacroEntry? = readAll().firstOrNull { it.id == id }

    @Synchronized
    fun save(entry: MacroEntry): MacroEntry? {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            all[idx] = entry
        } else {
            if (all.size >= MacroLimits.MAX_MACROS) return null
            all.add(entry)
        }
        writeAll(all)
        return entry
    }

    @Synchronized
    fun addNew(
        name: String,
        trigger: String,
        intervalMinutes: Int,
        atUnixMs: Long,
        steps: List<MacroStep>,
        nowMs: Long,
        batteryBelowPct: Int = 0,
    ): MacroEntry? {
        if (MacroLimits.rejectName(name) != null) return null
        if (MacroLimits.rejectTrigger(trigger) != null) return null
        if (MacroLimits.rejectSchedule(trigger, intervalMinutes, atUnixMs, batteryBelowPct, nowMs) !=
            null
        ) {
            return null
        }
        val all = readAll()
        if (all.size >= MacroLimits.MAX_MACROS) return null
        val next =
            when (trigger) {
                MacroLimits.TRIGGER_INTERVAL -> nowMs + intervalMinutes * 60_000L
                MacroLimits.TRIGGER_ONCE -> atUnixMs
                MacroLimits.TRIGGER_BATTERY_BELOW ->
                    nowMs + MacroLimits.BATTERY_POLL_MINUTES * 60_000L
                else -> 0L // boot: no alarm
            }
        val entry =
            MacroEntry(
                id = UUID.randomUUID().toString().take(8),
                name = name.trim(),
                trigger = trigger,
                intervalMinutes = intervalMinutes,
                atUnixMs = atUnixMs,
                nextFireMs = next,
                enabled = true,
                steps = steps,
                batteryBelowPct = batteryBelowPct,
                batteryLatched = false,
            )
        return save(entry)
    }

    @Synchronized
    fun bumpNext(id: String, nowMs: Long): MacroEntry? {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val cur = all[idx]
        val minutes =
            when (cur.trigger) {
                MacroLimits.TRIGGER_INTERVAL -> cur.intervalMinutes
                MacroLimits.TRIGGER_BATTERY_BELOW -> MacroLimits.BATTERY_POLL_MINUTES
                else -> return cur
            }
        val next = cur.copy(nextFireMs = nowMs + minutes * 60_000L)
        all[idx] = next
        writeAll(all)
        return next
    }

    @Synchronized
    fun setBatteryLatched(id: String, latched: Boolean): MacroEntry? {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val cur = all[idx]
        if (cur.batteryLatched == latched) return cur
        val next = cur.copy(batteryLatched = latched)
        all[idx] = next
        writeAll(all)
        return next
    }

    @Synchronized
    fun remove(id: String): MacroEntry? {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val removed = all.removeAt(idx)
        writeAll(all)
        return removed
    }

    private fun readAll(): List<MacroEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("entries") ?: return emptyList()
            val out = ArrayList<MacroEntry>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(MacroEntry.fromJson(arr.optJSONObject(i) ?: continue) ?: continue)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeAll(entries: List<MacroEntry>) {
        val arr = JSONArray()
        for (e in entries) arr.put(e.toJson())
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("entries", arr).toString())
    }
}
