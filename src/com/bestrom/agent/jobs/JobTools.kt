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
import com.bestrom.agent.runner.ToolDispatch
import com.bestrom.agent.runner.ToolSchema
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object JobTools {

    fun run(context: Context, call: ToolSchema.ToolCall): ToolDispatch.Outcome =
        when (call.name) {
            ToolSchema.START_JOB -> start(context, call.args)
            ToolSchema.STOP_JOB -> stop(context, call.args.optString("id"))
            ToolSchema.LIST_JOBS -> list(context)
            else -> ToolDispatch.Outcome.Failed(-32602, "unknown job tool", null)
        }

    private fun start(context: Context, args: JSONObject): ToolDispatch.Outcome {
        val kind = args.optString("kind")
        val interval = args.optInt("interval_minutes", -1)
        val label = args.optString("label", "")
        JobLimits.rejectKind(kind)?.let {
            return ToolDispatch.Outcome.Failed(-32602, it, null)
        }
        JobLimits.rejectInterval(interval)?.let {
            return ToolDispatch.Outcome.Failed(-32602, it, null)
        }
        val store = JobStore(context)
        for (oldId in store.idsOfKind(kind)) {
            JobScheduler.cancel(context, oldId)
        }
        val entry =
            store.add(kind, interval, label, System.currentTimeMillis())
                ?: return ToolDispatch.Outcome.Failed(
                    -32602,
                    "could not start job (full; max ${JobLimits.MAX_JOBS})",
                    null,
                )
        JobScheduler.arm(context, entry)
        return ToolDispatch.Outcome.Ok(
            JSONObject()
                .put("ok", true)
                .put("id", entry.id)
                .put("kind", entry.kind)
                .put("interval_minutes", entry.intervalMinutes)
                .put("next_fire_ms", entry.nextFireMs)
                .put("next_fire_utc", utc(entry.nextFireMs))
                .put("label", entry.label)
                .put(
                    "note",
                    "Runs in the background; never turns Agent mode on. Stop from notification or stop_job.",
                )
        )
    }

    private fun stop(context: Context, id: String): ToolDispatch.Outcome {
        if (id.isEmpty()) {
            return ToolDispatch.Outcome.Failed(-32602, "id must not be empty", null)
        }
        val removed =
            JobStore(context).remove(id)
                ?: return ToolDispatch.Outcome.Failed(-32602, "no job with that id", null)
        JobScheduler.cancel(context, id)
        return ToolDispatch.Outcome.Ok(
            JSONObject().put("ok", true).put("id", removed.id).put("stopped", true)
        )
    }

    private fun list(context: Context): ToolDispatch.Outcome {
        val arr = JSONArray()
        for (e in JobStore(context).list()) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("kind", e.kind)
                    .put("interval_minutes", e.intervalMinutes)
                    .put("next_fire_ms", e.nextFireMs)
                    .put("next_fire_utc", utc(e.nextFireMs))
                    .put("label", e.label)
            )
        }
        return ToolDispatch.Outcome.Ok(JSONObject().put("jobs", arr).put("count", arr.length()))
    }

    private fun utc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }
}
