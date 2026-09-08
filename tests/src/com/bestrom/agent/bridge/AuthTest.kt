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
    fun aCodePairsExactlyOnce() {
        val auth = Auth()
        auth.start(t0)
        val code = auth.currentCode()
        assertTrue(auth.pair(code, t0) is Auth.PairResult.Ok)

        // The code is spent: nothing shows on the screen any more, and the
        // same digits do not pair a second client inside the ten minutes.
        assertEquals("", auth.currentCode())
        assertTrue(auth.pair(code, t0 + 1) is Auth.PairResult.AlreadyPaired)
    }

    @Test
    fun aSecondPairIsRefusedWhileATokenIsOut() {
        val auth = Auth()
        auth.start(t0)
        val first = auth.pair(auth.currentCode(), t0) as Auth.PairResult.Ok

        // Even a fresh code does not pair while the first client holds a
        // token; New code on the phone is what hands the session over.
        auth.newCode(t0 + 1)
        assertTrue(auth.pair(auth.currentCode(), t0 + 2) is Auth.PairResult.AlreadyPaired)

        val reissued = auth.reissue(t0 + 3)
        assertFalse(auth.isPaired())
        assertFalse(auth.verify(first.token))
        val second = auth.pair(reissued, t0 + 4)
        assertTrue(second is Auth.PairResult.Ok)
        assertFalse(first.token == (second as Auth.PairResult.Ok).token)
    }

    @Test
    fun theRotationListenerSeesEveryChange() {
        val auth = Auth()
        val seen = ArrayList<String>()
        var cooldown = 0L
        auth.setCodeListener { code, cooldownUntilMs ->
            seen.add(code)
            cooldown = cooldownUntilMs
        }
        auth.start(t0)
        val issued = auth.currentCode()
        assertEquals(issued, seen.last())

        // Three wrong codes rotate it, and the listener - not the caller that
        // happened to lose - is how the screen finds out.
        val wrong = if (issued == "000000") "111111" else "000000"
        repeat(3) { auth.pair(wrong, t0) }
        assertEquals(auth.currentCode(), seen.last())
        assertFalse(issued == seen.last())
        assertTrue(cooldown > t0)

        auth.clear()
        assertEquals("", seen.last())
    }

    @Test
    fun reissueLiftsTheCooldown() {
        val auth = Auth()
        auth.start(t0)
        val wrong = if (auth.currentCode() == "000000") "111111" else "000000"
        repeat(3) { auth.pair(wrong, t0) }
        assertTrue(auth.pair(auth.currentCode(), t0 + 1) is Auth.PairResult.Cooldown)

        // Otherwise a peer guessing codes could keep the maintainer from
        // pairing for as long as it liked.
        val fresh = auth.reissue(t0 + 2)
        assertTrue(auth.pair(fresh, t0 + 3) is Auth.PairResult.Ok)
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
