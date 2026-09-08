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

/**
 * Which endpoint the user picked, and what that endpoint will accept.
 *
 * The table is a compile-time constant so the shape of every request can be
 * checked on the host: which max-tokens field name is legal, whether
 * tool_choice is understood, and whether the base URL is https. Nothing here
 * holds a key.
 */
enum class BrainPreset(
    val label: String,
    val defaultBaseUrl: String,
    val models: List<String>,
    /** max_tokens everywhere but OpenAI, which rejects it on the GPT-5 line. */
    val maxTokensField: String,
    val supportsToolChoice: Boolean,
    val supportsStreaming: Boolean,
    val keyRequired: Boolean,
    /** Only these two may be reached over plain http, and only on a private address. */
    val local: Boolean = false,
) {
    ANTHROPIC(
        "Anthropic",
        "https://api.anthropic.com/v1",
        listOf("claude-haiku-4-5", "claude-sonnet-5"),
        "max_tokens",
        true,
        true,
        true,
    ),
    OPENAI(
        "OpenAI",
        "https://api.openai.com/v1",
        listOf("gpt-5-nano", "gpt-5-mini"),
        "max_completion_tokens",
        true,
        true,
        true,
    ),
    GEMINI(
        "Gemini",
        "https://generativelanguage.googleapis.com/v1beta/openai",
        listOf("gemini-3-flash", "gemini-2.5-flash-lite"),
        "max_tokens",
        true,
        true,
        true,
    ),
    GROQ(
        "Groq",
        "https://api.groq.com/openai/v1",
        listOf("openai/gpt-oss-20b", "llama-3.3-70b-versatile"),
        "max_tokens",
        true,
        true,
        true,
    ),
    CEREBRAS(
        "Cerebras",
        "https://api.cerebras.ai/v1",
        listOf("gpt-oss-120b", "llama-3.3-70b"),
        "max_tokens",
        true,
        true,
        true,
    ),
    OPENROUTER(
        "OpenRouter",
        "https://openrouter.ai/api/v1",
        listOf("openai/gpt-oss-20b", "google/gemini-2.5-flash-lite"),
        "max_tokens",
        true,
        true,
        true,
    ),

    // The two LAN servers are separate rows because they differ in the one
    // field that changes the request: Ollama returns an error for tool_choice
    // and llama.cpp accepts it. A single "local" preset would have to send the
    // conservative shape to both and hide the reason.
    LLAMA_CPP(
        "Local llama.cpp",
        "http://192.168.1.10:8080/v1",
        listOf("(whatever the server serves)"),
        "max_tokens",
        true,
        true,
        false,
        local = true,
    ),
    OLLAMA(
        "Local Ollama",
        "http://192.168.1.10:11434/v1",
        listOf("(whatever the server serves)"),
        "max_tokens",
        false,
        true,
        false,
        local = true,
    ),
    CUSTOM(
        "Custom",
        "",
        emptyList(),
        "max_tokens",
        true,
        false,
        true,
    );

    companion object {
        @JvmStatic
        fun byName(name: String?): BrainPreset =
            values().firstOrNull { it.name == name } ?: GROQ
    }
}

/**
 * The brain the user configured. The key is deliberately absent: it lives in
 * ApiKeyStore and never travels with the rest of the settings.
 */
data class BrainConfig(
    val preset: BrainPreset = BrainPreset.GROQ,
    val baseUrl: String = BrainPreset.GROQ.defaultBaseUrl,
    val model: String = "",
    /** Anthropic only, and only when the key can see more than one workspace. */
    val workspaceId: String = "",
    val screenshots: Boolean = false,
    val autonomous: Boolean = false,
    val stepCap: Int = DEFAULT_STEP_CAP,
    val tokenCap: Int = DEFAULT_TOKEN_CAP,
) {
    companion object {
        const val DEFAULT_STEP_CAP = 25
        const val MIN_STEP_CAP = 1
        const val MAX_STEP_CAP = 100

        const val DEFAULT_TOKEN_CAP = 200000
        const val MIN_TOKEN_CAP = 10000
        const val MAX_TOKEN_CAP = 2000000
    }

    /** The one URL this app ever builds. */
    fun endpoint(): String = baseUrl.trimEnd('/') + "/chat/completions"

    fun host(): String = BrainUrl.hostOf(baseUrl) ?: ""

    /**
     * Screenshots reach the model only when the user turned them on.
     *
     * There was a second flag for "the model can see", set from the same
     * switch in both directions, so it could only ever repeat this one. The
     * switch says what it costs and what the model has to be.
     */
    fun sendScreenshots(): Boolean = screenshots

    fun configured(): Boolean =
        baseUrl.isNotEmpty() && model.isNotEmpty() && BrainUrl.reject(baseUrl) == null
}

