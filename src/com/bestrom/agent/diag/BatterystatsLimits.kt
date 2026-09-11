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

package com.bestrom.agent.diag

/**
 * Bounds for batterystats_snippet. Host-testable; no Android.
 *
 * dumpsys is always invoked with a fixed argv list chosen from [MODE_*].
 * Optional [focus] only filters lines in-process after the dump.
 */
object BatterystatsLimits {

    const val DEFAULT_MAX_BYTES = 48_000
    const val MIN_MAX_BYTES = 8_000
    const val MAX_MAX_BYTES = 80_000

    /** Full human dump (trimmed). Fixed argv: dumpsys batterystats */
    const val MODE_FULL = "full"

    /** Compact checkin form. Fixed argv: dumpsys batterystats -c */
    const val MODE_CHECKIN = "checkin"

    val MODES: Set<String> = setOf(MODE_FULL, MODE_CHECKIN)

    /**
     * In-process line filters (substring match, case-insensitive). Empty = keep
     * a curated summary of interesting headings when mode=full.
     */
    val FOCUS: Set<String> =
        setOf(
            "summary",
            "discharge",
            "screen",
            "wifi",
            "bluetooth",
            "cpu",
            "uid",
            "wakelock",
            "idle",
        )

    /** Headings kept when focus is empty / "summary" on a full dump. */
    val SUMMARY_NEEDLES: List<String> =
        listOf(
            "capacity:",
            "discharge:",
            "screen on:",
            "screen off:",
            "estimated power use",
            "amount discharged",
            "time on battery",
            "total run time",
            "idle mode",
            "wifi:",
            "bluetooth:",
            "cellular:",
            "gps:",
            "camera:",
            "flashlight:",
            "audio:",
            "video:",
            "cpu:",
            "wake lock",
            "uid u0a",
            "uid 1000",
            "uid 0:",
        )

    fun rejectMode(mode: String?): String? {
        val m = mode?.trim().orEmpty().ifEmpty { MODE_FULL }
        if (m !in MODES) return "mode must be full or checkin"
        return null
    }

    fun normalizeMode(mode: String?): String {
        val m = mode?.trim().orEmpty().ifEmpty { MODE_FULL }
        return if (m in MODES) m else MODE_FULL
    }

    fun rejectFocus(focus: String?): String? {
        if (focus.isNullOrEmpty()) return null
        val f = focus.trim().lowercase()
        if (f !in FOCUS) {
            return "focus must be one of: " + FOCUS.sorted().joinToString(", ")
        }
        return null
    }

    fun clampMaxBytes(raw: Int): Int =
        when {
            raw <= 0 -> DEFAULT_MAX_BYTES
            else -> raw.coerceIn(MIN_MAX_BYTES, MAX_MAX_BYTES)
        }

    /**
     * Fixed argv for ProcessBuilder. Never takes free-form user strings.
     */
    fun argvForMode(mode: String): List<String> =
        when (normalizeMode(mode)) {
            MODE_CHECKIN -> listOf("dumpsys", "batterystats", "-c")
            else -> listOf("dumpsys", "batterystats")
        }

    /**
     * Filter dump text in-process. [focus] empty or "summary" → summary needles
     * for full dumps; checkin mode returns the dump trimmed as-is.
     */
    fun filterDump(raw: String, mode: String, focus: String?): String {
        val m = normalizeMode(mode)
        if (m == MODE_CHECKIN) return raw
        val f = focus?.trim()?.lowercase().orEmpty()
        if (f.isEmpty() || f == "summary") {
            return filterByNeedles(raw, SUMMARY_NEEDLES)
        }
        return filterByNeedles(raw, listOf(f))
    }

    private fun filterByNeedles(raw: String, needles: List<String>): String {
        val lines = raw.lineSequence()
        val kept = ArrayList<String>()
        for (line in lines) {
            val lower = line.lowercase()
            if (needles.any { lower.contains(it) }) {
                kept.add(line)
            }
        }
        if (kept.isEmpty()) {
            // Fall back to head of dump so the maintainer still sees something.
            return raw.lineSequence().take(80).joinToString("\n")
        }
        return kept.joinToString("\n")
    }

    fun looksLikePermissionDenied(text: String): Boolean {
        val lower = text.lowercase()
        return lower.contains("permission denial") ||
            lower.contains("does not have permission") ||
            lower.contains("requires android.permission.dump") ||
            lower.contains("requires android.permission.package_usage_stats")
    }
}
