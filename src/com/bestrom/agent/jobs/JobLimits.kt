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

package com.bestrom.agent.jobs

/**
 * Bounds for background jobs. Host-testable; no Android.
 */
object JobLimits {

    const val MAX_JOBS = 8
    const val MIN_INTERVAL_MINUTES = 15
    const val MAX_INTERVAL_MINUTES = 60 * 24 // 1 day
    const val MAX_LABEL_CHARS = 80

    const val KIND_IDLE_DRAIN = "idle_drain"
    const val KIND_ERROR_WATCH = "error_watch"

    fun rejectKind(kind: String): String? {
        if (kind != KIND_IDLE_DRAIN && kind != KIND_ERROR_WATCH) {
            return "kind must be idle_drain or error_watch"
        }
        return null
    }

    fun rejectInterval(minutes: Int): String? {
        if (minutes < MIN_INTERVAL_MINUTES || minutes > MAX_INTERVAL_MINUTES) {
            return "interval_minutes must be between $MIN_INTERVAL_MINUTES and $MAX_INTERVAL_MINUTES"
        }
        return null
    }

    fun rejectLabel(label: String): String? {
        if (label.length > MAX_LABEL_CHARS) {
            return "label is longer than $MAX_LABEL_CHARS characters"
        }
        return null
    }
}
