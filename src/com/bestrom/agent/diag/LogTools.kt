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

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.bestrom.agent.runner.ToolDispatch
import com.bestrom.agent.runner.ToolSchema
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Maintainer diagnostics: bounded logcat dump and recent process exits.
 *
 * logcat is invoked with a fixed argv list only. Filters and grep run in this
 * process. Results are redacted and size-capped before they reach the model.
 */
object LogTools {

    private const val TIMEOUT_SEC = 8L

    fun run(context: Context, call: ToolSchema.ToolCall): ToolDispatch.Outcome =
        when (call.name) {
            ToolSchema.LOG_TAIL -> logTail(call.args.optInt("lines"), call.args.optString("tag"))
            ToolSchema.LOG_GREP ->
                logGrep(
                    call.args.optString("pattern"),
                    call.args.optInt("lines"),
                    call.args.optString("tag"),
                )
            ToolSchema.CRASH_SCAN -> crashScan(context, call.args.optInt("max"))
            else -> ToolDispatch.Outcome.Failed(-32602, "unknown log tool", null)
        }

    private fun logTail(linesRaw: Int, tag: String): ToolDispatch.Outcome {
        val err = LogLimits.rejectTag(tag)
        if (err != null) return ToolDispatch.Outcome.Failed(-32602, err, null)
        val lines = LogLimits.clampLines(linesRaw)
        val text = dumpLogcat(lines, tag.ifEmpty { null })
            ?: return ToolDispatch.Outcome.Failed(
                -32000,
                "logcat unavailable (need READ_LOGS / priv-app)",
                null,
            )
        return okLog("log_tail", lines, tag, text, matched = null)
    }

    private fun logGrep(pattern: String, linesRaw: Int, tag: String): ToolDispatch.Outcome {
        val perr = LogLimits.rejectPattern(pattern)
        if (perr != null) return ToolDispatch.Outcome.Failed(-32602, perr, null)
        val terr = LogLimits.rejectTag(tag)
        if (terr != null) return ToolDispatch.Outcome.Failed(-32602, terr, null)
        val lines = LogLimits.clampLines(linesRaw)
        val dump =
            dumpLogcat(lines.coerceAtLeast(200), tag.ifEmpty { null })
                ?: return ToolDispatch.Outcome.Failed(
                    -32000,
                    "logcat unavailable (need READ_LOGS / priv-app)",
                    null,
                )
        val needle = pattern.lowercase()
        val hits =
            dump.lineSequence().filter { it.lowercase().contains(needle) }.take(lines).toList()
        val text = hits.joinToString("\n")
        return okLog("log_grep", lines, tag, text, matched = hits.size)
    }

    private fun okLog(
        tool: String,
        lines: Int,
        tag: String,
        raw: String,
        matched: Int?,
    ): ToolDispatch.Outcome {
        val body = LogLimits.redact(LogLimits.trimBytes(raw))
        val out =
            JSONObject()
                .put("ok", true)
                .put("tool", tool)
                .put("lines_requested", lines)
                .put("chars", body.length)
                .put("text", body)
        if (tag.isNotEmpty()) out.put("tag", tag)
        if (matched != null) out.put("matched", matched)
        out.put(
            "note",
            "Redacted maintainer brief. Empty /data/tombstones on user builds is not evidence of no crashes.",
        )
        return ToolDispatch.Outcome.Ok(out)
    }

    /**
     * Fixed argv only. Never concatenates user input into a shell command.
     */
    private fun dumpLogcat(lines: Int, tag: String?): String? {
        val argv = ArrayList<String>(8)
        argv.add("logcat")
        argv.add("-d")
        argv.add("-v")
        argv.add("threadtime")
        argv.add("-t")
        argv.add(lines.toString())
        if (tag != null) {
            argv.add("-s")
            argv.add("$tag:V")
        }
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
            BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
        } catch (_: Exception) {
            null
        }
    }

    private fun crashScan(context: Context, maxRaw: Int): ToolDispatch.Outcome {
        val max =
            when {
                maxRaw <= 0 -> LogLimits.MAX_CRASH_ENTRIES
                else -> maxRaw.coerceIn(1, LogLimits.MAX_CRASH_ENTRIES)
            }
        val am = context.getSystemService(ActivityManager::class.java)
            ?: return ToolDispatch.Outcome.Failed(-32000, "ActivityManager missing", null)
        if (Build.VERSION.SDK_INT < 30) {
            return ToolDispatch.Outcome.Ok(
                JSONObject()
                    .put("ok", true)
                    .put("exits", JSONArray())
                    .put("count", 0)
                    .put("note", "getHistoricalProcessExitReasons needs API 30+")
            )
        }
        val exits: List<ApplicationExitInfo> =
            try {
                am.getHistoricalProcessExitReasons(null, 0, max)
            } catch (e: SecurityException) {
                return ToolDispatch.Outcome.Failed(
                    -32000,
                    "crash_scan refused: " + (e.message ?: "security"),
                    null,
                )
            }
        val arr = JSONArray()
        for (e in exits) {
            arr.put(
                JSONObject()
                    .put("package", e.packageName ?: "")
                    .put("pid", e.pid)
                    .put("reason", reasonName(e.reason))
                    .put("status", e.status)
                    .put("timestamp_ms", e.timestamp)
                    .put("description", LogLimits.redact(e.description ?: "").take(200))
            )
        }
        return ToolDispatch.Outcome.Ok(
            JSONObject()
                .put("ok", true)
                .put("exits", arr)
                .put("count", arr.length())
                .put(
                    "note",
                    "Process exit history. Tombstones under /data are empty without adb root.",
                )
        )
    }

    private fun reasonName(reason: Int): String =
        when (reason) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "exit_self"
            ApplicationExitInfo.REASON_SIGNALED -> "signaled"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low_memory"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash_native"
            ApplicationExitInfo.REASON_ANR -> "anr"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init_failure"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission_change"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "resource"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user"
            ApplicationExitInfo.REASON_USER_STOPPED -> "user_stopped"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency"
            ApplicationExitInfo.REASON_OTHER -> "other"
            ApplicationExitInfo.REASON_FREEZER -> "freezer"
            else -> "reason_$reason"
        }
}
