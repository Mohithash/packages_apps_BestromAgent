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

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The client against a fake server.
 *
 * Everything here talks to a com.sun.net.httpserver on the loopback address:
 * the retry counts, the backoff and the headers are properties of this code,
 * not of a provider, so they are checked without a key and without a network.
 */
class OpenAiCompatClientTest {

    private lateinit var server: HttpServer
    private val requests = AtomicInteger(0)
    private val headers = Collections.synchronizedList(ArrayList<Map<String, List<String>>>())
    private val bodies = Collections.synchronizedList(ArrayList<String>())

    /** What the fixture answers, one entry per request; the last repeats. */
    private var replies: List<Reply> = emptyList()

    private class Reply(val status: Int, val body: String, val retryAfter: String? = null)

    private val slept = ArrayList<Long>()

    @Before
    fun start() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/v1/chat/completions") { exchange: HttpExchange ->
            val n = requests.getAndIncrement()
            headers.add(HashMap(exchange.requestHeaders))
            bodies.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            val reply = replies[minOf(n, replies.size - 1)]
            if (reply.retryAfter != null) {
                exchange.responseHeaders.add("Retry-After", reply.retryAfter)
            }
            val bytes = reply.body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun stop() {
        server.stop(0)
    }

    private fun baseUrl(): String = "http://127.0.0.1:" + server.address.port + "/v1"

    private fun client(
        key: ApiKey = ApiKey.Present("sk-test-000"),
        url: String = baseUrl(),
        preset: BrainPreset = BrainPreset.GROQ,
    ): OpenAiCompatClient =
        OpenAiCompatClient(
            BrainConfig(
                preset = preset,
                baseUrl = url,
                model = "a-model",
            ),
            { key },
            // The waits are recorded rather than taken: a test that really
            // slept the backoff would take seven seconds to prove arithmetic.
            { slept.add(it) },
            // No jitter, so the backoff is exactly 1/2/4 and can be asserted.
            { it },
        )

    private fun call(client: OpenAiCompatClient): OpenAiCompatClient.Outcome =
        client.complete(
            "system prompt",
            listOf(ChatMessage(ChatMessage.USER, "turn on battery saver")),
            null,
        )

    private fun okBody(): String =
        """
        {"id":"1","model":"a-model","choices":[{"index":0,"finish_reason":"stop",
          "message":{"role":"assistant","content":"done"}}],
         "usage":{"prompt_tokens":10,"completion_tokens":2}}
        """

    private fun fail(outcome: OpenAiCompatClient.Outcome): BrainError {
        assertTrue("expected a failure", outcome is OpenAiCompatClient.Outcome.Fail)
        return (outcome as OpenAiCompatClient.Outcome.Fail).error
    }

    @Test
    fun twoHundredReturnsAChatResponse() {
        replies = listOf(Reply(200, okBody()))
        val outcome = call(client())
        assertTrue(outcome is OpenAiCompatClient.Outcome.Ok)
        val ok = outcome as OpenAiCompatClient.Outcome.Ok
        assertEquals("done", ok.response.text)
        assertEquals(12, ok.response.usage!!.total())
        assertEquals(1, requests.get())
    }

    @Test
    fun unauthorizedIsNeverRetried() {
        replies = listOf(Reply(401, """{"error":{"message":"bad key"}}"""))
        assertTrue(fail(call(client())) is BrainError.Unauthorized)
        assertEquals(1, requests.get())
        assertTrue(slept.isEmpty())
    }

    @Test
    fun forbiddenIsTreatedLikeABadKey() {
        replies = listOf(Reply(403, """{"error":{"message":"no"}}"""))
        assertTrue(fail(call(client())) is BrainError.Unauthorized)
        assertEquals(1, requests.get())
    }

    @Test
    fun rateLimitedHonoursRetryAfterAndRetries() {
        replies = listOf(Reply(429, "{}", retryAfter = "2"), Reply(200, okBody()))
        assertTrue(call(client()) is OpenAiCompatClient.Outcome.Ok)
        assertEquals(2, requests.get())
        assertEquals(listOf(2000L), slept)
    }

    @Test
    fun rateLimitedWithoutRetryAfterBacksOffOneTwoFour() {
        replies = listOf(Reply(429, "{}"))
        assertTrue(fail(call(client())) is BrainError.RateLimited)
        // Three attempts, so two waits: the third gives up rather than sleeping.
        assertEquals(3, requests.get())
        assertEquals(listOf(1000L, 2000L), slept)
    }

    @Test
    fun serverErrorsAreRetriedThreeTimes() {
        replies = listOf(Reply(500, "{}"))
        assertTrue(fail(call(client())) is BrainError.Overloaded)
        assertEquals(3, requests.get())
    }

    @Test
    fun anthropicOverloadedIsTreatedLikeServiceUnavailable() {
        replies = listOf(Reply(529, "{}"), Reply(200, okBody()))
        assertTrue(call(client()) is OpenAiCompatClient.Outcome.Ok)
        assertEquals(2, requests.get())
    }

    @Test
    fun aBodyOfHtmlIsARefusalAfterOneMoreTry() {
        replies = listOf(Reply(200, "<html><body>proxy error</body></html>"))
        val error = fail(call(client()))
        assertTrue(error is BrainError.BadResponse)
        assertTrue((error as BrainError.BadResponse).why.contains("HTML"))
        assertEquals(2, requests.get())
    }

