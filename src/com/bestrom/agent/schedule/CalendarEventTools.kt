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
import com.bestrom.agent.health.HealthProxy
import com.bestrom.agent.health.HealthProxyContract
import com.bestrom.agent.runner.ToolDispatch
import com.bestrom.agent.runner.ToolSchema
import org.json.JSONObject

/** Agent tool: insert a calendar event after the runner already confirmed with the user. */
object CalendarEventTools {

    fun run(context: Context, call: ToolSchema.ToolCall): ToolDispatch.Outcome {
        if (call.name != ToolSchema.SCHEDULE_EVENT) {
            return ToolDispatch.Outcome.Failed(-32602, "unknown calendar tool", null)
        }
        val title = call.args.optString("title").trim()
        val start = call.args.optLong("start_epoch_ms", -1L)
        val duration = call.args.optInt("duration_min", 30)
        val remind = call.args.optInt("remind_min", 10)
        if (title.isEmpty() || start <= 0L) {
            return ToolDispatch.Outcome.Failed(-32602, "schedule_event needs title and start_epoch_ms", null)
        }
        val bundle = HealthProxy.scheduleEvent(context, title, start, duration, remind)
        if (!bundle.getBoolean(HealthProxyContract.KEY_OK, false)) {
            return ToolDispatch.Outcome.Failed(
                -32000,
                bundle.getString(HealthProxyContract.KEY_ERROR) ?: "schedule failed",
                null,
            )
        }
        return ToolDispatch.Outcome.Ok(
            JSONObject()
                .put("ok", true)
                .put("event_id", bundle.getLong(HealthProxyContract.KEY_EVENT_ID))
                .put("uri", bundle.getString(HealthProxyContract.KEY_EVENT_URI))
        )
    }
}
