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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/** Runtime state for one recipe (counters, checks, logs, timer). */
class MiniAppRuntimeStore(private val dir: File) {

    constructor(context: Context) : this(File(context.filesDir, "miniapp_data"))

    fun counterValue(id: String): Int = read(id).optInt("value", 0)

    fun setCounter(id: String, value: Int): Int {
        val o = read(id)
        val v = value.coerceAtLeast(0)
        o.put("value", v)
        write(id, o)
        return v
    }

    fun bumpCounter(id: String, delta: Int): Int = setCounter(id, counterValue(id) + delta)

    fun checklistChecked(id: String, recipe: MiniAppRecipe): BooleanArray {
        val arr = read(id).optJSONArray("checked")
        val out = BooleanArray(recipe.items.size)
        if (arr != null) {
            for (i in recipe.items.indices) {
                out[i] = arr.optBoolean(i, false)
            }
        }
        return out
    }

    fun toggleChecklist(id: String, recipe: MiniAppRecipe, index: Int): BooleanArray {
        val checked = checklistChecked(id, recipe)
        if (index !in checked.indices) return checked
        checked[index] = !checked[index]
        val arr = JSONArray()
        for (b in checked) arr.put(b)
        val o = read(id)
        o.put("checked", arr)
        write(id, o)
        return checked
    }

    fun todayLog(id: String): List<String> {
        val day = todayKey()
        val o = read(id)
        val byDay = o.optJSONObject("log") ?: return emptyList()
        val arr = byDay.optJSONArray(day) ?: return emptyList()
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    fun appendLog(id: String, line: String): List<String> {
        val text = line.trim().take(MiniAppLimits.MAX_LOG_LINE_CHARS)
        if (text.isEmpty()) return todayLog(id)
        val day = todayKey()
        val o = read(id)
        val byDay = o.optJSONObject("log") ?: JSONObject()
        val arr = byDay.optJSONArray(day) ?: JSONArray()
        if (arr.length() >= MiniAppLimits.MAX_LOG_LINES_PER_DAY) {
            // drop oldest
            val next = JSONArray()
            for (i in 1 until arr.length()) next.put(arr.get(i))
            next.put(text)
            byDay.put(day, next)
        } else {
            arr.put(text)
            byDay.put(day, arr)
        }
        o.put("log", byDay)
        write(id, o)
        return todayLog(id)
    }

    fun timerEndsAtMs(id: String): Long = read(id).optLong("timer_ends_ms", 0L)

    fun startTimer(id: String, minutes: Int): Long {
        val ends = System.currentTimeMillis() + minutes.coerceIn(
            MiniAppLimits.MIN_TIMER_MINUTES,
            MiniAppLimits.MAX_TIMER_MINUTES,
        ) * 60_000L
        val o = read(id)
        o.put("timer_ends_ms", ends)
        write(id, o)
        return ends
    }

    fun clearTimer(id: String) {
        val o = read(id)
        o.put("timer_ends_ms", 0L)
        write(id, o)
    }

    fun delete(id: String) {
        fileFor(id).delete()
    }

    fun clearAll() {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { it.delete() }
    }

    fun widgetSummary(recipe: MiniAppRecipe): String =
        when (recipe.kind) {
            MiniAppLimits.KIND_COUNTER -> {
                val unit = recipe.unit.ifEmpty { "x" }
                "${counterValue(recipe.id)} $unit"
            }
            MiniAppLimits.KIND_CHECKLIST -> {
                val c = checklistChecked(recipe.id, recipe)
                "${c.count { it }}/${c.size} done"
            }
            MiniAppLimits.KIND_DAILY_LOG -> "${todayLog(recipe.id).size} today"
            MiniAppLimits.KIND_TIMER -> {
                val ends = timerEndsAtMs(recipe.id)
                if (ends <= System.currentTimeMillis()) "idle"
                else {
                    val sec = ((ends - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                    "%d:%02d left".format(sec / 60, sec % 60)
                }
            }
            else -> recipe.kind
        }

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun fileFor(id: String): File = File(dir, "$id.json")

    private fun read(id: String): JSONObject {
        val f = fileFor(id)
        if (!f.exists()) return JSONObject()
        return try {
            JSONObject(f.readText())
        } catch (_: Exception) {
            JSONObject()
        }
    }

    private fun write(id: String, o: JSONObject) {
        dir.mkdirs()
        fileFor(id).writeText(o.toString())
    }
}
