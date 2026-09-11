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

import android.content.Context
import com.bestrom.agent.runner.ToolDispatch
import com.bestrom.agent.runner.ToolSchema
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Bridge-free handlers for schedule_* / list_reminders / cancel_reminder. */
object ReminderTools {

    fun run(context: Context, call: ToolSchema.ToolCall): ToolDispatch.Outcome {
        val store = ReminderStore(context)
        return when (call.name) {
            ToolSchema.SCHEDULE_REMINDER -> schedule(context, store, ReminderTime.KIND_REMINDER, call.args.optString("message"), call.args)
            ToolSchema.SCHEDULE_TASK -> schedule(context, store, ReminderTime.KIND_TASK, call.args.optString("goal"), call.args)
            ToolSchema.LIST_REMINDERS -> list(store)
            ToolSchema.CANCEL_REMINDER -> cancel(context, store, call.args.optString("id"))
            else -> ToolDispatch.Outcome.Failed(-32602, "unknown schedule tool", null)
        }
    }

    private fun schedule(
        context: Context,
        store: ReminderStore,
        kind: String,
        text: String,
        args: JSONObject,
    ): ToolDispatch.Outcome {
        val fireAt = ReminderTime.fireAtMs(args)
            ?: return ToolDispatch.Outcome.Failed(-32602, "invalid schedule time", null)
        val entry =
            store.add(kind, text, fireAt)
                ?: return ToolDispatch.Outcome.Failed(
                    -32602,
                    "could not schedule (full or invalid text; max ${ReminderTime.MAX_ENTRIES})",
                    null,
                )
        ReminderScheduler.arm(context, entry)
        return ToolDispatch.Outcome.Ok(
            JSONObject()
                .put("ok", true)
                .put("id", entry.id)
                .put("kind", entry.kind)
                .put("fire_at_ms", entry.fireAtMs)
                .put("fire_at_utc", utc(entry.fireAtMs))
                .put("text", entry.text)
        )
    }

    private fun list(store: ReminderStore): ToolDispatch.Outcome {
        val entries = store.list()
        val arr = JSONArray()
        for (e in entries) {
            arr.put(
                JSONObject()
                    .put("id", e.id)
                    .put("kind", e.kind)
                    .put("fire_at_ms", e.fireAtMs)
                    .put("fire_at_utc", utc(e.fireAtMs))
                    .put("text", e.text)
            )
        }
        return ToolDispatch.Outcome.Ok(
            JSONObject().put("reminders", arr).put("count", entries.size)
        )
    }

    private fun cancel(context: Context, store: ReminderStore, id: String): ToolDispatch.Outcome {
        val removed = store.remove(id)
            ?: return ToolDispatch.Outcome.Failed(-32602, "no reminder with that id", null)
        ReminderScheduler.cancel(context, id)
        return ToolDispatch.Outcome.Ok(
            JSONObject().put("ok", true).put("id", removed.id).put("cancelled", true)
        )
    }

    private fun utc(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }
}
