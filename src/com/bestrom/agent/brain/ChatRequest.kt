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


package com.bestrom.agent.brain

import org.json.JSONArray
import org.json.JSONObject

/**
 * One message on the wire.
 *
 * There is no `name` field: Groq answers 400 for messages[].name and nothing in
 * the loop needs it.
 */
class ChatMessage(
    val role: String,
    val content: String?,
    val toolCalls: List<Call>? = null,
    val toolCallId: String? = null,
    /**
     * A PNG, base64, sent as a data URI content part.
     *
     * Only ever a user message: the compatibility layers accept an image in a
     * user turn and not in a tool turn, so a screenshot is answered as a tool
     * result saying it was taken and then handed over in the message after it.
     */
    val imageBase64: String? = null,
) {
    class Call(val id: String, val name: String, val argumentsJson: String)

    fun toJson(): JSONObject {
        val o = JSONObject().put("role", role)
        val image = imageBase64
        if (image != null) {
            o.put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", content ?: ""))
                    .put(
                        JSONObject()
                            .put("type", "image_url")
                            .put(
                                "image_url",
                                JSONObject().put("url", "data:image/png;base64," + image),
                            )
                    ),
            )
            return o
        }
        o.put("content", content ?: JSONObject.NULL)
        if (toolCallId != null) o.put("tool_call_id", toolCallId)
        val calls = toolCalls
        if (calls != null && calls.isNotEmpty()) {
            val array = JSONArray()
            for (c in calls) {
                array.put(
                    JSONObject()
                        .put("id", c.id)
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject().put("name", c.name).put("arguments", c.argumentsJson),
                        )
                )
            }
            o.put("tool_calls", array)
        }
        return o
    }

    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
        const val TOOL = "tool"

        /** A screenshot, as the one message shape every preset accepts. */
        fun image(caption: String, pngBase64: String): ChatMessage =
            ChatMessage(USER, caption, imageBase64 = pngBase64)
    }
}

/**
 * Builds the chat-completions body.
 *
 * The whole point of this file is that it is testable without a device and
 * without a network: which fields a preset may carry is a property of the
 * preset table, so a provider that rejects a field cannot be discovered only in
 * the field.
 */
object ChatRequest {

    /**
     * Not 0. Groq silently rewrites an exact 0 to 1e-8, so the value that looks
     * deterministic is the one that is quietly rewritten.
     */
    const val TEMPERATURE = 0.1

    const val MAX_TOKENS = 1024

    fun build(
        config: BrainConfig,
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: JSONArray?,
        maxTokens: Int = MAX_TOKENS,
    ): JSONObject {
        val body = JSONObject()
        body.put("model", config.model)

        val array = JSONArray()
        if (systemPrompt.isNotEmpty()) {
            array.put(ChatMessage(ChatMessage.SYSTEM, systemPrompt).toJson())
        }
        for (m in messages) array.put(m.toJson())
        body.put("messages", array)

        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            if (config.preset.supportsToolChoice) body.put("tool_choice", "auto")
        }

        body.put(config.preset.maxTokensField, maxTokens)
        body.put("temperature", TEMPERATURE)
        // Only sent to an endpoint that documents it, so adding streaming later
        // is an additive change rather than a new failure mode on the presets
        // that do not.
        if (config.preset.supportsStreaming) body.put("stream", false)
        return body
    }

    /**
     * The fields this client never sends, named so the test can assert their
     * absence rather than trusting the builder above to stay short.
     */
    val NEVER_SENT: List<String> =
        listOf(
            "logprobs",
            "top_logprobs",
            "logit_bias",
            "n",
            "seed",
            "response_format",
            "presence_penalty",
            "frequency_penalty",
            "user",
            "metadata",
            "store",
            "parallel_tool_calls",
            "strict",
        )
}
