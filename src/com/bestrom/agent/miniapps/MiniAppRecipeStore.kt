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
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class MiniAppRecipe(
    val id: String,
    val name: String,
    val kind: String,
    val goal: String,
    val unit: String,
    val items: List<String>,
    val timerMinutes: Int,
    val createdMs: Long,
) {
    fun toJson(): JSONObject {
        val arr = JSONArray()
        for (i in items) arr.put(i)
        return JSONObject()
            .put("id", id)
            .put("name", name)
            .put("kind", kind)
            .put("goal", goal)
            .put("unit", unit)
            .put("items", arr)
            .put("timer_minutes", timerMinutes)
            .put("created_ms", createdMs)
    }

    companion object {
        fun fromJson(o: JSONObject): MiniAppRecipe? {
            val id = o.optString("id")
            val name = o.optString("name")
            val kind = o.optString("kind")
            if (id.isEmpty() || MiniAppLimits.rejectName(name) != null) return null
            if (MiniAppLimits.rejectKind(kind) != null) return null
            val itemsArr = o.optJSONArray("items") ?: JSONArray()
            val items = ArrayList<String>(itemsArr.length())
            for (i in 0 until itemsArr.length()) {
                items.add(itemsArr.optString(i))
            }
            return MiniAppRecipe(
                id = id,
                name = name.trim(),
                kind = kind,
                goal = o.optString("goal"),
                unit = o.optString("unit"),
                items = items,
                timerMinutes = o.optInt("timer_minutes", 25),
                createdMs = o.optLong("created_ms", 0L),
            )
        }
    }
}

class MiniAppRecipeStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "agent_miniapp_recipes.json"))

    @Synchronized
    fun list(): List<MiniAppRecipe> = readAll().sortedByDescending { it.createdMs }

    @Synchronized
    fun get(id: String): MiniAppRecipe? = readAll().firstOrNull { it.id == id }

    @Synchronized
    fun add(
        name: String,
        kind: String,
        goal: String,
        unit: String,
        items: List<String>,
        timerMinutes: Int,
    ): MiniAppRecipe? {
        val itemsArr = JSONArray()
        for (i in items) itemsArr.put(i)
        if (
            MiniAppLimits.rejectCreate(name, kind, goal, unit, itemsArr, timerMinutes) != null
        ) {
            return null
        }
        val all = readAll().toMutableList()
        if (all.size >= MiniAppLimits.MAX_RECIPES) return null
        val entry =
            MiniAppRecipe(
                id = UUID.randomUUID().toString().take(8),
                name = name.trim(),
                kind = kind,
                goal = goal.trim(),
                unit = unit.trim(),
                items = items,
                timerMinutes = timerMinutes,
                createdMs = System.currentTimeMillis(),
            )
        all.add(entry)
        writeAll(all)
        return entry
    }

    @Synchronized
    fun remove(id: String): MiniAppRecipe? {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val removed = all.removeAt(idx)
        writeAll(all)
        return removed
    }

    @Synchronized
    fun clear() {
        writeAll(emptyList())
    }

    private fun readAll(): List<MiniAppRecipe> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("recipes") ?: return emptyList()
            val out = ArrayList<MiniAppRecipe>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(MiniAppRecipe.fromJson(arr.optJSONObject(i) ?: continue) ?: continue)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeAll(recipes: List<MiniAppRecipe>) {
        val arr = JSONArray()
        for (r in recipes) arr.put(r.toJson())
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("recipes", arr).toString())
    }
}
