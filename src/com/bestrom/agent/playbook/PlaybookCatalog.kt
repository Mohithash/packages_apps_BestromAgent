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

package com.bestrom.agent.playbook

/**
 * Curated goal templates for driving other apps. Host-testable; no Android.
 *
 * Not a second scripting language: each playbook expands to one goal string
 * the existing AgentRunner loop executes with tap/type/launch/call_function.
 * Payment / checkout taps stay ALWAYS_CONFIRM in PolicyEngine.
 */
object PlaybookCatalog {

    const val MAX_DETAIL_CHARS = 200
    const val MAX_ID_CHARS = 40

    data class Playbook(
        val id: String,
        val title: String,
        /** Goal text. May include `{detail}` when [needsDetail] is true. */
        val goal: String,
        val needsDetail: Boolean = false,
        /**
         * Optional package to name in the expanded goal so PolicyEngine's
         * "named apps" gate unlocks launch for that package.
         */
        val packageHint: String? = null,
    )

    val ALL: List<Playbook> =
        listOf(
            Playbook(
                id = "settings_display",
                title = "Open Display settings",
                goal =
                    "Open Settings (com.android.settings) and find Display. " +
                        "Stop when Display settings are on screen. Do not change anything " +
                        "unless the user asked for a specific change.",
                packageHint = "com.android.settings",
            ),
            Playbook(
                id = "wifi_cycle",
                title = "Cycle Wi‑Fi",
                goal =
                    "Turn Wi‑Fi off, wait about 3 seconds, turn it back on. " +
                        "Use Settings or Quick Settings. Confirm the final state is on.",
            ),
            Playbook(
                id = "battery_idle_sample",
                title = "Sample idle drain",
                goal =
                    "Call measure_idle_drain now, then tell the user to leave the phone " +
                        "idle with the screen off for a few minutes and ask them to say " +
                        "when to sample again. When they continue, call measure_idle_drain " +
                        "again and report the delta. Not full batterystats.",
            ),
            Playbook(
                id = "crash_brief",
                title = "Maintainer crash brief",
                goal =
                    "Using crash_scan, log_grep, and batterystats_snippet if useful, produce a " +
                        "short maintainer brief of recent crashes or FATAL/ANR lines and any " +
                        "obvious battery drain. Redacted. Empty tombstones on a user build are " +
                        "not proof of no crashes — say so.",
            ),
            Playbook(
                id = "order_food",
                title = "Order food (stop before pay)",
                goal =
                    "Help order food: {detail}. Open the food-delivery or restaurant app " +
                        "the user named (or the one already on screen). Search or browse, " +
                        "add items to the cart, and stop at checkout. Never tap Place order, " +
                        "Buy now, Pay, or Confirm purchase — wait for the user to confirm " +
                        "each payment step on the phone. Do not enter card numbers or CVV.",
                needsDetail = true,
            ),
            Playbook(
                id = "maps_navigate",
                title = "Navigate somewhere",
                goal =
                    "Open Maps or Google Maps and start navigation to: {detail}. " +
                        "Prefer package com.google.android.apps.maps if installed. " +
                        "Do not change payment or subscription settings.",
                needsDetail = true,
                packageHint = "com.google.android.apps.maps",
            ),
            Playbook(
                id = "set_alarm",
                title = "Set an alarm",
                goal =
                    "Open Clock and set an alarm for: {detail}. Confirm the alarm is saved. " +
                        "Prefer package com.android.deskclock or com.google.android.deskclock.",
                needsDetail = true,
            ),
            Playbook(
                id = "share_screen_summary",
                title = "Summarize this screen",
                goal =
                    "Describe what is on screen right now in plain language. " +
                        "Do not tap anything. Never copy passwords, OTPs, or card numbers.",
            ),
        )

    private val BY_ID: Map<String, Playbook> = ALL.associateBy { it.id }

    fun get(id: String): Playbook? = BY_ID[id]

    fun rejectId(id: String): String? {
        if (id.isEmpty()) return "id must not be empty"
        if (id.length > MAX_ID_CHARS) return "id is too long"
        if (get(id) == null) return "unknown playbook id"
        return null
    }

    fun rejectDetail(playbook: Playbook, detail: String): String? {
        val t = detail.trim()
        if (playbook.needsDetail && t.isEmpty()) {
            return "this playbook needs detail (what to order, where to go, …)"
        }
        if (t.length > MAX_DETAIL_CHARS) {
            return "detail is longer than $MAX_DETAIL_CHARS characters"
        }
        return null
    }

    /**
     * Expand a playbook into a single goal string. [detail] replaces `{detail}`.
     */
    fun expand(id: String, detail: String = ""): String? {
        val p = get(id) ?: return null
        if (rejectDetail(p, detail) != null) return null
        var goal = p.goal.replace("{detail}", detail.trim().ifEmpty { "(see user message)" })
        val hint = p.packageHint
        if (!hint.isNullOrEmpty() && !goal.contains(hint)) {
            goal += " Prefer launching package $hint if it is installed."
        }
        return goal
    }

    fun ids(): List<String> = ALL.map { it.id }
}
