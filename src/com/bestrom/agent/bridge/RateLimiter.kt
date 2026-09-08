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

/**
 * A token bucket shared by every connection.
 *
 * Capacity 10, refilled at 10 per second. It covers the acting methods and
 * functions.execute; reads are exempt, and ui.screenshot is left to the
 * platform's own interval limit.
 */
class RateLimiter(
    private val capacity: Int = 10,
    private val refillPerSecond: Double = 10.0,
) {

    private var tokens: Double = capacity.toDouble()
    private var lastMs: Long = 0

    /** Returns 0 when the request may run, otherwise the retry delay in ms. */
    @Synchronized
    fun acquire(nowMs: Long): Long {
        if (lastMs == 0L) lastMs = nowMs
        val elapsed = (nowMs - lastMs).coerceAtLeast(0)
        lastMs = nowMs
        tokens = minOf(capacity.toDouble(), tokens + elapsed / 1000.0 * refillPerSecond)
        if (tokens >= 1.0) {
            tokens -= 1.0
            return 0
        }
        val deficit = 1.0 - tokens
        return Math.ceil(deficit / refillPerSecond * 1000.0).toLong().coerceAtLeast(1)
    }

    @Synchronized
    fun reset() {
        tokens = capacity.toDouble()
        lastMs = 0
    }
}
