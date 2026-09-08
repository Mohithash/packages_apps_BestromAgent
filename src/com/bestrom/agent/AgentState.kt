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

    /** The six digits currently on screen, or null while the bridge is off. */
    @Volatile
    var pairingCode: String? = null

    /**
     * Connections closed because the peer was neither shell nor root.
     *
     * In memory and never written to the audit log: an unauthenticated peer
     * must not be able to push real entries out of the ring by connecting.
     */
    val peerRefusals = AtomicInteger(0)

    /** Requests refused on a connection that had not authenticated yet. */
    val preAuthRefusals = AtomicInteger(0)

    fun reset() {
        bridgeLive.set(false)
        paired.set(false)
        pairingCode = null
    }
}
