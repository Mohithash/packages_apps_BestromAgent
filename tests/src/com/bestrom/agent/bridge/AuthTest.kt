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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pairing code, the token and the three strike cooldown. */
class AuthTest {

    private val t0 = 1_000_000L

    @Test
    fun theRightCodePairsOnce() {
        val auth = Auth()
        auth.start(t0)
        val result = auth.pair(auth.currentCode(), t0)
        assertTrue(result is Auth.PairResult.Ok)
        val token = (result as Auth.PairResult.Ok).token
        // 32 random bytes, base64url, no padding.
        assertEquals(43, token.length)
        assertFalse(token.contains("="))
        assertTrue(auth.isPaired())
        assertTrue(auth.verify(token))
        assertFalse(auth.verify(token + "x"))
    }

    @Test
    fun anExpiredCodeIsRefused() {
        val auth = Auth()
        auth.start(t0)
        val code = auth.currentCode()
        val late = t0 + Auth.CODE_VALID_MS + 1
        assertTrue(auth.pair(code, late) is Auth.PairResult.BadCode)
    }

    @Test
    fun threeWrongCodesRegenerateAndCoolDown() {
        val auth = Auth()
        auth.start(t0)
        val original = auth.currentCode()
        val wrong = if (original == "000000") "111111" else "000000"

        assertTrue(auth.pair(wrong, t0) is Auth.PairResult.BadCode)
        assertTrue(auth.pair(wrong, t0) is Auth.PairResult.BadCode)
        assertTrue(auth.pair(wrong, t0) is Auth.PairResult.BadCode)

        // The third strike regenerated the code and opened a cooldown, so even
        // the code now on screen is refused until it closes.
        val cooled = auth.pair(auth.currentCode(), t0 + 1)
        assertTrue(cooled is Auth.PairResult.Cooldown)
        assertTrue((cooled as Auth.PairResult.Cooldown).retryAfterMs > 0)

        val after = auth.pair(auth.currentCode(), t0 + Auth.COOLDOWN_MS + 1)
        assertTrue(after is Auth.PairResult.Ok)
    }

    @Test
    fun clearWipesBothSecrets() {
        val auth = Auth()
        auth.start(t0)
        val ok = auth.pair(auth.currentCode(), t0) as Auth.PairResult.Ok
        auth.clear()
        assertFalse(auth.isPaired())
        assertFalse(auth.verify(ok.token))
        assertEquals("", auth.currentCode())
    }

    @Test
    fun theCompareDoesNotStopAtTheFirstDifference() {
        // Correctness of the constant time compare. The absence of an early
        // return is a property of the implementation, not something a unit test
        // can observe; what is asserted here is that the result does not depend
        // on where the difference falls, nor on the lengths matching.
        assertTrue(Auth.constantTimeEquals("123456", "123456"))
        assertFalse(Auth.constantTimeEquals("923456", "123456"))
        assertFalse(Auth.constantTimeEquals("123459", "123456"))
        assertFalse(Auth.constantTimeEquals("12345", "123456"))
        assertFalse(Auth.constantTimeEquals("1234567", "123456"))
        assertFalse(Auth.constantTimeEquals("", "123456"))
        assertFalse(Auth.constantTimeEquals(null, "123456"))
        assertFalse(Auth.constantTimeEquals("123456", null))
    }
}
