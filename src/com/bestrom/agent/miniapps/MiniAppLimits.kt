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

import org.json.JSONArray

/**
 * Bounds for vibecode-style mini apps. Host-testable; no Android.
 *
 * Recipes are templates only (counter / checklist / daily_log / timer) —
 * never generated code, never a separate APK.
 */
object MiniAppLimits {

    const val MAX_RECIPES = 16
    const val MAX_NAME_CHARS = 48
    const val MAX_GOAL_CHARS = 200
    const val MAX_UNIT_CHARS = 24
    const val MAX_CHECKLIST_ITEMS = 24
    const val MAX_ITEM_CHARS = 80
    const val MAX_LOG_LINE_CHARS = 200
    const val MAX_LOG_LINES_PER_DAY = 40
    const val MIN_TIMER_MINUTES = 1
    const val MAX_TIMER_MINUTES = 24 * 60

    const val KIND_COUNTER = "counter"
    const val KIND_CHECKLIST = "checklist"
    const val KIND_DAILY_LOG = "daily_log"
    const val KIND_TIMER = "timer"

    val KINDS: Set<String> =
        setOf(KIND_COUNTER, KIND_CHECKLIST, KIND_DAILY_LOG, KIND_TIMER)

    fun rejectName(name: String): String? {
        val t = name.trim()
        if (t.isEmpty()) return "name must not be empty"
        if (t.length > MAX_NAME_CHARS) return "name is longer than $MAX_NAME_CHARS characters"
        return null
    }

    fun rejectKind(kind: String): String? {
        if (kind !in KINDS) {
            return "kind must be one of: " + KINDS.sorted().joinToString(", ")
        }
        return null
    }

    fun rejectGoal(goal: String?): String? {
        if (goal.isNullOrEmpty()) return null
        if (goal.length > MAX_GOAL_CHARS) return "goal is too long"
        return null
    }

    fun rejectUnit(unit: String?): String? {
        if (unit.isNullOrEmpty()) return null
        if (unit.length > MAX_UNIT_CHARS) return "unit is too long"
        return null
    }

    fun rejectChecklistItems(items: JSONArray?): String? {
        if (items == null || items.length() == 0) {
            return "checklist needs items (JSON array of strings)"
        }
        if (items.length() > MAX_CHECKLIST_ITEMS) {
            return "at most $MAX_CHECKLIST_ITEMS checklist items"
        }
        for (i in 0 until items.length()) {
            val s = items.optString(i).trim()
            if (s.isEmpty()) return "checklist item $i is empty"
            if (s.length > MAX_ITEM_CHARS) return "checklist item $i is too long"
        }
        return null
    }

    fun rejectTimerMinutes(minutes: Int): String? {
        if (minutes < MIN_TIMER_MINUTES || minutes > MAX_TIMER_MINUTES) {
            return "timer_minutes must be between $MIN_TIMER_MINUTES and $MAX_TIMER_MINUTES"
        }
        return null
    }

    fun parseItems(raw: Any?): JSONArray? =
        when (raw) {
            is JSONArray -> raw
            is String ->
                try {
                    JSONArray(raw)
                } catch (_: Exception) {
                    null
                }
            else -> null
        }

    fun rejectCreate(
        name: String,
        kind: String,
        goal: String?,
        unit: String?,
        items: JSONArray?,
        timerMinutes: Int,
    ): String? {
        rejectName(name)?.let {
            return it
        }
        rejectKind(kind)?.let {
            return it
        }
        rejectGoal(goal)?.let {
            return it
        }
        rejectUnit(unit)?.let {
            return it
        }
        when (kind) {
            KIND_CHECKLIST -> rejectChecklistItems(items)?.let {
                return it
            }
            KIND_TIMER -> rejectTimerMinutes(timerMinutes)?.let {
                return it
            }
        }
        return null
    }
}
