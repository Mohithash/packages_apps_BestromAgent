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


package com.bestrom.agent.audit

import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * An append-only, bounded record of what the bridge was asked to do.
 *
 * It records WHAT was called and against WHAT: a package and function name, a
 * resource id, a key name. Never a parameter value, never node text, never the
 * typed string, never the token. It holds 500 entries and rewrites the file when
 * it overflows, so it cannot grow without bound and cannot be edited selectively
 * from the bridge - log.clear removes everything or nothing.
 */
class AuditLog(private val file: File) {

    companion object {
        const val CAPACITY = 500

        @Volatile
        private var instance: AuditLog? = null

        /** [filesDir] is the app-private files directory; the log never leaves it. */
        @JvmStatic
        fun get(filesDir: File): AuditLog {
            val existing = instance
            if (existing != null) return existing
            synchronized(this) {
                val again = instance
                if (again != null) return again
                val created = AuditLog(File(filesDir, "agent-audit.log"))
                instance = created
                return created
            }
        }

        private fun utcFormat(): SimpleDateFormat {
            val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f
        }
    }

    class Entry(
        val tsUtc: String,
        val method: String,
        val target: String,
        val result: String,
        val errorCode: Int?,
        val durationMs: Long,
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("ts_utc", tsUtc)
                .put("method", method)
                .put("target", target)
                .put("result", result)
                .put("error_code", errorCode ?: JSONObject.NULL)
                .put("duration_ms", durationMs)

        companion object {
            fun fromJson(o: JSONObject): Entry =
                Entry(
                    o.optString("ts_utc"),
                    o.optString("method"),
                    o.optString("target"),
                    o.optString("result"),
                    if (o.isNull("error_code")) null else o.optInt("error_code"),
                    o.optLong("duration_ms"),
                )
        }
    }

    private val entries = ArrayDeque<Entry>()
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        if (!file.exists()) return
        try {
            file.forEachLine { line ->
                if (line.isNotBlank()) {
                    try {
                        entries.addLast(Entry.fromJson(JSONObject(line)))
                    } catch (ignored: Exception) {
                        // A truncated tail is dropped rather than failing the read.
                    }
                }
            }
        } catch (ignored: Exception) {
            entries.clear()
        }
        while (entries.size > CAPACITY) entries.removeFirst()
    }

    /** Appends one entry. Oldest first in the file, newest last. */
    @Synchronized
    fun append(
        method: String,
        target: String,
        result: String,
        errorCode: Int?,
        durationMs: Long,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        load()
        val entry = Entry(utcFormat().format(Date(nowMs)), method, target, result, errorCode, durationMs)
        entries.addLast(entry)
        if (entries.size > CAPACITY) {
            while (entries.size > CAPACITY) entries.removeFirst()
            rewrite()
        } else {
            try {
                file.appendText(entry.toJson().toString() + "\n")
            } catch (ignored: Exception) {
                // A log that cannot be written must not take the bridge down.
            }
        }
    }

    private fun rewrite() {
        try {
            val sb = StringBuilder()
            for (e in entries) sb.append(e.toJson().toString()).append('\n')
            file.writeText(sb.toString())
        } catch (ignored: Exception) {
        }
    }

    /** Newest first, at most [limit], optionally only entries at or after [sinceUtc]. */
    @Synchronized
    fun list(limit: Int, sinceUtc: String? = null): List<Entry> {
        load()
        val out = ArrayList<Entry>(minOf(limit, entries.size))
        val it = entries.descendingIterator()
        while (it.hasNext() && out.size < limit) {
            val e = it.next()
            if (sinceUtc != null && e.tsUtc < sinceUtc) continue
            out.add(e)
        }
        return out
    }

    @Synchronized
    fun size(): Int {
        load()
        return entries.size
    }

    /** Removes everything and returns how many entries went. */
    @Synchronized
    fun clear(): Int {
        load()
        val n = entries.size
        entries.clear()
        try {
            file.delete()
        } catch (ignored: Exception) {
        }
        return n
    }
}
