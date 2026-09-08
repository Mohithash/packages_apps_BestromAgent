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


package com.bestrom.agent.bridge

import java.security.SecureRandom
import java.util.Base64

/**
 * The pairing code and the session token.
 *
 * Both live in memory only. They are created when the bridge starts and zeroed
 * when it stops, which means they do not survive a reboot either.
 */
class Auth(private val random: SecureRandom = SecureRandom()) {

    companion object {
        const val CODE_VALID_MS = 10 * 60 * 1000L
        const val COOLDOWN_MS = 30 * 1000L
        const val MAX_STRIKES = 3

        /**
         * Compares two strings without an early return.
         *
         * The length is folded into the accumulator instead of short-circuiting,
         * so a wrong length is not distinguishable from a wrong character.
         */
        @JvmStatic
        fun constantTimeEquals(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            val x = a.toByteArray(Charsets.UTF_8)
            val y = b.toByteArray(Charsets.UTF_8)
            var diff = x.size xor y.size
            val n = maxOf(x.size, y.size)
            for (i in 0 until n) {
                val xi = if (i < x.size) x[i].toInt() else 0
                val yi = if (i < y.size) y[i].toInt() else 0
                diff = diff or (xi xor yi)
            }
            return diff == 0
        }
    }

    sealed class PairResult {
        class Ok(val token: String, val expiresAtMs: Long) : PairResult()
        object BadCode : PairResult()
        class Cooldown(val retryAfterMs: Long) : PairResult()
    }

    @Volatile
    private var code: String = ""

    @Volatile
    private var codeIssuedAtMs: Long = 0

    @Volatile
    private var token: String? = null

    private var strikes = 0
    private var cooldownUntilMs = 0L

    /** Generates the first code. Called when the bridge binds its socket. */
    @Synchronized
    fun start(nowMs: Long) {
        newCode(nowMs)
        token = null
        strikes = 0
        cooldownUntilMs = 0
    }

    @Synchronized
    fun newCode(nowMs: Long): String {
        val n = random.nextInt(1_000_000)
        code = String.format("%06d", n)
        codeIssuedAtMs = nowMs
        return code
    }

    @Synchronized
    fun currentCode(): String = code

    @Synchronized
    fun isPaired(): Boolean = token != null

    /** Wipes both secrets. Called on the off sequence. */
    @Synchronized
    fun clear() {
        code = ""
        token = null
        strikes = 0
        cooldownUntilMs = 0
        codeIssuedAtMs = 0
    }

    /**
     * Checks a pairing code. Three wrong ones regenerate the code, start a
     * cooldown and, at the call site, close the connection.
     */
    @Synchronized
    fun pair(candidate: String?, nowMs: Long): PairResult {
        if (nowMs < cooldownUntilMs) {
            return PairResult.Cooldown(cooldownUntilMs - nowMs)
        }
        val expired = codeIssuedAtMs == 0L || nowMs - codeIssuedAtMs > CODE_VALID_MS
        val match = constantTimeEquals(code, candidate) && !expired
        if (!match) {
            strikes++
            if (strikes >= MAX_STRIKES) {
                strikes = 0
                cooldownUntilMs = nowMs + COOLDOWN_MS
                newCode(nowMs)
            }
            return PairResult.BadCode
        }
        strikes = 0
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val issued = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        token = issued
        return PairResult.Ok(issued, codeIssuedAtMs + CODE_VALID_MS)
    }

    /** Re-authenticates a reconnecting client. */
    @Synchronized
    fun verify(candidate: String?): Boolean {
        val current = token ?: return false
        return constantTimeEquals(current, candidate)
    }

    /** Whether the pairing code is still inside its ten minute window. */
    @Synchronized
    fun codeExpiresAtMs(): Long = codeIssuedAtMs + CODE_VALID_MS
}
