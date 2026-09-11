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


package com.bestrom.agent

import com.bestrom.agent.a11y.AgentAccessibilityService
import com.bestrom.agent.bridge.AgentBridgeService
import com.bestrom.agent.runner.PendingChoice
import com.bestrom.agent.runner.PendingConfirm
import com.bestrom.agent.runner.StepEvent
import com.bestrom.agent.runner.Task
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The single in-process source of truth for Agent mode.
 *
 * None of it is persisted. A fresh boot starts with [bridgeLive] false, which is
 * what makes the accessibility service take itself back out of
 * ENABLED_ACCESSIBILITY_SERVICES the moment the platform binds it.
 */
object AgentState {

    /** True only while AgentBridgeService holds an open LocalServerSocket. */
    val bridgeLive = AtomicBoolean(false)

    /** SystemClock.uptimeMillis() of the most recent touch interaction event. */
    val lastTouchMs = AtomicLong(0)

    /** True once a client has paired this session. */
    val paired = AtomicBoolean(false)

    /** Set by AgentAccessibilityService while it is connected, cleared when it is not. */
    @Volatile
    var a11y: AgentAccessibilityService? = null

    /**
     * The six digits currently on screen, or null when there is no live code -
     * the bridge is off, or the code was spent on a successful pairing.
     *
     * Written only from the Auth rotation listener, so whatever rotates the
     * code - the New code button or three wrong guesses over the wire - the
     * screen and the secret cannot drift apart.
     */
    @Volatile
    var pairingCode: String? = null

    /** System.currentTimeMillis() until which pairing is refused, or 0. */
    @Volatile
    var pairingCooldownUntilMs: Long = 0

    /**
     * Connections closed because the peer was neither shell nor root.
     *
     * In memory and never written to the audit log: an unauthenticated peer
     * must not be able to push real entries out of the ring by connecting.
     */
    val peerRefusals = AtomicInteger(0)

    /** Requests refused on a connection that had not authenticated yet. */
    val preAuthRefusals = AtomicInteger(0)

    /**
     * The running bridge service, published so the runner can reach the same
     * dispatcher host the adb bridge uses. Null whenever Agent mode is off.
     */
    @Volatile
    var bridge: AgentBridgeService? = null

    /** The one task that may be running. Cleared the moment it ends. */
    @Volatile
    var task: Task? = null

    /** A confirm sheet the runner is blocked on, or null. */
    @Volatile
    var confirm: PendingConfirm? = null

    /** A multi-choice sheet (offer_choices), or null. */
    @Volatile
    var choice: PendingChoice? = null

    /**
     * Set when the agent is about to open another app. The chat activity
     * enters picture-in-picture so the task UI is not buried under the target.
     * Cleared when PiP starts or the task ends.
     */
    val keepVisible = AtomicBoolean(false)

    /** How many step lines the task screen keeps. Nothing is persisted. */
    const val MAX_STEPS = 300

    private val steps = ArrayList<StepEvent>(64)

    @Synchronized
    fun addStep(event: StepEvent) {
        steps.add(event)
        while (steps.size > MAX_STEPS) steps.removeAt(0)
    }

    @Synchronized
    fun steps(): List<StepEvent> = ArrayList(steps)

    @Synchronized
    fun clearSteps() {
        steps.clear()
    }

    /**
     * Drops everything a finished task read, keeping only how it ended.
     *
     * The step lines carry text the agent read on other apps' screens, and
     * this activity can be brought forward by anything. The last line is the
     * answer the user is waiting for, so it stays until the next task starts.
     */
    @Synchronized
    fun keepEndingOnly() {
        val ending = steps.lastOrNull { it.kind == StepEvent.Kind.DONE }
        steps.clear()
        if (ending != null) steps.add(ending)
    }

    fun reset() {
        bridgeLive.set(false)
        paired.set(false)
        pairingCode = null
        pairingCooldownUntilMs = 0
        task = null
        confirm = null
        choice = null
        keepVisible.set(false)
    }
}
