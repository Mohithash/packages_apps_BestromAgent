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

import java.io.ByteArrayOutputStream
import java.io.InputStream
import com.bestrom.agent.runner.InjectionFilter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.Random
import java.util.TimeZone
import javax.net.ssl.HttpsURLConnection
import org.json.JSONArray

/**
 * The only network call in the app.
 *
 * One transport - the platform's - and one URL, <baseUrl>/chat/completions,
 * built from the configured base URL and nothing else. There is no vendored
 * HTTP library to audit and no second place a request could be built.
 *
 * This file contains no logging statement, and neither does anything else in
 * this package: the key and the whole request body pass through here, and a
 * build gate asserts the absence rather than trusting a review to catch it.
 */
class OpenAiCompatClient(
    private val config: BrainConfig,
    private val keySupplier: () -> ApiKey,
    private val sleeper: (Long) -> Unit = { if (it > 0) Thread.sleep(it) },
    private val jitter: (Long) -> Long = { defaultJitter(it) },
) {

    companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000

        /**
         * How much of a response body is ever read into memory.
         *
         * No chat completion this client asks for comes close: the ceiling is
         * against an endpoint answering with something else entirely.
         */
        const val MAX_BODY_BYTES = 1024 * 1024

        /**
         * What the two LAN servers are sent instead of a key.
         *
         * Ollama requires the header and ignores its value; llama.cpp only
         * needs one when it was started with --api-key.
         */
        const val LOCAL_KEY = "local"

        /** 429 and 5xx: three attempts, and never more than half a minute of waiting. */
        const val MAX_SERVER_ATTEMPTS = 3
        const val MAX_BACKOFF_TOTAL_MS = 30_000L

        /** Connect, DNS and TLS failures: two attempts, 1 s then 2 s. */
        const val MAX_NETWORK_ATTEMPTS = 2

        /** A body that is not a chat completion is worth exactly one more try. */
        const val MAX_BAD_BODY_ATTEMPTS = 2

        /** Anthropic's overloaded, treated exactly like 503. */
        const val HTTP_OVERLOADED = 529

        /**
         * The only statuses worth sending the same request again for.
         *
         * Named rather than "429 or anything above 500": 501 and 505 are
         * permanent, and retrying them costs two more requests and up to half
         * a minute of backoff before the same answer.
         */
        val RETRY_STATUS: Set<Int> = setOf(429, 500, 502, 503, HTTP_OVERLOADED)

        private val random = Random()

        /** The backoff carries +/-20% so a fleet does not retry in lockstep. */
        @JvmStatic
        fun defaultJitter(ms: Long): Long {
            val span = ms / 5
            if (span <= 0) return ms
            return ms - span + (random.nextDouble() * 2 * span).toLong()
        }

        /** Retry-After is integer seconds or an HTTP-date; both are accepted. */
        @JvmStatic
        fun retryAfterMs(header: String?, nowMs: Long): Long? {
            if (header.isNullOrBlank()) return null
            val seconds = header.trim().toLongOrNull()
            if (seconds != null) return (seconds * 1000L).coerceAtLeast(0L)
            return try {
                val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                format.timeZone = TimeZone.getTimeZone("GMT")
                val at = format.parse(header.trim()) ?: return null
                (at.time - nowMs).coerceAtLeast(0L)
            } catch (e: Exception) {
                null
            }
        }
    }

    sealed class Outcome {
        class Ok(
            val response: ChatResponse,
            val sentChars: Int,
            val receivedChars: Int,
            val durationMs: Long,
        ) : Outcome()

        class Fail(val error: BrainError, val durationMs: Long) : Outcome()
    }

    /** The connection currently in flight, so Stop does not wait out a read. */
    @Volatile private var live: HttpURLConnection? = null

    @Volatile private var cancelled = false

    /**
     * Drops the connection under the read.
     *
     * A stop is never deferred to the end of a sixty second generation: the
     * socket goes and the read fails immediately.
     */
    fun cancel() {
        cancelled = true
        try {
            live?.disconnect()
        } catch (e: Exception) {
            // The call is being abandoned either way.
        }
    }

    fun complete(
        systemPrompt: String,
        messages: List<ChatMessage>,
        tools: JSONArray?,
        maxTokens: Int = ChatRequest.MAX_TOKENS,
    ): Outcome {
        val started = System.currentTimeMillis()
        val rejection = BrainUrl.reject(config.baseUrl)
        if (rejection != null) {
            return Outcome.Fail(BrainError.Network("base url refused", rejection), 0)
        }
        val authorization =
            when (val bearer = bearer()) {
                is Bearer.Failed -> return Outcome.Fail(bearer.error, elapsed(started))
                is Bearer.Header -> bearer.value
            }
        val body = ChatRequest.build(config, systemPrompt, messages, tools, maxTokens).toString()
        val bytes = body.toByteArray(Charsets.UTF_8)

        var serverAttempts = 0
        var networkAttempts = 0
        var badBodyAttempts = 0
        var waited = 0L
        var backoff = 1000L

        while (true) {
            if (cancelled) return Outcome.Fail(BrainError.Cancelled, elapsed(started))
            val attempt = send(bytes, authorization)

            when (attempt) {
                is Attempt.Body -> {
                    if (attempt.status == HttpURLConnection.HTTP_OK) {
                        when (val parsed = ChatResponse.parse(attempt.text)) {
                            is ChatResponse.Parsed.Ok ->
                                return Outcome.Ok(
                                    parsed.response,
                                    body.length,
                                    attempt.text.length,
                                    elapsed(started),
                                )
                            is ChatResponse.Parsed.Bad -> {
                                badBodyAttempts++
                                if (badBodyAttempts >= MAX_BAD_BODY_ATTEMPTS) {
                                    return Outcome.Fail(parsed.error, elapsed(started))
                                }
                                continue
                            }
                        }
                    }
                    val status = attempt.status
                    if (status == HttpURLConnection.HTTP_UNAUTHORIZED ||
                        status == HttpURLConnection.HTTP_FORBIDDEN
                    ) {
                        return Outcome.Fail(BrainError.Unauthorized, elapsed(started))
                    }
                    if (RETRY_STATUS.contains(status)) {
                        serverAttempts++
                        val fromHeader = retryAfterMs(attempt.retryAfter, System.currentTimeMillis())
                        val wait = fromHeader ?: jitter(backoff)
                        if (serverAttempts >= MAX_SERVER_ATTEMPTS ||
                            waited + wait > MAX_BACKOFF_TOTAL_MS
                        ) {
                            return Outcome.Fail(
                                if (status == 429) BrainError.RateLimited(wait)
                                else BrainError.Overloaded,
                                elapsed(started),
                            )
                        }
                        sleeper(wait)
                        waited += wait
                        backoff *= 2
                        continue
                    }
                    return Outcome.Fail(
                        BrainError.Rejected(status, providerMessage(attempt.text)),
                        elapsed(started),
                    )
                }
                is Attempt.ReadTimeout ->
                    // Not retried: a slow generation retried is paid for twice.
                    return Outcome.Fail(
                        BrainError.Network("read timed out"),
                        elapsed(started),
                    )
                is Attempt.Cancelled -> return Outcome.Fail(BrainError.Cancelled, elapsed(started))
                is Attempt.Unreachable -> {
                    networkAttempts++
                    if (networkAttempts >= MAX_NETWORK_ATTEMPTS) {
                        return Outcome.Fail(BrainError.Network(attempt.why), elapsed(started))
                    }
                    val wait = 1000L * networkAttempts
                    sleeper(wait)
                    continue
                }
            }
        }
    }

    // ------------------------------------------------------------------ one try

    private sealed class Attempt {
        class Body(val status: Int, val text: String, val retryAfter: String?) : Attempt()

        class Unreachable(val why: String) : Attempt()

        object ReadTimeout : Attempt()

        object Cancelled : Attempt()
    }

    private fun send(bytes: ByteArray, authorization: String): Attempt {
        var wrote = false
        var connection: HttpURLConnection? = null
        try {
            // One parse, and both facts read off the same object. The check
            // and the socket must not be able to disagree about which host
            // this is: that disagreement is how a key leaves in cleartext.
            val url = BrainUrl.parse(config.endpoint())
                ?: return Attempt.Unreachable("the endpoint is not a URL")
            val https = url.protocol.equals("https", ignoreCase = true)
            val host = BrainUrl.hostOf(url) ?: return Attempt.Unreachable("the endpoint has no host")
            if (!https && !BrainUrl.isPrivateHost(host)) {
                return Attempt.Unreachable("plain http to a public address")
            }
            val opened =
                url.openConnection() as? HttpURLConnection
                    ?: return Attempt.Unreachable("the endpoint is not http")
            connection = opened
            // The presence of this cast is also the verify gate's evidence that
            // the app rides the platform TLS stack and not a raw socket.
            if (https && opened !is HttpsURLConnection) {
                return Attempt.Unreachable("the https endpoint did not open a TLS connection")
            }
            live = opened
            opened.requestMethod = "POST"
            opened.connectTimeout = CONNECT_TIMEOUT_MS
            opened.readTimeout = READ_TIMEOUT_MS
            opened.doOutput = true
            opened.useCaches = false
            // A redirect would hand the Authorization header to whatever host
            // the 3xx names. It is answered as a refusal instead.
            opened.instanceFollowRedirects = false
            opened.setFixedLengthStreamingMode(bytes.size)
            opened.setRequestProperty("Content-Type", "application/json")
            opened.setRequestProperty("Accept", "application/json")
            opened.setRequestProperty("Authorization", "Bearer " + authorization)
            if (config.preset == BrainPreset.ANTHROPIC && config.workspaceId.isNotEmpty()) {
                opened.setRequestProperty("anthropic-workspace-id", config.workspaceId)
            }

            opened.outputStream.use { it.write(bytes) }
            wrote = true

            val status = opened.responseCode
            val stream: InputStream? =
                if (status in 200..299) opened.inputStream else opened.errorStream
            val text = stream?.use { read(it) } ?: ""
            return Attempt.Body(status, text, opened.getHeaderField("Retry-After"))
        } catch (e: SocketTimeoutException) {
            if (cancelled) return Attempt.Cancelled
            return if (wrote) Attempt.ReadTimeout else Attempt.Unreachable("connect timed out")
        } catch (e: Exception) {
            if (cancelled) return Attempt.Cancelled
            return Attempt.Unreachable(e.javaClass.simpleName)
        } finally {
            live = null
            try {
                connection?.disconnect()
            } catch (e: Exception) {
                // Nothing to do about a connection that will not close.
            }
        }
    }

    /** What goes in the Authorization header, or the reason there is nothing. */
    private sealed class Bearer {
        class Header(val value: String) : Bearer()

        class Failed(val error: BrainError) : Bearer()
    }

    /**
     * The key for this endpoint, resolved once before a socket is opened.
     *
     * A key stored for a cloud preset never reaches a LAN server: the two
     * local presets speak the placeholder and nothing else, whatever is in the
     * store when the preset changes.
     */
    private fun bearer(): Bearer {
        if (config.preset.local) return Bearer.Header(LOCAL_KEY)
        return when (val key = keySupplier()) {
            is ApiKey.Present -> Bearer.Header(key.value)
            ApiKey.Absent ->
                if (config.preset.keyRequired) Bearer.Failed(BrainError.KeyMissing)
                else Bearer.Header(LOCAL_KEY)
            // A key that is stored and cannot be opened is not a missing key,
            // and sending the placeholder instead would report the provider's
            // 401 as a bad key the user would then replace for nothing.
            ApiKey.Unavailable -> Bearer.Failed(BrainError.KeyUnavailable)
        }
    }

    private fun read(stream: InputStream): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val n = stream.read(buffer)
            if (n < 0) break
            val room = MAX_BODY_BYTES - total
            if (room <= 0) break
            val written = minOf(n, room)
            out.write(buffer, 0, written)
            // What was kept, not what was read: counting the read overshoots
            // the ceiling by up to one buffer.
            total += written
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** The provider's own error.message, and only that, sanitised and truncated. */
    private fun providerMessage(text: String): String =
        try {
            val root = org.json.JSONObject(text)
            val error = root.optJSONObject("error")
            // optString answers "" and never null, so the elvis never fired
            // for a provider that put its text at the top level beside an
            // empty error object.
            val message =
                error?.optString("message")?.ifEmpty { null } ?: root.optString("message")
            // The endpoint chooses this text and the transcript renders it.
            InjectionFilter.sanitise(message, BrainError.MAX_DETAIL)
        } catch (e: Exception) {
            ""
        }

    private fun elapsed(started: Long): Long = System.currentTimeMillis() - started
}
