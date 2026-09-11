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
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** One local reminder or deferred agent goal. */
data class ReminderEntry(
    val id: String,
    val kind: String,
    val text: String,
    val fireAtMs: Long,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("kind", kind)
            .put("text", text)
            .put("fire_at_ms", fireAtMs)

    companion object {
        fun fromJson(o: JSONObject): ReminderEntry? {
            val id = o.optString("id")
            val kind = o.optString("kind")
            val text = o.optString("text")
            val fire = o.optLong("fire_at_ms", -1L)
            if (id.isEmpty() || text.isEmpty() || fire <= 0L) return null
            if (kind != ReminderTime.KIND_REMINDER && kind != ReminderTime.KIND_TASK) return null
            return ReminderEntry(id, kind, text, fire)
        }
    }
}

/**
 * JSON file under the app's private files dir. Bounded; oldest future entries
 * win when full (new schedule refused).
 */
class ReminderStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "reminders.json"))

    @Synchronized
    fun list(): List<ReminderEntry> {
        val now = System.currentTimeMillis()
        return readAll().filter { it.fireAtMs >= now - 60_000L }.sortedBy { it.fireAtMs }
    }

    @Synchronized
    fun get(id: String): ReminderEntry? = readAll().firstOrNull { it.id == id }

    @Synchronized
    fun add(kind: String, text: String, fireAtMs: Long): ReminderEntry? {
        val trimmed = text.trim()
        if (ReminderTime.rejectText(trimmed) != null) return null
        val all = readAll().toMutableList()
        // Drop past entries so capacity is for live ones.
        val now = System.currentTimeMillis()
        all.removeAll { it.fireAtMs < now - 60_000L }
        if (all.size >= ReminderTime.MAX_ENTRIES) return null
        val entry =
            ReminderEntry(
                id = UUID.randomUUID().toString().take(8),
                kind = kind,
                text = trimmed,
                fireAtMs = fireAtMs,
            )
        all.add(entry)
        writeAll(all)
        return entry
    }

    @Synchronized
    fun remove(id: String): ReminderEntry? {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val removed = all.removeAt(idx)
        writeAll(all)
        return removed
    }

    private fun readAll(): List<ReminderEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            val arr = root.optJSONArray("entries") ?: return emptyList()
            val out = ArrayList<ReminderEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val e = ReminderEntry.fromJson(arr.optJSONObject(i) ?: continue) ?: continue
                out.add(e)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeAll(entries: List<ReminderEntry>) {
        val arr = JSONArray()
        for (e in entries) arr.put(e.toJson())
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("entries", arr).toString())
    }
}