    @Test
    fun otherFourHundredsAreTerminalAndKeepTheProvidersMessage() {
        replies =
            listOf(Reply(400, """{"error":{"message":"model `a-model` does not exist"}}"""))
        val error = fail(call(client()))
        assertTrue(error is BrainError.Rejected)
        assertEquals(400, (error as BrainError.Rejected).status)
        assertTrue(error.detail.contains("does not exist"))
        assertEquals(1, requests.get())
    }

    @Test
    fun theRequestCarriesTheKeyAndNothingThatNamesTheDevice() {
        replies = listOf(Reply(200, okBody()))
        call(client())
        val sent = headers[0].mapKeys { it.key.lowercase() }
        assertEquals(listOf("Bearer sk-test-000"), sent["authorization"])
        assertEquals(listOf("application/json"), sent["content-type"])
        assertEquals(listOf("application/json"), sent["accept"])
        // Attribution and workspace headers are not sent: a ROM does not put an
        // identifier on the user's own traffic.
        for (name in
            listOf("http-referer", "referer", "x-title", "anthropic-workspace-id", "x-device")) {
            assertNull(name, sent[name])
        }
        // And the key is in the header, not in the body.
        assertFalse(bodies[0].contains("sk-test-000"))
    }

    @Test
    fun anEmptyKeyBecomesThePlaceholderTheLanServersWant() {
        replies = listOf(Reply(200, okBody()))
        call(client(key = ApiKey.Absent, preset = BrainPreset.LLAMA_CPP))
        val sent = headers[0].mapKeys { it.key.lowercase() }
        // Ollama requires the header and ignores the value; llama.cpp only
        // wants one when it was started with --api-key.
        assertEquals(listOf("Bearer local"), sent["authorization"])
    }

    @Test
    fun aKeyStoredForACloudPresetIsNeverSentToALanServer() {
        replies = listOf(Reply(200, okBody()))
        // The key is still in the store; the preset is a LAN server now.
        call(client(key = ApiKey.Present("sk-live-123"), preset = BrainPreset.OLLAMA))
        val sent = headers[0].mapKeys { it.key.lowercase() }
        assertEquals(listOf("Bearer local"), sent["authorization"])
        assertFalse(bodies[0].contains("sk-live-123"))
    }

    @Test
    fun aKeyThatWillNotUnsealIsItsOwnSentenceAndSendsNothing() {
        val outcome = call(client(key = ApiKey.Unavailable))
        assertTrue(fail(outcome) is BrainError.KeyUnavailable)
        assertEquals(0, requests.get())
        // And a preset that needs a key with nothing stored says so.
        assertTrue(fail(call(client(key = ApiKey.Absent))) is BrainError.KeyMissing)
        assertEquals(0, requests.get())
    }

    @Test
    fun plainHttpIsRefusedForAPublicAddressBeforeAnythingIsSent() {
        val error = fail(call(client(url = "http://api.example.com/v1")))
        assertTrue(error is BrainError.Network)
        assertEquals(0, requests.get())
    }

    @Test
    fun aPrivateAddressIsReachedOverPlainHttp() {
        replies = listOf(Reply(200, okBody()))
        // 127.0.0.1 here; 192.168/10/172.16 are covered by the URL rules test.
        assertTrue(call(client()) is OpenAiCompatClient.Outcome.Ok)
        assertTrue(BrainUrl.isPrivateHost("192.168.1.10"))
        assertTrue(BrainUrl.isPrivateHost("10.0.0.5"))
        assertFalse(BrainUrl.isPrivateHost("8.8.8.8"))
    }

    @Test
    fun anUnreachablePortFailsTwiceAndThenGivesUp() {
        val socket = java.net.ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val port = socket.localPort
        socket.close()
        val error = fail(call(client(url = "http://127.0.0.1:$port/v1")))
        assertTrue(error is BrainError.Network)
        assertEquals("The brain could not be reached.", error.sentence)
        assertEquals(listOf(1000L), slept)
    }

    @Test
    fun cancelEndsTheCallWithoutWaitingForTheRead() {
        replies = listOf(Reply(200, okBody()))
        val client = client()
        client.cancel()
        assertTrue(fail(call(client)) is BrainError.Cancelled)
        assertEquals(0, requests.get())
    }

    @Test
    fun retryAfterAcceptsSecondsAndAnHttpDate() {
        assertEquals(2000L, OpenAiCompatClient.retryAfterMs("2", 0L))
        assertEquals(0L, OpenAiCompatClient.retryAfterMs("0", 0L))
        assertNull(OpenAiCompatClient.retryAfterMs(null, 0L))
        assertNull(OpenAiCompatClient.retryAfterMs("soon", 0L))
        val date = "Wed, 21 Oct 2026 07:28:00 GMT"
        val at = OpenAiCompatClient.retryAfterMs(date, 0L)
        assertTrue(at != null && at > 0L)
        // A date already in the past never asks for a negative wait.
        assertEquals(0L, OpenAiCompatClient.retryAfterMs(date, Long.MAX_VALUE / 2))
    }
}
