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

package com.bestrom.agent.miniapps

import android.content.Context
import android.content.Intent
import com.bestrom.agent.runner.ToolDispatch
import com.bestrom.agent.runner.ToolSchema
import org.json.JSONArray
import org.json.JSONObject

object MiniAppTools {

    fun run(context: Context, call: ToolSchema.ToolCall): ToolDispatch.Outcome =
        when (call.name) {
            ToolSchema.CREATE_MINIAPP -> create(context, call.args)
            ToolSchema.LIST_MINIAPPS -> list(context)
            ToolSchema.DELETE_MINIAPP -> delete(context, call.args.optString("id"))
            ToolSchema.OPEN_MINIAPP -> open(context, call.args.optString("id"))
            else -> ToolDispatch.Outcome.Failed(-32602, "unknown miniapp tool", null)
        }

    private fun create(context: Context, args: JSONObject): ToolDispatch.Outcome {
        val name = args.optString("name")
        val kind = args.optString("kind")
        val goal = args.optString("goal")
        val unit = args.optString("unit")
        val itemsRaw = MiniAppLimits.parseItems(args.opt("items"))
        val timerMinutes = args.optInt("timer_minutes", 25)
        MiniAppLimits.rejectCreate(name, kind, goal, unit, itemsRaw, timerMinutes)?.let {
            return ToolDispatch.Outcome.Failed(-32602, it, null)
        }
        val items = ArrayList<String>()
        if (itemsRaw != null) {
            for (i in 0 until itemsRaw.length()) items.add(itemsRaw.optString(i).trim())
        }
        val store = MiniAppRecipeStore(context)
        val entry =
            store.add(name, kind, goal, unit, items, timerMinutes)
                ?: return ToolDispatch.Outcome.Failed(
                    -32602,
                    "could not create (full; max ${MiniAppLimits.MAX_RECIPES})",
                    null,
                )
        return ToolDispatch.Outcome.Ok(
            JSONObject()
                .put("ok", true)
                .put("id", entry.id)
                .put("name", entry.name)
                .put("kind", entry.kind)
                .put(
                    "note",
                    "Mini app saved inside BestROM Agent (not a separate APK). " +
                        "Open it with open_miniapp or Agent settings → Mini apps. " +
                        "Add the Recipe widget from the launcher for a homescreen tile.",
                )
        )
    }

    private fun list(context: Context): ToolDispatch.Outcome {
        val runtime = MiniAppRuntimeStore(context)
        val arr = JSONArray()
        for (r in MiniAppRecipeStore(context).list()) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("kind", r.kind)
                    .put("goal", r.goal)
                    .put("summary", runtime.widgetSummary(r))
            )
        }
        return ToolDispatch.Outcome.Ok(
            JSONObject()
                .put("miniapps", arr)
                .put("count", arr.length())
                .put(
                    "kinds",
                    JSONArray().put(MiniAppLimits.KIND_COUNTER)
                        .put(MiniAppLimits.KIND_CHECKLIST)
                        .put(MiniAppLimits.KIND_DAILY_LOG)
                        .put(MiniAppLimits.KIND_TIMER),
                )
        )
    }

    private fun delete(context: Context, id: String): ToolDispatch.Outcome {
        if (id.isEmpty()) {
            return ToolDispatch.Outcome.Failed(-32602, "id must not be empty", null)
        }
        val removed =
            MiniAppRecipeStore(context).remove(id)
                ?: return ToolDispatch.Outcome.Failed(-32602, "no mini app with that id", null)
        MiniAppRuntimeStore(context).delete(id)
        return ToolDispatch.Outcome.Ok(
            JSONObject().put("ok", true).put("id", removed.id).put("deleted", true)
        )
    }

    private fun open(context: Context, id: String): ToolDispatch.Outcome {
        if (id.isEmpty()) {
            return ToolDispatch.Outcome.Failed(-32602, "id must not be empty", null)
        }
        val recipe =
            MiniAppRecipeStore(context).get(id)
                ?: return ToolDispatch.Outcome.Failed(-32602, "no mini app with that id", null)
        return try {
            context.startActivity(
                Intent(context, RecipeMiniAppActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(RecipeMiniAppActivity.EXTRA_ID, recipe.id)
            )
            ToolDispatch.Outcome.Ok(
                JSONObject().put("ok", true).put("id", recipe.id).put("opened", true)
            )
        } catch (e: Exception) {
            ToolDispatch.Outcome.Failed(
                -32000,
                "could not open: " + (e.message ?: "error"),
                null,
            )
        }
    }
}
