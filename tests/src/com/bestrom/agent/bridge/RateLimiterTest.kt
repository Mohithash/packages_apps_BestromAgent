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
import org.junit.Assert.assertTrue
import org.junit.Test

/** The token bucket that covers the acting methods. */
class RateLimiterTest {

    @Test
    fun theBucketHoldsTenAndThenRefuses() {
        val limiter = RateLimiter(capacity = 10, refillPerSecond = 10.0)
        for (i in 0 until 10) {
            assertEquals("request $i should pass", 0L, limiter.acquire(1_000L))
        }
        val retry = limiter.acquire(1_000L)
        assertTrue("the eleventh must be refused", retry > 0)
    }

    @Test
    fun itRefillsAtTenPerSecond() {
        val limiter = RateLimiter(capacity = 10, refillPerSecond = 10.0)
        for (i in 0 until 10) limiter.acquire(1_000L)
        assertTrue(limiter.acquire(1_000L) > 0)
        // A full second later the bucket is full again.
        for (i in 0 until 10) {
            assertEquals(0L, limiter.acquire(2_000L))
        }
        assertTrue(limiter.acquire(2_000L) > 0)
    }

    @Test
    fun resetRefillsTheBucket() {
        val limiter = RateLimiter(capacity = 10, refillPerSecond = 10.0)
        for (i in 0 until 10) limiter.acquire(1_000L)
        limiter.reset()
        assertEquals(0L, limiter.acquire(1_000L))
    }
}
