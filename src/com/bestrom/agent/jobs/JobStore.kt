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
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class JobEntry(
    val id: String,
    val kind: String,
    val intervalMinutes: Int,
    val nextFireMs: Long,
    val label: String,
) {
    fun toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("kind", kind)
            .put("interval_minutes", intervalMinutes)
            .put("next_fire_ms", nextFireMs)
            .put("label", label)

    companion object {
        fun fromJson(o: JSONObject): JobEntry? {
            val id = o.optString("id")
            val kind = o.optString("kind")
            val interval = o.optInt("interval_minutes", -1)
            val next = o.optLong("next_fire_ms", -1L)
            val label = o.optString("label", kind)
            if (id.isEmpty() || JobLimits.rejectKind(kind) != null) return null
            if (JobLimits.rejectInterval(interval) != null || next <= 0L) return null
            return JobEntry(id, kind, interval, next, label)
        }
    }
}

class JobStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "agent_jobs.json"))

    @Synchronized
    fun list(): List<JobEntry> = readAll().sortedBy { it.nextFireMs }

    @Synchronized
    fun get(id: String): JobEntry? = readAll().firstOrNull { it.id == id }

    @Synchronized
    fun add(kind: String, intervalMinutes: Int, label: String, nowMs: Long): JobEntry? {
        if (JobLimits.rejectKind(kind) != null) return null
        if (JobLimits.rejectInterval(intervalMinutes) != null) return null
        val lab = label.trim().ifEmpty { kind }
        if (JobLimits.rejectLabel(lab) != null) return null
        val all = readAll().toMutableList()
        if (all.size >= JobLimits.MAX_JOBS && all.none { it.kind == kind }) return null
        // One of each kind; return replaced ids via side channel for cancel.
        val entry =
            JobEntry(
                id = UUID.randomUUID().toString().take(8),
                kind = kind,
                intervalMinutes = intervalMinutes,
                nextFireMs = nowMs + intervalMinutes * 60_000L,
                label = lab,
            )
        all.removeAll { it.kind == kind }
        all.add(entry)
        writeAll(all)
        return entry
    }

    /** Ids removed when replacing a kind, so alarms can be cancelled. */
    @Synchronized
    fun idsOfKind(kind: String): List<String> = readAll().filter { it.kind == kind }.map { it.id }

    @Synchronized
    fun bumpNext(id: String, nowMs: Long): JobEntry? {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val cur = all[idx]
        val next =
            cur.copy(nextFireMs = nowMs + cur.intervalMinutes * 60_000L)
        all[idx] = next
        writeAll(all)
        return next
    }

    @Synchronized
    fun remove(id: String): JobEntry? {
        val all = readAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx < 0) return null
        val removed = all.removeAt(idx)
        writeAll(all)
        return removed
    }

    private fun readAll(): List<JobEntry> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("entries") ?: return emptyList()
            val out = ArrayList<JobEntry>(arr.length())
            for (i in 0 until arr.length()) {
                out.add(JobEntry.fromJson(arr.optJSONObject(i) ?: continue) ?: continue)
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeAll(entries: List<JobEntry>) {
        val arr = JSONArray()
        for (e in entries) arr.put(e.toJson())
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("entries", arr).toString())
    }
}
