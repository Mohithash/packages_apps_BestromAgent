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


package com.bestrom.agent.runner

import com.bestrom.agent.brain.ChatMessage

/**
 * The messages sent to the model, and what is dropped from them as they age.
 *
 * A screen digest is three or four kilobytes and is worthless two rounds
 * later: what the model needs is the newest one and the shape of what it has
 * already tried. So the last few rounds go whole, older screens collapse to a
 * placeholder, and older tool results collapse to one line.
 *
 * Derived from PokeClaw's compressHistoryForSend, KEEP_RECENT_ROUNDS and
 * OBSERVATION_PLACEHOLDERS (Apache-2.0).
 */
class Transcript {

    companion object {
        const val KEEP_RECENT_ROUNDS = 3

        const val SCREEN_OMITTED = "[screen omitted]"

        /** How much of an older tool result survives as its summary. */
        const val SUMMARY_CHARS = 120
    }

    private enum class Kind {
        PLAIN,
        SCREEN,
        TOOL,
    }

    private class Entry(val message: ChatMessage, val round: Int, val kind: Kind)

    private val entries = ArrayList<Entry>()
    private var round = 0

    fun addUser(text: String) {
        entries.add(Entry(ChatMessage(ChatMessage.USER, text), round, Kind.PLAIN))
    }

    /** A user message carrying an image part - the screenshot tool's result. */
    fun addImage(caption: String, pngBase64: String) {
        entries.add(Entry(ChatMessage.image(caption, pngBase64), round, Kind.PLAIN))
    }

    /** Opens a new round. Everything until the next one belongs to it. */
    fun addAssistant(text: String?, toolCalls: List<ChatMessage.Call>) {
        round++
        entries.add(
            Entry(
                ChatMessage(ChatMessage.ASSISTANT, text, toolCalls.ifEmpty { null }),
                round,
                Kind.PLAIN,
            )
        )
    }

    /** [screen] marks a result that is a screen digest, which ages differently. */
    fun addToolResult(toolCallId: String, text: String, screen: Boolean) {
        entries.add(
            Entry(
                ChatMessage(ChatMessage.TOOL, text, toolCallId = toolCallId),
                round,
                if (screen) Kind.SCREEN else Kind.TOOL,
            )
        )
    }

    fun size(): Int = entries.size

    /**
     * The messages to send, compacted.
     *
     * Pure: it builds a new list and changes nothing, so calling it twice gives
     * the same answer and a compaction cannot compound.
     */
    fun forSend(): List<ChatMessage> {
        val newestScreen = entries.indexOfLast { it.kind == Kind.SCREEN }
        val keepFrom = round - KEEP_RECENT_ROUNDS + 1
        val out = ArrayList<ChatMessage>(entries.size)
        for ((i, entry) in entries.withIndex()) {
            // The newest screen is always whole, however old its round: it is
            // the only thing in here describing what is in front of the user.
            if (entry.round >= keepFrom || i == newestScreen) {
                out.add(entry.message)
                continue
            }
            when (entry.kind) {
                Kind.SCREEN ->
                    out.add(
                        ChatMessage(
                            ChatMessage.TOOL,
                            SCREEN_OMITTED,
                            toolCallId = entry.message.toolCallId,
                        )
                    )
                Kind.TOOL ->
                    out.add(
                        ChatMessage(
                            ChatMessage.TOOL,
                            summarise(entry.message.content),
                            toolCallId = entry.message.toolCallId,
                        )
                    )
                Kind.PLAIN -> out.add(entry.message)
            }
        }
        return out
    }

    /**
     * One line of an old tool result.
     *
     * Enough to remember that a thing was tried and whether it worked, and not
     * enough to pay for it again every round.
     */
    private fun summarise(content: String?): String {
        if (content.isNullOrEmpty()) return "ok"
        val body =
            content
                .lineSequence()
                .filter {
                    it.isNotBlank() &&
                        it != InjectionFilter.HEADER &&
                        it != InjectionFilter.FOOTER
                }
                .lastOrNull()
                ?: return "ok"
        return if (body.length <= SUMMARY_CHARS) body else body.take(SUMMARY_CHARS) + " ..."
    }
}
