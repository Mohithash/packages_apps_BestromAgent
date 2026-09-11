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
 * Bounds and redaction for maintainer log tools. Host-testable; no Android.
 *
 * The binary is always argv-only (`logcat -d …`); never a shell string. User
 * patterns are applied in-process after the dump is read.
 */
object LogLimits {

    const val DEFAULT_LINES = 120
    const val MIN_LINES = 20
    const val MAX_LINES = 400
    const val MAX_BYTES = 80_000
    const val MAX_TAG_CHARS = 64
    const val MAX_PATTERN_CHARS = 64
    const val MAX_CRASH_ENTRIES = 16

    private val TAG_OK = Regex("^[A-Za-z0-9_./+:-]{1,$MAX_TAG_CHARS}$")

    /** Secrets that must never leave the device in a tool result. */
    private val REDACT =
        listOf(
            Regex("""(?i)(authorization\s*[:=]\s*)(\S+)"""),
            Regex("""(?i)(bearer\s+)(\S+)"""),
            Regex("""(?i)(api[_-]?key\s*[:=]\s*)(\S+)"""),
            Regex("""(?i)(x-api-key\s*[:=]\s*)(\S+)"""),
            Regex("""\b(sk-[A-Za-z0-9]{8,})\b"""),
            Regex("""\b(AIza[0-9A-Za-z_-]{20,})\b"""),
            Regex("""(?i)(password\s*[:=]\s*)(\S+)"""),
        )

    fun rejectTag(tag: String?): String? {
        if (tag.isNullOrEmpty()) return null
        if (tag.length > MAX_TAG_CHARS) return "tag is longer than $MAX_TAG_CHARS characters"
        if (!TAG_OK.matches(tag)) return "tag has illegal characters"
        return null
    }

    fun rejectPattern(pattern: String?): String? {
        if (pattern.isNullOrEmpty()) return "pattern must not be empty"
        if (pattern.length > MAX_PATTERN_CHARS) {
            return "pattern is longer than $MAX_PATTERN_CHARS characters"
        }
        return null
    }

    fun clampLines(lines: Int): Int =
        when {
            lines <= 0 -> DEFAULT_LINES
            else -> lines.coerceIn(MIN_LINES, MAX_LINES)
        }

    fun redact(text: String): String {
        var out = text
        for (re in REDACT) {
            out =
                re.replace(out) { m ->
                    // Two capture groups: keep the label, hide the secret.
                    if (m.groups.size >= 3) m.groupValues[1] + "[redacted]"
                    else "[redacted]"
                }
        }
        return out
    }

    /** Keep at most [maxBytes] UTF-8-ish chars from the end (newest log lines). */
    fun trimBytes(text: String, maxBytes: Int = MAX_BYTES): String {
        if (text.length <= maxBytes) return text
        return "…[truncated]\n" + text.substring(text.length - maxBytes)
    }
}
