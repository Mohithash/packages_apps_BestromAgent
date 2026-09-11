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

package com.bestrom.agent.schedule

import org.json.JSONObject

/**
 * Shared timing rules for schedule_reminder / schedule_task.
 *
 * Host-testable: no Android types.
 */
object ReminderTime {

    const val MIN_IN_MINUTES = 1
    const val MAX_IN_MINUTES = 60 * 24 * 30 // 30 days
    const val MAX_TEXT_CHARS = 500
    const val MAX_ENTRIES = 32

    const val KIND_REMINDER = "reminder"
    const val KIND_TASK = "task"

    /**
     * Resolves when the alarm should fire.
     *
     * Prefers [in_minutes] when both are present. [nowMs] is injectable for
     * tests.
     */
    fun fireAtMs(args: JSONObject, nowMs: Long = System.currentTimeMillis()): Long? {
        if (args.has("in_minutes")) {
            val minutes = args.optInt("in_minutes", -1)
            if (minutes < MIN_IN_MINUTES || minutes > MAX_IN_MINUTES) return null
            return nowMs + minutes * 60_000L
        }
        if (args.has("at_unix_ms")) {
            val at = args.optLong("at_unix_ms", -1L)
            if (at < nowMs + MIN_IN_MINUTES * 60_000L) return null
            if (at > nowMs + MAX_IN_MINUTES * 60_000L) return null
            return at
        }
        return null
    }

    fun rejectText(text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return "message must not be empty"
        if (t.length > MAX_TEXT_CHARS) return "text is longer than $MAX_TEXT_CHARS characters"
        return null
    }

    fun rejectWhen(args: JSONObject, nowMs: Long = System.currentTimeMillis()): String? {
        if (!args.has("in_minutes") && !args.has("at_unix_ms")) {
            return "give in_minutes or at_unix_ms"
        }
        if (args.has("in_minutes")) {
            val minutes = args.optInt("in_minutes", -1)
            if (minutes < MIN_IN_MINUTES || minutes > MAX_IN_MINUTES) {
                return "in_minutes must be between $MIN_IN_MINUTES and $MAX_IN_MINUTES"
            }
        }
        if (args.has("at_unix_ms") && !args.has("in_minutes")) {
            val at = args.optLong("at_unix_ms", -1L)
            if (at < nowMs + MIN_IN_MINUTES * 60_000L) {
                return "at_unix_ms must be at least one minute from now"
            }
            if (at > nowMs + MAX_IN_MINUTES * 60_000L) {
                return "at_unix_ms is more than 30 days away"
            }
        }
        return null
    }
}
