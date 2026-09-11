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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What the task screen shows for one step. */
class StepEvent(val step: Int, val kind: Kind, val text: String) {

    enum class Kind {
        THINKING,
        ACTION,
        RESULT,
        CONFIRM,
        REFUSED,
        ERROR,
        DONE,
    }

    /** "4  tap  Battery saver", or "-  stopped by you" for a terminal line. */
    fun line(): String = (if (step > 0) step.toString() else "-") + "  " + text
}

/** Where a task got to. */
enum class TaskState {
    PREPARING,
    THINKING,
    ACTING,
    WAITING,
    FINISHED,
}

/**
 * One task: what was asked, what it is allowed to do, and how it ended.
 *
 * The caps and the policy are copied in here when the task starts, so changing
 * a setting while a task runs cannot widen what the running task may do.
 */
class Task(
    val id: String,
    val goal: String,
    val autonomy: com.bestrom.agent.brain.AutonomyLevel,
    val stepCap: Int,
    val tokenCap: Int,
    val vision: Boolean,
    val startedMs: Long,
) {

    companion object {
        const val DONE = "done"
        const val STEP_CAP = "step_cap"
        const val TOKEN_CAP = "token_cap"
        const val STOPPED = "stopped"
        const val LOCKED = "locked"
        const val STUCK = "stuck"
        const val MALFORMED = "malformed"
        const val BRAIN_ERROR = "brain_error"
        const val NO_BRAIN = "no_brain"

        /** How long a confirm sheet waits before it answers Deny by itself. */
        const val CONFIRM_TIMEOUT_MS = 120_000L
    }

    /** Set by Stop, checked before every model call and every capability call. */
    val stop = AtomicBoolean(false)

    /** Set by the third button on the sheet. Never persisted, never reused. */
    @Volatile
    var allowAllForThisTask = false

    @Volatile
    var state = TaskState.PREPARING

    /** One of the constants above, once the task is finished. */
    @Volatile
    var reason = ""

    /** What the model answered with, for the last line of the transcript. */
    @Volatile
    var answer = ""

    @Volatile
    var step = 0

    fun stopped(): Boolean = stop.get()

    fun finished(): Boolean = state == TaskState.FINISHED

    /** The one-line ending the transcript and the audit log agree on. */
    fun ending(): String =
        when (reason) {
            DONE -> if (answer.isEmpty()) "done" else "done - $answer"
            STEP_CAP -> "step cap reached ($stepCap)"
            TOKEN_CAP -> "token cap reached ($tokenCap)"
            STOPPED -> "stopped by you"
            LOCKED -> "the phone was locked"
            STUCK -> "stopped: it was repeating itself"
            MALFORMED -> "stopped: the model kept sending calls that do not parse"
            BRAIN_ERROR -> if (answer.isEmpty()) "the brain failed" else answer
            NO_BRAIN -> if (answer.isEmpty()) "no brain is set up" else answer
            else -> reason
        }
}

/**
 * A confirm sheet the runner is waiting on.
 *
 * The runner blocks on the latch. Whatever answers it - a button, the timeout,
 * or Stop - answers once; a second answer is ignored, so a race between the
 * user and the timer cannot act twice.
 */
class PendingConfirm(
    val what: String,
    val target: String,
    /** Payments or credentials: no Allow all button, and an extra line. */
    val sensitive: Boolean,
) {
    enum class Answer {
        ALLOW,
        ALLOW_ALL,
        DENY,
    }

    private val latch = CountDownLatch(1)

    @Volatile
    private var answer = Answer.DENY

    @Volatile
    private var answered = false

    @Synchronized
    fun answer(value: Answer) {
        if (answered) return
        answered = true
        answer = value
        latch.countDown()
    }

    /** Deny on timeout: an unanswered sheet is never acted on. */
    fun await(timeoutMs: Long = Task.CONFIRM_TIMEOUT_MS): Answer {
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return Answer.DENY
        } catch (e: InterruptedException) {
            return Answer.DENY
        }
        return answer
    }
}

/**
 * A multi-option sheet (e.g. notification vs toast vs dialog for an alert).
 */
class PendingChoice(
    val prompt: String,
    val options: List<String>,
) {
    private val latch = CountDownLatch(1)

    @Volatile
    private var selected: String? = null

    @Volatile
    private var answered = false

    @Synchronized
    fun answer(value: String?) {
        if (answered) return
        answered = true
        selected = value
        latch.countDown()
    }

    fun await(timeoutMs: Long = Task.CONFIRM_TIMEOUT_MS): String? {
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        } catch (_: InterruptedException) {
            return null
        }
        return selected
    }
}
