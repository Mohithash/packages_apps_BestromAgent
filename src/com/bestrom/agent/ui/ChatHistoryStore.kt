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

package com.bestrom.agent.ui

import android.content.Context
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists USER + ASSISTANT turns across sessions. TOOL lines are never saved
 * (they can name other apps' screens).
 */
class ChatHistoryStore(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "agent_chat_history.json"))

    data class Session(
        val id: String,
        val title: String,
        val updatedMs: Long,
        val messages: List<ChatStore.Message>,
    )

    @Synchronized
    fun listSessions(): List<Session> =
        read().sessions.sortedByDescending { it.updatedMs }

    @Synchronized
    fun loadSession(id: String): Session? = read().sessions.firstOrNull { it.id == id }

    @Synchronized
    fun saveCurrent(messages: List<ChatStore.Message>, existingId: String?): String {
        val durable =
            messages.filter {
                it.role == ChatStore.Role.USER || it.role == ChatStore.Role.ASSISTANT
            }
        if (durable.isEmpty()) return existingId.orEmpty()
        val title =
            durable.firstOrNull { it.role == ChatStore.Role.USER }?.text?.take(60)
                ?: "Chat"
        val all = read().sessions.toMutableList()
        val id = existingId?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString().take(8)
        all.removeAll { it.id == id }
        all.add(
            0,
            Session(
                id = id,
                title = title,
                updatedMs = System.currentTimeMillis(),
                messages =
                    durable.map {
                        ChatStore.Message(it.id, it.role, it.text, stepKind = it.stepKind)
                    },
            ),
        )
        while (all.size > MAX_SESSIONS) all.removeAt(all.size - 1)
        write(all)
        return id
    }

    @Synchronized
    fun deleteSession(id: String) {
        val all = read().sessions.toMutableList()
        all.removeAll { it.id == id }
        write(all)
    }

    @Synchronized
    fun clearAll() {
        write(emptyList())
    }

    private data class Root(val sessions: List<Session>)

    private fun read(): Root {
        if (!file.exists()) return Root(emptyList())
        return try {
            val arr = JSONObject(file.readText()).optJSONArray("sessions") ?: return Root(emptyList())
            val out = ArrayList<Session>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val msgs = o.optJSONArray("messages") ?: JSONArray()
                val list = ArrayList<ChatStore.Message>(msgs.length())
                for (j in 0 until msgs.length()) {
                    val m = msgs.optJSONObject(j) ?: continue
                    val role =
                        when (m.optString("role")) {
                            "assistant" -> ChatStore.Role.ASSISTANT
                            else -> ChatStore.Role.USER
                        }
                    list.add(
                        ChatStore.Message(
                            id = m.optLong("id", j.toLong()),
                            role = role,
                            text = m.optString("text"),
                        )
                    )
                }
                out.add(
                    Session(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        updatedMs = o.optLong("updated_ms"),
                        messages = list,
                    )
                )
            }
            Root(out)
        } catch (_: Exception) {
            Root(emptyList())
        }
    }

    private fun write(sessions: List<Session>) {
        val arr = JSONArray()
        for (s in sessions) {
            val msgs = JSONArray()
            for (m in s.messages) {
                msgs.put(
                    JSONObject()
                        .put("id", m.id)
                        .put(
                            "role",
                            if (m.role == ChatStore.Role.ASSISTANT) "assistant" else "user",
                        )
                        .put("text", m.text)
                )
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("updated_ms", s.updatedMs)
                    .put("messages", msgs)
            )
        }
        file.parentFile?.mkdirs()
        file.writeText(JSONObject().put("sessions", arr).toString())
    }

    companion object {
        const val MAX_SESSIONS = 40
    }
}
