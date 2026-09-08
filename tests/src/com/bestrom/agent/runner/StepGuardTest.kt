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


package com.bestrom.agent.runner

import com.bestrom.agent.brain.ChatResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StepGuardTest {

    @Test
    fun theStepCapTerminatesAtExactlyOneOverIt() {
        val guard = StepGuard(3, 100000)
        assertTrue(guard.nextStep())
        assertTrue(guard.nextStep())
        assertTrue(guard.nextStep())
        assertFalse(guard.nextStep())
        assertEquals(4, guard.steps)
    }

    @Test
    fun theTokenCapWarnsOnceAtEightyPercentAndEndsAtTheCap() {
        val guard = StepGuard(25, 1000)
        guard.addUsage(ChatResponse.Usage(500, 100), 0, 0)
        assertEquals(StepGuard.Signal.NONE, guard.tokenVerdict())

        guard.addUsage(ChatResponse.Usage(200, 10), 0, 0)
        assertEquals(810, guard.tokens)
        assertEquals(StepGuard.Signal.HINT, guard.tokenVerdict())
        // Once, not once per step.
        assertEquals(StepGuard.Signal.NONE, guard.tokenVerdict())

        guard.addUsage(ChatResponse.Usage(200, 0), 0, 0)
        assertEquals(StepGuard.Signal.TERMINATE, guard.tokenVerdict())
    }

    @Test
    fun withNoUsageBlockTheTokensAreEstimatedFromCharacters() {
        val guard = StepGuard(25, 1000)
        assertFalse(guard.estimated)
        guard.addUsage(null, 4000, 400)
        assertTrue(guard.estimated)
        assertEquals(1100, guard.tokens)
        // A cap still bites on an estimate, which is the point of estimating.
        assertEquals(StepGuard.Signal.TERMINATE, guard.tokenVerdict())
    }

    @Test
    fun aUsageBlockIsPreferredToAnEstimate() {
        val guard = StepGuard(25, 100000)
        guard.addUsage(ChatResponse.Usage(10, 5), 999999, 999999)
        assertEquals(15, guard.tokens)
        assertFalse(guard.estimated)
    }

    @Test
    fun theSameCallOverAndOverEscalatesToATerminate() {
        val guard = StepGuard(25, 100000)
        val call = """tap {"node_id":4}"""
        assertEquals(StepGuard.Signal.NONE, guard.noteCall(call))
        assertEquals(StepGuard.Signal.NONE, guard.noteCall(call))
        assertEquals(StepGuard.Signal.HINT, guard.noteCall(call))
        assertEquals(StepGuard.Signal.CHANGE_STRATEGY, guard.noteCall(call))
        assertEquals(StepGuard.Signal.TERMINATE, guard.noteCall(call))
    }

    @Test
    fun aDifferentCallResetsTheRepetitionCount() {
        val guard = StepGuard(25, 100000)
        val a = """tap {"node_id":4}"""
        val b = """tap {"node_id":5}"""
        guard.noteCall(a)
        guard.noteCall(a)
        assertEquals(StepGuard.Signal.NONE, guard.noteCall(b))
        assertEquals(StepGuard.Signal.NONE, guard.noteCall(a))
        assertEquals(StepGuard.Signal.NONE, guard.noteCall(a))
        assertEquals(StepGuard.Signal.HINT, guard.noteCall(a))
    }

    @Test
    fun aScreenThatDoesNotMoveTripsTheUnchangedSignal() {
        val guard = StepGuard(25, 100000)
        assertEquals(StepGuard.Signal.NONE, guard.noteScreen("aaa"))
        assertEquals(StepGuard.Signal.NONE, guard.noteScreen("aaa"))
        assertEquals(StepGuard.Signal.HINT, guard.noteScreen("aaa"))
    }

    @Test
    fun aChangedScreenResetsIt() {
        val guard = StepGuard(25, 100000)
        guard.noteScreen("aaa")
        guard.noteScreen("aaa")
        assertEquals(StepGuard.Signal.NONE, guard.noteScreen("bbb"))
        assertEquals(StepGuard.Signal.NONE, guard.noteScreen("bbb"))
        assertEquals(StepGuard.Signal.HINT, guard.noteScreen("bbb"))
    }

    @Test
    fun theSameErrorThreeTimesEscalatesToo() {
        val guard = StepGuard(25, 100000)
        val error = "the action did not take effect"
        assertEquals(StepGuard.Signal.NONE, guard.noteError(error))
        assertEquals(StepGuard.Signal.NONE, guard.noteError(error))
        assertEquals(StepGuard.Signal.HINT, guard.noteError(error))
        assertEquals(StepGuard.Signal.NONE, guard.noteError("something else"))
    }

    @Test
    fun theEscalationIsSharedAcrossTheThreeSignals() {
        // A task that repeats a call, then sits on the same screen, then keeps
        // getting the same error is one stuck task, not three.
        val guard = StepGuard(25, 100000)
        // The repeated call fires the first escalation.
        repeat(3) { guard.noteCall("tap {}") }
        guard.noteScreen("x")
        guard.noteScreen("x")
        assertEquals(StepGuard.Signal.CHANGE_STRATEGY, guard.noteScreen("x"))
        guard.noteError("nope")
        guard.noteError("nope")
        assertEquals(StepGuard.Signal.TERMINATE, guard.noteError("nope"))
    }

    @Test
    fun progressClearsTheRepetitionCounters() {
        val guard = StepGuard(25, 100000)
        guard.noteCall("tap {}")
        guard.noteCall("tap {}")
        guard.noteProgress()
        assertEquals(StepGuard.Signal.NONE, guard.noteCall("tap {}"))
        assertEquals(StepGuard.Signal.NONE, guard.noteCall("tap {}"))
        assertEquals(StepGuard.Signal.HINT, guard.noteCall("tap {}"))
    }

    @Test
    fun eachSignalHasSomethingToSayToTheModel() {
        val guard = StepGuard(25, 100000)
        val control = "[BestROM 7f3a91]"
        assertTrue(guard.message(StepGuard.Signal.HINT, control).contains("Do not repeat it"))
        assertTrue(
            guard.message(StepGuard.Signal.CHANGE_STRATEGY, control).contains("Change strategy")
        )
        assertEquals("", guard.message(StepGuard.Signal.NONE, control))
        assertTrue(guard.softLimitMessage(control).contains("token budget"))
        // Every line the phone sends carries this task's own prefix.
        assertTrue(guard.message(StepGuard.Signal.HINT, control).startsWith(control))
        assertTrue(guard.softLimitMessage(control).startsWith(control))
    }
}
