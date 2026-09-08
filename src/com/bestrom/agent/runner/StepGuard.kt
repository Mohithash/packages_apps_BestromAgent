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

/**
 * The caps, and the check for a task going round in circles.
 *
 * Trimmed from PokeClaw's StuckDetector, which carries five signals and three
 * recovery levels, and its TaskBudget soft/hard split (Apache-2.0). Three
 * signals are kept: the same call repeated, a screen that does not move, and
 * the same error coming back. The three that were dropped needed a model of
 * intent this loop does not have.
 */
class StepGuard(val stepCap: Int, val tokenCap: Int) {

    companion object {
        /** How many times in a row before a signal fires. */
        const val REPEATS_BEFORE_SIGNAL = 3

        /** The share of the token cap at which the model is told to wrap up. */
        const val SOFT_LIMIT = 0.8

        /** Characters per token, when the endpoint sends no usage block. */
        const val CHARS_PER_TOKEN = 4
    }

    /** What the runner should do about a signal, in the order it escalates. */
    enum class Signal {
        NONE,
        HINT,
        CHANGE_STRATEGY,
        TERMINATE,
    }

    var steps = 0
        private set

    var tokens = 0
        private set

    /** True when at least one call had no usage block and had to be guessed at. */
    var estimated = false
        private set

    private var warned = false
    private var escalations = 0

    private var lastCall = ""
    private var callRepeats = 0

    private var lastScreen = ""
    private var screenRepeats = 0

    private var lastError = ""
    private var errorRepeats = 0

    /** Counts the step. False when the cap has just been passed. */
    fun nextStep(): Boolean {
        steps++
        return steps <= stepCap
    }

    /**
     * Adds what the last model call cost.
     *
     * Several LAN servers send no usage at all. Estimating from characters is
     * rough, but a token cap that silently stops counting is worse than one
     * that counts badly, and the transcript says which happened.
     */
    fun addUsage(usage: ChatResponse.Usage?, sentChars: Int, receivedChars: Int) {
        if (usage != null) {
            tokens += usage.total()
            return
        }
        estimated = true
        tokens += (sentChars + receivedChars) / CHARS_PER_TOKEN
    }

    /** NONE, one HINT at the soft limit, then TERMINATE at the cap. */
    fun tokenVerdict(): Signal {
        if (tokens >= tokenCap) return Signal.TERMINATE
        if (!warned && tokens >= (tokenCap * SOFT_LIMIT).toInt()) {
            warned = true
            return Signal.HINT
        }
        return Signal.NONE
    }

    /** The same tool with the same arguments, over and over. */
    fun noteCall(signature: String): Signal {
        if (signature == lastCall) callRepeats++ else callRepeats = 1
        lastCall = signature
        return if (callRepeats >= REPEATS_BEFORE_SIGNAL) escalate() else Signal.NONE
    }

    /**
     * The screen after an acting step.
     *
     * Only acting steps count: a task that reads the same screen twice while
     * thinking is not stuck, it is being careful.
     */
    fun noteScreen(hash: String): Signal {
        if (hash == lastScreen) screenRepeats++ else screenRepeats = 1
        lastScreen = hash
        return if (screenRepeats >= REPEATS_BEFORE_SIGNAL) escalate() else Signal.NONE
    }

    /** The same failure coming back from the same kind of call. */
    fun noteError(text: String): Signal {
        if (text == lastError) errorRepeats++ else errorRepeats = 1
        lastError = text
        return if (errorRepeats >= REPEATS_BEFORE_SIGNAL) escalate() else Signal.NONE
    }

    /**
     * A step that changed something clears the repetition counters.
     *
     * The escalation count goes with them. It is task-lifetime otherwise, so
     * three unrelated hiccups twenty steps apart, each recovered from, ended a
     * working task as "stuck".
     */
    fun noteProgress() {
        callRepeats = 0
        errorRepeats = 0
        escalations = 0
        lastCall = ""
        lastError = ""
    }

    private fun escalate(): Signal {
        escalations++
        return when (escalations) {
            1 -> Signal.HINT
            2 -> Signal.CHANGE_STRATEGY
            else -> Signal.TERMINATE
        }
    }

    /**
     * The sentence the model is told when a signal fires.
     *
     * [control] is the task's own control prefix, which carries the nonce: a
     * fixed one is a line any screen can write for itself.
     */
    fun message(signal: Signal, control: String): String =
        when (signal) {
            Signal.HINT ->
                control + " That did not change anything. Do not repeat it - try a different " +
                    "element, scroll, or go back."
            Signal.CHANGE_STRATEGY ->
                control + " This approach is not working. Change strategy: read the screen " +
                    "again and pick a different route, or call done and say what stopped you."
            Signal.TERMINATE -> "stopped"
            Signal.NONE -> ""
        }

    /** The one warning sent when the token budget is most of the way gone. */
    fun softLimitMessage(control: String): String =
        control + " You have used most of the token budget for this task. Finish in as few " +
            "steps as you can, and call done with what you have."
}
