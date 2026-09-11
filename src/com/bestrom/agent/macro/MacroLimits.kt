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

import org.json.JSONArray
import org.json.JSONObject

/**
 * Bounds and step whitelist for macros. Host-testable; no Android.
 *
 * Macros never get tap/type/launch — only local diag, notify, schedule_reminder,
 * or a goal string that starts a task when Agent mode is already on.
 */
object MacroLimits {

    const val MAX_MACROS = 16
    const val MAX_STEPS = 8
    const val MAX_NAME_CHARS = 80
    const val MAX_GOAL_CHARS = 500
    const val MAX_NOTIFY_CHARS = 500
    const val MIN_INTERVAL_MINUTES = 15
    const val MAX_INTERVAL_MINUTES = 60 * 24 * 7
    const val MIN_IN_MINUTES_ONCE = 1
    const val MIN_BATTERY_BELOW_PCT = 5
    const val MAX_BATTERY_BELOW_PCT = 95
    /** How often battery_below macros re-check level while armed. */
    const val BATTERY_POLL_MINUTES = 15

    const val TRIGGER_INTERVAL = "interval"
    const val TRIGGER_ONCE = "once"
    const val TRIGGER_BOOT = "boot"
    const val TRIGGER_BATTERY_BELOW = "battery_below"

    const val STEP_MEASURE_IDLE_DRAIN = "measure_idle_drain"
    const val STEP_CRASH_SCAN = "crash_scan"
    const val STEP_LOG_GREP = "log_grep"
    const val STEP_NOTIFY = "notify"
    const val STEP_RUN_GOAL = "run_goal"
    const val STEP_SCHEDULE_REMINDER = "schedule_reminder"

    val STEP_TOOLS: Set<String> =
        setOf(
            STEP_MEASURE_IDLE_DRAIN,
            STEP_CRASH_SCAN,
            STEP_LOG_GREP,
            STEP_NOTIFY,
            STEP_RUN_GOAL,
            STEP_SCHEDULE_REMINDER,
        )

    fun rejectName(name: String): String? {
        val t = name.trim()
        if (t.isEmpty()) return "name must not be empty"
        if (t.length > MAX_NAME_CHARS) return "name is longer than $MAX_NAME_CHARS characters"
        return null
    }

    fun rejectTrigger(trigger: String): String? {
        if (trigger != TRIGGER_INTERVAL &&
            trigger != TRIGGER_ONCE &&
            trigger != TRIGGER_BOOT &&
            trigger != TRIGGER_BATTERY_BELOW
        ) {
            return "trigger must be interval, once, boot, or battery_below"
        }
        return null
    }

    fun rejectSteps(steps: JSONArray?): String? {
        if (steps == null || steps.length() == 0) return "steps must not be empty"
        if (steps.length() > MAX_STEPS) return "at most $MAX_STEPS steps"
        for (i in 0 until steps.length()) {
            val o = steps.optJSONObject(i) ?: return "step $i must be an object"
            val tool = o.optString("tool")
            if (tool !in STEP_TOOLS) {
                return "step tool must be one of: " + STEP_TOOLS.joinToString(", ")
            }
            val args = o.optJSONObject("args") ?: JSONObject()
            when (tool) {
                STEP_LOG_GREP -> {
                    val p = args.optString("pattern")
                    if (p.isEmpty()) return "log_grep needs args.pattern"
                }
                STEP_NOTIFY -> {
                    val m = args.optString("message").trim()
                    if (m.isEmpty()) return "notify needs args.message"
                    if (m.length > MAX_NOTIFY_CHARS) return "notify message too long"
                    val d = args.optString("delivery")
                    if (d.isNotEmpty()) {
                        com.bestrom.agent.alert.AlertDelivery.reject(d)?.let {
                            return it
                        }
                    }
                }
                STEP_RUN_GOAL -> {
                    val g = args.optString("goal").trim()
                    if (g.isEmpty()) return "run_goal needs args.goal"
                    if (g.length > MAX_GOAL_CHARS) return "run_goal text too long"
                }
                STEP_SCHEDULE_REMINDER -> {
                    val m = args.optString("message").trim()
                    if (m.isEmpty()) return "schedule_reminder needs args.message"
                    if (m.length > MAX_NOTIFY_CHARS) return "schedule_reminder message too long"
                    if (!args.has("in_minutes") && !args.has("at_unix_ms")) {
                        return "schedule_reminder needs args.in_minutes or args.at_unix_ms"
                    }
                    if (args.has("in_minutes")) {
                        val minutes = args.optInt("in_minutes", -1)
                        if (minutes < MIN_IN_MINUTES_ONCE || minutes > MAX_INTERVAL_MINUTES) {
                            return "schedule_reminder in_minutes out of range"
                        }
                    }
                }
            }
        }
        return null
    }

    fun rejectSchedule(
        trigger: String,
        intervalMinutes: Int,
        atUnixMs: Long,
        batteryBelowPct: Int = 0,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        when (trigger) {
            TRIGGER_INTERVAL -> {
                if (intervalMinutes < MIN_INTERVAL_MINUTES ||
                    intervalMinutes > MAX_INTERVAL_MINUTES
                ) {
                    return "interval_minutes must be between $MIN_INTERVAL_MINUTES and $MAX_INTERVAL_MINUTES"
                }
            }
            TRIGGER_ONCE -> {
                if (atUnixMs < nowMs + MIN_IN_MINUTES_ONCE * 60_000L) {
                    return "at_unix_ms must be at least one minute from now"
                }
                if (atUnixMs > nowMs + MAX_INTERVAL_MINUTES * 60_000L) {
                    return "at_unix_ms is too far away"
                }
            }
            TRIGGER_BATTERY_BELOW -> {
                if (batteryBelowPct < MIN_BATTERY_BELOW_PCT ||
                    batteryBelowPct > MAX_BATTERY_BELOW_PCT
                ) {
                    return "battery_below_pct must be between $MIN_BATTERY_BELOW_PCT and $MAX_BATTERY_BELOW_PCT"
                }
            }
            TRIGGER_BOOT -> {}
        }
        return null
    }

    /** Normalize a steps array from a tool arg (string or JSONArray). */
    fun parseSteps(raw: Any?): JSONArray? {
        return when (raw) {
            is JSONArray -> raw
            is String ->
                try {
                    JSONArray(raw)
                } catch (_: Exception) {
                    null
                }
            else -> null
        }
    }
}
