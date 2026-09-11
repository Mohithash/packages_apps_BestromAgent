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

import android.content.Context
import com.bestrom.agent.runner.ToolDispatch
import com.bestrom.agent.runner.ToolSchema
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Maintainer batterystats dump via fixed argv only.
 *
 * Needs DUMP + PACKAGE_USAGE_STATS on the privapp allowlist. Never a shell
 * string; never user text in argv.
 */
object BatterystatsTools {

    private const val TIMEOUT_SEC = 20L

    fun run(@Suppress("UNUSED_PARAMETER") context: Context, call: ToolSchema.ToolCall): ToolDispatch.Outcome =
        when (call.name) {
            ToolSchema.BATTERYSTATS_SNIPPET -> snippet(call.args)
            else -> ToolDispatch.Outcome.Failed(-32602, "unknown batterystats tool", null)
        }

    private fun snippet(args: org.json.JSONObject): ToolDispatch.Outcome {
        BatterystatsLimits.rejectMode(args.optString("mode"))?.let {
            return ToolDispatch.Outcome.Failed(-32602, it, null)
        }
        BatterystatsLimits.rejectFocus(args.optString("focus"))?.let {
            return ToolDispatch.Outcome.Failed(-32602, it, null)
        }
        val mode = BatterystatsLimits.normalizeMode(args.optString("mode"))
        val focus = args.optString("focus")
        val maxBytes = BatterystatsLimits.clampMaxBytes(args.optInt("max_bytes", 0))

        val raw =
            dumpBatterystats(mode)
                ?: return ToolDispatch.Outcome.Failed(
                    -32000,
                    "batterystats unavailable (need DUMP + PACKAGE_USAGE_STATS / priv-app)",
                    null,
                )
        if (BatterystatsLimits.looksLikePermissionDenied(raw)) {
            return ToolDispatch.Outcome.Failed(
                -32000,
                "batterystats refused: DUMP or PACKAGE_USAGE_STATS missing",
                null,
            )
        }

        val filtered = BatterystatsLimits.filterDump(raw, mode, focus.ifEmpty { null })
        val body = LogLimits.redact(LogLimits.trimBytes(filtered, maxBytes))
        return ToolDispatch.Outcome.Ok(
            JSONObject()
                .put("ok", true)
                .put("tool", ToolSchema.BATTERYSTATS_SNIPPET)
                .put("mode", mode)
                .put("focus", focus)
                .put("chars", body.length)
                .put("text", body)
                .put(
                    "note",
                    "Redacted dumpsys batterystats snippet. Argv-only; no shell. " +
                        "Pair with measure_idle_drain for live µA samples.",
                )
        )
    }

    /** Fixed argv from [BatterystatsLimits.argvForMode] only. */
    private fun dumpBatterystats(mode: String): String? {
        val argv = BatterystatsLimits.argvForMode(mode)
        return try {
            val proc =
                ProcessBuilder(argv)
                    .redirectErrorStream(true)
                    .start()
            val finished = proc.waitFor(TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                return null
            }
            // Read up to a hard ceiling before filtering so a huge dump cannot
            // blow the process; filter then trims further.
            val ceiling = BatterystatsLimits.MAX_MAX_BYTES * 4
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (sb.length >= ceiling) break
                    sb.append(line).append('\n')
                }
                if (sb.isEmpty() && proc.exitValue() != 0) return null
                sb.toString()
            }
        } catch (_: Exception) {
            null
        }
    }
}
