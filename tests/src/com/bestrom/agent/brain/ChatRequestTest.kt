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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRequestTest {

    private fun config(preset: BrainPreset): BrainConfig =
        BrainConfig(preset = preset, baseUrl = preset.defaultBaseUrl, model = "a-model")

    private fun tools(): JSONArray =
        JSONArray()
            .put(
                JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject().put("name", "read_screen"))
            )

    private fun build(preset: BrainPreset): JSONObject =
        ChatRequest.build(
            config(preset),
            "system prompt",
            listOf(ChatMessage(ChatMessage.USER, "goal")),
            tools(),
        )

    @Test
    fun openAiIsTheOnlyPresetWithMaxCompletionTokens() {
        for (preset in BrainPreset.values()) {
            val body = build(preset)
            if (preset == BrainPreset.OPENAI) {
                assertTrue(preset.name, body.has("max_completion_tokens"))
                assertFalse(preset.name, body.has("max_tokens"))
            } else {
                assertTrue(preset.name, body.has("max_tokens"))
                assertFalse(preset.name, body.has("max_completion_tokens"))
            }
        }
    }

    @Test
    fun ollamaIsTheOnlyPresetWithoutToolChoice() {
        for (preset in BrainPreset.values()) {
            val body = build(preset)
            if (preset == BrainPreset.OLLAMA) {
                assertFalse(preset.name, body.has("tool_choice"))
            } else {
                assertEquals(preset.name, "auto", body.optString("tool_choice"))
            }
        }
    }

    @Test
    fun toolChoiceIsAbsentWhenThereAreNoTools() {
        val body =
            ChatRequest.build(
                config(BrainPreset.GROQ),
                "system prompt",
                listOf(ChatMessage(ChatMessage.USER, "goal")),
                null,
            )
        assertFalse(body.has("tools"))
        assertFalse(body.has("tool_choice"))
    }

    @Test
    fun nothingOnTheNeverSentListIsEverEmitted() {
        for (preset in BrainPreset.values()) {
            val body = build(preset)
            for (field in ChatRequest.NEVER_SENT) {
                assertFalse(preset.name + "/" + field, body.has(field))
            }
        }
    }

    @Test
    fun temperatureIsNeverExactlyZero() {
        for (preset in BrainPreset.values()) {
            val body = build(preset)
            assertEquals(preset.name, 0.1, body.getDouble("temperature"), 1e-9)
            assertTrue(preset.name, body.getDouble("temperature") > 0.0)
        }
    }

    @Test
    fun streamIsOnlySentToAnEndpointThatDocumentsIt() {
        for (preset in BrainPreset.values()) {
            val body = build(preset)
            if (preset.supportsStreaming) {
                assertFalse(preset.name, body.getBoolean("stream"))
            } else {
                assertFalse(preset.name, body.has("stream"))
            }
        }
    }

    @Test
    fun assistantToolCallsAndToolRepliesRoundTrip() {
        val messages =
            listOf(
                ChatMessage(ChatMessage.USER, "turn on battery saver"),
                ChatMessage(
                    ChatMessage.ASSISTANT,
                    null,
                    listOf(ChatMessage.Call("call_1", "tap", "{\"node_id\":4}")),
                ),
                ChatMessage(ChatMessage.TOOL, "ok", toolCallId = "call_1"),
            )
        val body =
            ChatRequest.build(config(BrainPreset.GROQ), "system prompt", messages, tools())
        val array = body.getJSONArray("messages")
        assertEquals(4, array.length())
        assertEquals("system", array.getJSONObject(0).getString("role"))

        val assistant = array.getJSONObject(2)
        assertEquals("assistant", assistant.getString("role"))
        assertTrue(assistant.isNull("content"))
        val calls = assistant.getJSONArray("tool_calls")
        assertEquals(1, calls.length())
        assertEquals("call_1", calls.getJSONObject(0).getString("id"))
        assertEquals("function", calls.getJSONObject(0).getString("type"))
        assertEquals(
            "tap",
            calls.getJSONObject(0).getJSONObject("function").getString("name"),
        )
        assertEquals(
            "{\"node_id\":4}",
            calls.getJSONObject(0).getJSONObject("function").getString("arguments"),
        )

        val tool = array.getJSONObject(3)
        assertEquals("tool", tool.getString("role"))
        assertEquals("call_1", tool.getString("tool_call_id"))
        // Groq answers 400 for messages[].name and nothing needs it.
        assertFalse(tool.has("name"))
    }

    @Test
    fun theKeyIsNowhereInTheBody() {
        val body =
            ChatRequest.build(
                config(BrainPreset.GROQ),
                "system prompt",
                listOf(ChatMessage(ChatMessage.USER, "goal")),
                tools(),
            )
        assertFalse(body.toString().contains("gsk_"))
        assertFalse(body.toString().contains("Bearer"))
        assertFalse(body.has("api_key"))
        assertFalse(body.has("key"))
    }

    @Test
    fun everyPresetBaseUrlIsUsableAsGiven() {
        for (preset in BrainPreset.values()) {
            val url = preset.defaultBaseUrl
            if (url.isEmpty()) {
                // Custom is the one preset with nothing to prefill.
                assertEquals(BrainPreset.CUSTOM, preset)
                continue
            }
            assertNull(preset.name, BrainUrl.reject(url))
            assertFalse(preset.name, url.endsWith("/"))
            assertFalse(preset.name, url.endsWith("/chat/completions"))
            if (preset.local) {
                assertTrue(preset.name, url.startsWith("http://"))
                assertTrue(preset.name, BrainUrl.isPrivateHost(BrainUrl.hostOf(url)!!))
            } else {
                assertTrue(preset.name, url.startsWith("https://"))
            }
            assertEquals(url + "/chat/completions", config(preset).endpoint())
        }
    }

    @Test
    fun plainHttpIsRefusedForAPublicAddressAndAllowedOnTheLan() {
        assertNull(BrainUrl.reject("http://127.0.0.1:8080/v1"))
        assertNull(BrainUrl.reject("http://192.168.4.7:11434/v1"))
        assertNull(BrainUrl.reject("http://10.0.0.5:8080/v1"))
        assertNull(BrainUrl.reject("http://172.16.9.9:8080/v1"))
        assertNull(BrainUrl.reject("http://[fd00::1]:8080/v1"))
        assertTrue(BrainUrl.reject("http://api.example.com/v1") != null)
        assertTrue(BrainUrl.reject("http://8.8.8.8/v1") != null)
        // A name is never private however it is spelled.
        assertTrue(BrainUrl.reject("http://localhost.attacker.example/v1") != null)
        assertTrue(BrainUrl.reject("ftp://example.com/v1") != null)
        assertTrue(BrainUrl.reject("https://api.groq.com/openai/v1/chat/completions") != null)
        assertTrue(BrainUrl.reject("") != null)
    }
}
