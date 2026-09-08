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

        /** A token is already out. Re-pairing goes through New code on the phone. */
        object AlreadyPaired : PairResult()
    }

    /**
     * Told whenever the code or the cooldown changes, whoever changed it.
     *
     * The screen renders what this publishes, so a rotation triggered by three
     * wrong codes over the wire cannot leave it showing a dead secret.
     */
    fun interface CodeListener {
        fun onCodeChanged(code: String, cooldownUntilMs: Long)
    }

    @Volatile
    private var code: String = ""

    @Volatile
    private var codeIssuedAtMs: Long = 0

    @Volatile
    private var token: String? = null

    private var strikes = 0
    private var cooldownUntilMs = 0L

    @Volatile
    private var listener: CodeListener? = null

    /** Installs the rotation listener and publishes the current state to it. */
    @Synchronized
    fun setCodeListener(newListener: CodeListener?) {
        listener = newListener
        publish()
    }

    private fun publish() {
        listener?.onCodeChanged(code, cooldownUntilMs)
    }

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
        publish()
        return code
    }

    /**
     * Rotates the code and drops the pairing with it.
     *
     * This is the New code button, and the only way to pair a second client:
     * a successful pair burns its code, and pair() refuses while a token is
     * out. It also lifts a cooldown, so a peer guessing codes cannot keep the
     * maintainer from re-pairing.
     */
    @Synchronized
    fun reissue(nowMs: Long): String {
        token = null
        strikes = 0
        cooldownUntilMs = 0
        return newCode(nowMs)
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
        publish()
    }

    /**
     * Checks a pairing code. Three wrong ones regenerate the code, start a
     * cooldown and, at the call site, close the connection. A code is good for
     * exactly one pairing and only while no token is out.
     */
    @Synchronized
    fun pair(candidate: String?, nowMs: Long): PairResult {
        if (nowMs < cooldownUntilMs) {
            return PairResult.Cooldown(cooldownUntilMs - nowMs)
        }
        if (token != null) return PairResult.AlreadyPaired
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
        val expiresAtMs = codeIssuedAtMs + CODE_VALID_MS
        // Single use. The six digits that paired this client never pair
        // another, so anyone who reads them off the screen afterwards is late.
        code = ""
        codeIssuedAtMs = 0
        publish()
        return PairResult.Ok(issued, expiresAtMs)
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
