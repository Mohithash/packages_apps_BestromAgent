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

import android.content.Context
import com.bestrom.agent.diag.DrainTools
import com.bestrom.agent.diag.LogTools
import com.bestrom.agent.runner.ToolDispatch
import com.bestrom.agent.runner.ToolSchema
import org.json.JSONObject

/** Runs one background job tick and returns a short notification line. */
object JobRunner {

    fun run(context: Context, entry: JobEntry): String {
        return when (entry.kind) {
            JobLimits.KIND_IDLE_DRAIN -> {
                val call =
                    ToolSchema.ToolCall(
                        "job",
                        ToolSchema.tool(ToolSchema.MEASURE_IDLE_DRAIN)!!,
                        JSONObject(),
                    )
                val out = DrainTools.run(context, call)
                summarizeDrain(out)
            }
            JobLimits.KIND_ERROR_WATCH -> {
                val call =
                    ToolSchema.ToolCall(
                        "job",
                        ToolSchema.tool(ToolSchema.CRASH_SCAN)!!,
                        JSONObject().put("max", 8),
                    )
                val out = LogTools.run(context, call)
                summarizeCrash(out)
            }
            else -> "unknown job kind"
        }
    }

    private fun summarizeDrain(out: ToolDispatch.Outcome): String {
        val r = (out as? ToolDispatch.Outcome.Ok)?.result ?: return "idle_drain failed"
        val level = r.optInt("level_pct")
        val cur = r.optLong("current_now_ua")
        val avg =
            if (r.has("estimated_avg_drain_ma")) {
                " avg_ma=" + String.format("%.1f", r.optDouble("estimated_avg_drain_ma"))
            } else if (r.optBoolean("first_sample")) {
                " (first sample)"
            } else {
                ""
            }
        return "Idle drain: $level% current_ua=$cur$avg"
    }

    private fun summarizeCrash(out: ToolDispatch.Outcome): String {
        val r = (out as? ToolDispatch.Outcome.Ok)?.result ?: return "error_watch failed"
        val n = r.optInt("count")
        return if (n == 0) "Error watch: no recent exits"
        else "Error watch: $n recent process exit(s) — open Agent for details"
    }
}
