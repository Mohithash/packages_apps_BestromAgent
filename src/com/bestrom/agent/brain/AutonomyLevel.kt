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
 * How far the agent may go without asking, and which tool families unlock.
 *
 * Ordinal order is the product ladder: each level includes everything below.
 * Forbidden Settings keys stay refused at every level. Payment / credential
 * surfaces stay ALWAYS_CONFIRM through Auto; Bypass alone skips that sheet.
 */
enum class AutonomyLevel {
    /** Ask before each mutating UI or schedule action. */
    ASSIST,

    /** Skip mutate confirms inside one task (former brain_autonomous=true). */
    TASK,

    /** Plus background jobs such as idle-drain sampling. */
    BACKGROUND,

    /** Plus crash/log tools for next-build briefs. */
    MAINTAINER,

    /** Plus macros (save/run Tasker-style recipes). */
    FULL,

    /**
     * Full tools, and skip ALWAYS_CONFIRM that is not payment / credential /
     * checkout (e.g. settings call_function). Wallets and pay buttons still ask.
     */
    AUTO,

    /** Skip every confirm sheet. Forbidden rows and excluded apps still refuse. */
    BYPASS;

    fun atLeast(min: AutonomyLevel): Boolean = ordinal >= min.ordinal

    /** Former "Autonomous inside a task" switch. */
    fun skipsMutateConfirm(): Boolean = atLeast(TASK)

    /** Auto: skip non-payment ALWAYS_CONFIRM. */
    fun skipsNonSensitiveConfirm(): Boolean = atLeast(AUTO)

    /** Bypass: no confirm sheet at all. */
    fun bypassesConfirms(): Boolean = this == BYPASS

    companion object {
        @JvmStatic
        fun byName(name: String?): AutonomyLevel {
            if (name.isNullOrEmpty()) return TASK
            return values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: TASK
        }

        /** Migrate the old boolean prefs key. */
        @JvmStatic
        fun fromLegacyAutonomous(autonomous: Boolean): AutonomyLevel =
            if (autonomous) TASK else ASSIST
    }
}
