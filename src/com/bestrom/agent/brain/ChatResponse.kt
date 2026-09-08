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

import org.json.JSONObject

/**
 * choices[0].message, and nothing else from the body.
 *
 * usage is null rather than zero when the endpoint omits it - several LAN
 * servers do - so the step guard knows to estimate instead of believing a
 * task cost nothing.
 */
class ChatResponse(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val finishReason: String?,
    val usage: Usage?,
    val model: String?,
) {
    /**
     * function.arguments is a JSON string on every provider, so it is carried
     * as a string here and parsed by the caller: a bad argument object is a
     * malformed tool call the model can fix, not a transport failure.
     */
    class ToolCall(val id: String, val name: String, val argumentsJson: String)

    class Usage(val promptTokens: Int, val completionTokens: Int) {
        fun total(): Int = promptTokens + completionTokens
    }

    sealed class Parsed {
        class Ok(val response: ChatResponse) : Parsed()

        class Bad(val error: BrainError.BadResponse) : Parsed()
    }

    companion object {

        const val FINISH_LENGTH = "length"
        const val FINISH_TOOL_CALLS = "tool_calls"

        fun parse(body: String): Parsed {
            val trimmed = body.trim()
            if (trimmed.isEmpty()) return bad("the response body was empty")
            if (trimmed.startsWith("<")) {
                // A LAN server serving an HTML error page is the common case,
                // and a JSONException here would name a parser rather than the
                // thing that went wrong.
                return bad("the endpoint answered with an HTML page, not JSON")
            }
            val root =
                try {
                    JSONObject(trimmed)
                } catch (e: Exception) {
                    return bad("the response body is not JSON")
                }
            return parse(root)
        }

        fun parse(root: JSONObject): Parsed {
            val choices = root.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                val message = root.optJSONObject("error")?.optString("message").orEmpty()
                return bad(
                    if (message.isEmpty()) "the response carries no choices"
                    else "the response carries no choices: " +
                        message.take(BrainError.MAX_DETAIL)
                )
            }
            val choice = choices.optJSONObject(0) ?: return bad("choices[0] is not an object")
            val message = choice.optJSONObject("message") ?: JSONObject()

            val content =
                if (message.isNull("content")) null
                else message.optString("content").ifEmpty { null }

            val calls = ArrayList<ToolCall>()
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls != null) {
                // Two calls sharing one id become two tool messages with the
                // same tool_call_id: some compatibility layers answer 400 for
                // that and one silently drops the second reply.
                val ids = HashSet<String>()
                for (i in 0 until toolCalls.length()) {
                    val c = toolCalls.optJSONObject(i) ?: continue
                    val function = c.optJSONObject("function") ?: continue
                    val name = function.optString("name")
                    if (name.isEmpty()) continue
                    var id = c.optString("id").ifEmpty { "call_$i" }
                    if (!ids.add(id)) {
                        id = "call_$i"
                        while (!ids.add(id)) id += "_"
                    }
                    calls.add(
                        ToolCall(id, name, function.optString("arguments").ifEmpty { "{}" })
                    )
                }
            }

            val finish =
                if (choice.isNull("finish_reason")) null
                else choice.optString("finish_reason").ifEmpty { null }

            val usageObject = root.optJSONObject("usage")
            val usage =
                if (usageObject == null ||
                    (!usageObject.has("prompt_tokens") && !usageObject.has("completion_tokens"))
                ) {
                    null
                } else {
                    Usage(
                        usageObject.optInt("prompt_tokens", 0),
                        usageObject.optInt("completion_tokens", 0),
                    )
                }

            return Parsed.Ok(
                ChatResponse(
                    content,
                    calls,
                    finish,
                    usage,
                    root.optString("model").ifEmpty { null },
                )
            )
        }

        private fun bad(why: String): Parsed = Parsed.Bad(BrainError.BadResponse(why))
    }
}
