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

import android.content.Context
import com.bestrom.agent.AgentState
import com.bestrom.agent.runner.ToolDispatch
import com.bestrom.agent.runner.ToolSchema
import org.json.JSONArray
import org.json.JSONObject

/**
 * list_playbooks / run_playbook. Expands curated goals; never auto-confirms pay.
 */
object PlaybookTools {

    fun run(context: Context, call: ToolSchema.ToolCall): ToolDispatch.Outcome =
        when (call.name) {
            ToolSchema.LIST_PLAYBOOKS -> list()
            ToolSchema.RUN_PLAYBOOK -> runPlaybook(call.args)
            else -> ToolDispatch.Outcome.Failed(-32602, "unknown playbook tool", null)
        }

    private fun list(): ToolDispatch.Outcome {
        val arr = JSONArray()
        for (p in PlaybookCatalog.ALL) {
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("title", p.title)
                    .put("needs_detail", p.needsDetail)
                    .put("package_hint", p.packageHint ?: "")
            )
        }
        return ToolDispatch.Outcome.Ok(
            JSONObject()
                .put("playbooks", arr)
                .put("count", arr.length())
                .put(
                    "note",
                    "run_playbook expands a curated goal. Checkout/Pay taps always ask. " +
                        "Never auto-confirm payment.",
                )
        )
    }

    private fun runPlaybook(args: JSONObject): ToolDispatch.Outcome {
        val id = args.optString("id")
        val detail = args.optString("detail")
        PlaybookCatalog.rejectId(id)?.let {
            return ToolDispatch.Outcome.Failed(-32602, it, null)
        }
        val playbook = PlaybookCatalog.get(id)!!
        PlaybookCatalog.rejectDetail(playbook, detail)?.let {
            return ToolDispatch.Outcome.Failed(-32602, it, null)
        }
        val goal =
            PlaybookCatalog.expand(id, detail)
                ?: return ToolDispatch.Outcome.Failed(-32602, "could not expand playbook", null)

        val bridge = AgentState.bridge
        if (!AgentState.bridgeLive.get() || bridge == null) {
            return ToolDispatch.Outcome.Ok(
                JSONObject()
                    .put("ok", true)
                    .put("started", false)
                    .put("id", id)
                    .put("goal", goal)
                    .put(
                        "note",
                        "Agent mode is off. Turn Agent on, then run this goal, or paste it " +
                            "into the composer.",
                    )
            )
        }

        val err = bridge.startTask(goal)
        if (err == null) {
            return ToolDispatch.Outcome.Ok(
                JSONObject()
                    .put("ok", true)
                    .put("started", true)
                    .put("id", id)
                    .put("goal", goal)
                    .put(
                        "note",
                        "Playbook task started. Payment/checkout controls still need " +
                            "confirmation on the phone.",
                    )
            )
        }

        // Task already running: hand the expanded goal to the model as focus.
        return ToolDispatch.Outcome.Ok(
            JSONObject()
                .put("ok", true)
                .put("started", false)
                .put("id", id)
                .put("goal", goal)
                .put(
                    "note",
                    "A task is already running ($err). Treat the goal field as the " +
                        "focus for the rest of this task. Still never auto-pay.",
                )
        )
    }
}
