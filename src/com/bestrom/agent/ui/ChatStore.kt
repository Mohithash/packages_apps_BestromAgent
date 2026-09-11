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

import com.bestrom.agent.runner.StepEvent

/**
 * Chat transcript for the ChatGPT-style UI.
 *
 * User goals and final answers stay for the session and are persisted via
 * [ChatHistoryStore]. Live tool steps are rebuilt while a task runs, then
 * removed when it ends (they can name what was on other apps' screens).
 */
object ChatStore {

    enum class Role {
        USER,
        ASSISTANT,
        TOOL,
    }

    data class Message(
        val id: Long,
        val role: Role,
        val text: String,
        /** For TOOL rows: short label before the body (e.g. "do", "…"). */
        val label: String = "",
        val stepKind: StepEvent.Kind? = null,
    )

    private const val MAX_MESSAGES = 400

    private val lock = Any()
    private val messages = ArrayList<Message>(64)
    private var nextId = 1L
    /** Index where the current task's live TOOL rows start, or -1. */
    private var liveStart = -1
    private var awaitingAnswer = false

    fun clear() {
        synchronized(lock) {
            messages.clear()
            liveStart = -1
            awaitingAnswer = false
            nextId = 1L
        }
    }

    /** Replace the transcript with a saved history (USER/ASSISTANT only). */
    fun replaceAll(history: List<Message>) {
        synchronized(lock) {
            messages.clear()
            liveStart = -1
            awaitingAnswer = false
            var maxId = 0L
            for (m in history) {
                if (m.role == Role.TOOL) continue
                messages.add(m)
                if (m.id > maxId) maxId = m.id
            }
            nextId = maxId + 1
            trimLocked()
        }
    }

    fun durableSnapshot(): List<Message> =
        synchronized(lock) {
            messages.filter { it.role == Role.USER || it.role == Role.ASSISTANT }
        }

    fun addUser(text: String) {
        synchronized(lock) {
            stripLiveLocked()
            messages.add(Message(nextId++, Role.USER, text))
            liveStart = messages.size
            awaitingAnswer = true
            trimLocked()
        }
    }

    /** Immediate assistant line when a task never started (validation error). */
    fun addAssistantError(text: String) {
        synchronized(lock) {
            stripLiveLocked()
            messages.add(
                Message(
                    nextId++,
                    Role.ASSISTANT,
                    text,
                    stepKind = StepEvent.Kind.ERROR,
                ),
            )
            liveStart = -1
            awaitingAnswer = false
            trimLocked()
        }
    }

    /**
     * Rebuilds the live tool block from [steps]. When a terminal event appears,
     * commits one assistant bubble and drops the tool lines.
     */
    fun syncSteps(steps: List<StepEvent>) {
        synchronized(lock) {
            if (!awaitingAnswer) return

            val terminal =
                steps.lastOrNull {
                    it.kind == StepEvent.Kind.DONE ||
                        it.kind == StepEvent.Kind.ERROR ||
                        it.kind == StepEvent.Kind.REFUSED
                }

            stripLiveLocked()
            liveStart = messages.size

            if (terminal != null) {
                messages.add(
                    Message(nextId++, Role.ASSISTANT, terminal.text, stepKind = terminal.kind),
                )
                liveStart = -1
                awaitingAnswer = false
                trimLocked()
                return
            }

            for (event in steps) {
                if (event.kind == StepEvent.Kind.CONFIRM) {
                    messages.add(
                        Message(nextId++, Role.ASSISTANT, event.text, stepKind = event.kind),
                    )
                    continue
                }
                val label =
                    when (event.kind) {
                        StepEvent.Kind.THINKING -> "…"
                        StepEvent.Kind.ACTION -> "do"
                        StepEvent.Kind.RESULT -> "ok"
                        else -> "·"
                    }
                messages.add(
                    Message(nextId++, Role.TOOL, event.text, label = label, stepKind = event.kind),
                )
            }
            trimLocked()
        }
    }

    fun snapshot(): List<Message> = synchronized(lock) { ArrayList(messages) }

    fun isEmpty(): Boolean = synchronized(lock) { messages.isEmpty() }

    private fun stripLiveLocked() {
        if (liveStart < 0) return
        while (messages.size > liveStart) {
            messages.removeAt(messages.size - 1)
        }
    }

    private fun trimLocked() {
        while (messages.size > MAX_MESSAGES) {
            messages.removeAt(0)
            if (liveStart > 0) liveStart--
        }
    }
}
