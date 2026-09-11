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

package com.bestrom.agent.alert

/**
 * How battery / macro alerts are shown. Host-testable names.
 *
 * dialog falls back to notification when no Activity is in the foreground.
 */
object AlertDelivery {

    const val NOTIFICATION = "notification"
    const val TOAST = "toast"
    const val DIALOG = "dialog"

    val ALL: Set<String> = setOf(NOTIFICATION, TOAST, DIALOG)

    fun normalize(raw: String?): String {
        val v = raw?.trim()?.lowercase().orEmpty()
        return if (v in ALL) v else NOTIFICATION
    }

    fun reject(raw: String?): String? {
        if (raw.isNullOrEmpty()) return null
        if (normalize(raw) !in ALL && raw.trim().lowercase() !in ALL) {
            return "delivery must be notification, toast, or dialog"
        }
        return null
    }

    /**
     * Capability note for the model when the user asks how to alert.
     */
    fun capabilityBrief(): String =
        "Alert delivery options: notification (always works in background), " +
            "toast (brief on-screen, needs a running process), dialog (popup in " +
            "BestROM Agent when open — otherwise falls back to notification). " +
            "Prefer notification for battery_below macros."
}
