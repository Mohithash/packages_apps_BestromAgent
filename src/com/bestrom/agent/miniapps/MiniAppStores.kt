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
import org.json.JSONObject

/** Cups of water logged per calendar day. */
class WaterStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "agent_water.json"))

    @Synchronized
    fun todayCups(): Int = cupsFor(todayKey())

    @Synchronized
    fun addCup(n: Int = 1): Int {
        val key = todayKey()
        val all = read()
        val next = ((all[key] ?: 0) + n).coerceAtLeast(0)
        all[key] = next
        write(all)
        return next
    }

    @Synchronized
    fun setGoal(cups: Int) {
        val all = read()
        all["_goal"] = cups.coerceIn(1, 20)
        write(all)
    }

    @Synchronized
    fun goal(): Int = read()["_goal"] ?: 8

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun cupsFor(day: String): Int = read()[day] ?: 0

    private fun todayKey(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun read(): MutableMap<String, Int> {
        if (!file.exists()) return mutableMapOf("_goal" to 8)
        return try {
            val o = JSONObject(file.readText())
            val out = mutableMapOf<String, Int>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.optInt(k)
            }
            if (!out.containsKey("_goal")) out["_goal"] = 8
            out
        } catch (_: Exception) {
            mutableMapOf("_goal" to 8)
        }
    }

    private fun write(map: Map<String, Int>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v)
        file.parentFile?.mkdirs()
        file.writeText(o.toString())
    }
}

/** Weight entries: date → kg. */
class WeightStore(private val file: File) {
    constructor(context: Context) : this(File(context.filesDir, "agent_weight.json"))

    data class Entry(val day: String, val kg: Float)

    @Synchronized
    fun log(kg: Float): Entry {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val all = read()
        all[day] = kg
        write(all)
        return Entry(day, kg)
    }

    @Synchronized
    fun latest(): Entry? =
        read()
            .entries
            .filter { it.key != "_goal" }
            .maxByOrNull { it.key }
            ?.let { Entry(it.key, it.value) }

    @Synchronized
    fun setGoalKg(kg: Float) {
        val all = read()
        all["_goal"] = kg
        write(all)
    }

    @Synchronized
    fun goalKg(): Float? = read()["_goal"]

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun read(): MutableMap<String, Float> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val o = JSONObject(file.readText())
            val out = mutableMapOf<String, Float>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                out[k] = o.optDouble(k).toFloat()
            }
            out
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun write(map: Map<String, Float>) {
        val o = JSONObject()
        for ((k, v) in map) o.put(k, v.toDouble())
        file.parentFile?.mkdirs()
        file.writeText(o.toString())
    }
}