/**
 * Base URL rules, kept apart from the client so they can be checked without a
 * network.
 *
 * https everywhere, with exactly one exception: a literal private, loopback or
 * link-local address, which is the llama.cpp and Ollama case and the only
 * configuration that sends nothing off the user's own network.
 *
 * The host is read with java.net.URL and never by hand. A hand parser that
 * ends the authority one character later than the parser which opens the
 * socket is not a style difference: "http://evil.example.com#@127.0.0.1/v1"
 * reads as private to one and as evil.example.com to the other, and the key
 * goes to the second one in cleartext.
 */
object BrainUrl {

    /**
     * Characters with no legitimate use in a chat-completions base URL.
     *
     * A fragment or a query is where the two parsers disagree, a backslash is
     * where a browser and a library disagree, and whitespace hides both.
     */
    private val FORBIDDEN = charArrayOf('#', '?', '\\')

    /** null when the URL is usable, otherwise the sentence to show the user. */
    @JvmStatic
    fun reject(baseUrl: String): String? {
        val url = baseUrl.trim()
        if (url.isEmpty()) return "The base URL is empty."
        for (c in url) {
            if (c.isWhitespace()) return "The base URL must not contain spaces."
        }
        for (c in FORBIDDEN) {
            if (url.indexOf(c) >= 0) return "The base URL must not contain \"$c\"."
        }
        val lower = url.lowercase()
        val https = lower.startsWith("https://")
        if (!https && !lower.startsWith("http://")) {
            return "The base URL must start with https:// or http://."
        }
        val parsed = parse(url) ?: return "That base URL cannot be read."
        if (!parsed.userInfo.isNullOrEmpty()) {
            return "The base URL must not carry a user name or a password."
        }
        val host = hostOf(parsed) ?: return "That base URL has no host."
        if (lower.endsWith("/chat/completions")) {
            return "Leave off /chat/completions; it is added for you."
        }
        if (!https && !isPrivateHost(host)) {
            return "Plain http is only allowed to a server on your own network."
        }
        return null
    }

    /** The platform's parser, or null when it will not have it. */
    @JvmStatic
    fun parse(url: String): java.net.URL? =
        try {
            java.net.URL(url)
        } catch (e: Exception) {
            null
        }

    /** The host java.net.URL reports, lowercased and without IPv6 brackets. */
    @JvmStatic
    fun hostOf(url: java.net.URL): String? {
        val host = url.host ?: return null
        return host.removeSurrounding("[", "]").lowercase().ifEmpty { null }
    }

    /** The host part of a base URL, without the port or the brackets. */
    @JvmStatic
    fun hostOf(baseUrl: String): String? {
        val parsed = parse(baseUrl.trim()) ?: return null
        return hostOf(parsed)
    }

    /**
     * True for a literal address the traffic cannot leave the local network to
     * reach. A NAME is never private: "localhost.attacker.example" resolves
     * wherever its owner says, so only literals count.
     */
    @JvmStatic
    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase()
        if (h == "localhost" || h == "::1" || h == "0:0:0:0:0:0:0:1") return true
        val colon = h.indexOf(':')
        if (colon >= 0) {
            // IPv6: unique local fc00::/7 and link local fe80::/10.
            val first = h.take(colon)
            if (first.isEmpty()) return false
            val group = first.toIntOrNull(16) ?: return false
            if (group ushr 9 == 0x7e) return true // fc00::/7
            if (group ushr 6 == 0x3fa) return true // fe80::/10
            return false
        }
        val parts = h.split('.')
        if (parts.size != 4) return false
        val octets = IntArray(4)
        for (i in 0 until 4) {
            val v = parts[i].toIntOrNull() ?: return false
            if (v < 0 || v > 255) return false
            octets[i] = v
        }
        if (octets[0] == 127) return true
        if (octets[0] == 10) return true
        if (octets[0] == 192 && octets[1] == 168) return true
        if (octets[0] == 172 && octets[1] in 16..31) return true
        if (octets[0] == 169 && octets[1] == 254) return true
        if (octets[0] == 100 && octets[1] in 64..127) return true
        return false
    }
}
