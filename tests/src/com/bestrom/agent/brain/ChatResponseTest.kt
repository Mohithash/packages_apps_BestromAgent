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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatResponseTest {

    private fun ok(body: String): ChatResponse {
        val parsed = ChatResponse.parse(body)
        assertTrue(
            "expected a parse, got " + (parsed as? ChatResponse.Parsed.Bad)?.error?.why,
            parsed is ChatResponse.Parsed.Ok,
        )
        return (parsed as ChatResponse.Parsed.Ok).response
    }

    private fun bad(body: String): BrainError.BadResponse {
        val parsed = ChatResponse.parse(body)
        assertTrue("expected a refusal", parsed is ChatResponse.Parsed.Bad)
        return (parsed as ChatResponse.Parsed.Bad).error
    }

    @Test
    fun parsesANormalOpenAiBody() {
        val response =
            ok(
                """
                {"id":"chatcmpl-1","model":"gpt-5-nano","choices":[
                  {"index":0,"finish_reason":"tool_calls","message":{
                     "role":"assistant","content":null,
                     "tool_calls":[{"id":"call_a","type":"function",
                        "function":{"name":"read_screen","arguments":"{\"max_nodes\":80}"}}]}}],
                 "usage":{"prompt_tokens":1200,"completion_tokens":40}}
                """
            )
        assertNull(response.text)
        assertEquals(1, response.toolCalls.size)
        assertEquals("call_a", response.toolCalls[0].id)
        assertEquals("read_screen", response.toolCalls[0].name)
        assertEquals("{\"max_nodes\":80}", response.toolCalls[0].argumentsJson)
        assertEquals("tool_calls", response.finishReason)
        assertEquals(1240, response.usage!!.total())
        assertEquals("gpt-5-nano", response.model)
    }

    @Test
    fun parsesAnAnthropicCompatBody() {
        // The compatibility layer always sends empty token details and no
        // service_tier; neither is read, and their absence must not matter.
        val response =
            ok(
                """
                {"id":"msg_1","model":"claude-haiku-4-5","object":"chat.completion",
                 "choices":[{"index":0,"finish_reason":"stop","message":
                   {"role":"assistant","content":"Battery saver is on."}}],
                 "usage":{"prompt_tokens":900,"completion_tokens":12,
                          "prompt_tokens_details":{},"completion_tokens_details":{}}}
                """
            )
        assertEquals("Battery saver is on.", response.text)
        assertTrue(response.toolCalls.isEmpty())
        assertEquals("stop", response.finishReason)
        assertEquals(912, response.usage!!.total())
    }

    @Test
    fun parsesAGroqBody() {
        val response =
            ok(
                """
                {"id":"chatcmpl-g","model":"openai/gpt-oss-20b","choices":[
                  {"index":0,"finish_reason":"tool_calls","message":{"role":"assistant",
                    "content":"","tool_calls":[{"id":"fc_1","type":"function",
                    "function":{"name":"tap","arguments":"{\"node_id\":12}"}}]}}],
                 "usage":{"prompt_tokens":700,"completion_tokens":18,"total_tokens":718},
                 "x_groq":{"id":"req_1"}}
                """
            )
        assertNull(response.text)
        assertEquals("tap", response.toolCalls[0].name)
        assertEquals(718, response.usage!!.total())
    }

    @Test
    fun parsesAnOllamaBodyWithNoUsage() {
        val response =
            ok(
                """
                {"model":"qwen3","choices":[{"index":0,"finish_reason":"stop",
                  "message":{"role":"assistant","content":"the network is Chandrika 5G"}}]}
                """
            )
        assertEquals("the network is Chandrika 5G", response.text)
        // null, not zero: the step guard has to know it must estimate.
        assertNull(response.usage)
    }

    @Test
    fun argumentsAreCarriedAsAStringAndParsedSeparately() {
        val response =
            ok(
                """
                {"choices":[{"index":0,"message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"c","type":"function",
                    "function":{"name":"tap","arguments":"{"}}]}}]}
                """
            )
        assertEquals("{", response.toolCalls[0].argumentsJson)
        // A broken argument object is the model's problem, not the parser's.
        var threw = false
        try {
            JSONObject(response.toolCalls[0].argumentsJson)
        } catch (e: Exception) {
            threw = true
        }
        assertTrue(threw)
    }

    @Test
    fun anEmptyChoicesArrayIsARefusal() {
        val error = bad("""{"id":"x","choices":[],"usage":{}}""")
        assertTrue(error.why.contains("choices"))
    }

    @Test
    fun anHtmlBodyNamesItselfRatherThanTheParser() {
        val error = bad("<html><head><title>502 Bad Gateway</title></head></html>")
        assertTrue(error.why.contains("HTML"))
        assertTrue(!error.why.contains("JSONException"))
    }

    @Test
    fun aBodyThatIsNotJsonIsARefusal() {
        assertTrue(bad("not json at all").why.contains("not JSON"))
        assertTrue(bad("   ").why.contains("empty"))
    }

    @Test
    fun anErrorBodyKeepsTheProvidersMessage() {
        val error = bad("""{"error":{"message":"model not found","type":"invalid_request"}}""")
        assertTrue(error.why.contains("model not found"))
    }

    @Test
    fun aParseNeverThrows() {
        for (body in listOf("{}", "[]", "{\"choices\":[{}]}", "{\"choices\":[1]}")) {
            assertNotNull(ChatResponse.parse(body))
        }
    }

    @Test
    fun twoToolCallsCannotShareAnId() {
        val body =
            """
            {"choices":[{"index":0,"message":{"role":"assistant","tool_calls":[
              {"id":"call_a","type":"function","function":{"name":"tap","arguments":"{}"}},
              {"id":"call_a","type":"function","function":{"name":"tap","arguments":"{}"}},
              {"id":"call_a","type":"function","function":{"name":"key","arguments":"{}"}}
            ]}}]}
            """
        val parsed = ChatResponse.parse(body)
        assertTrue(parsed is ChatResponse.Parsed.Ok)
        val calls = (parsed as ChatResponse.Parsed.Ok).response.toolCalls
        assertEquals(3, calls.size)
        // Two tool replies with one id is a 400 on some layers and a silently
        // dropped reply on others.
        assertEquals(3, calls.map { it.id }.toSet().size)
        assertEquals("call_a", calls[0].id)
    }
}
