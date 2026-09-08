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


package com.bestrom.agent.audit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The overflow arithmetic: a block at a time, never one entry at a time. */
class AuditBoundsTest {

    @Test
    fun belowTheCapNothingIsDropped() {
        assertEquals(0, AuditBounds.sizeAfterOverflow(0))
        assertEquals(1, AuditBounds.sizeAfterOverflow(1))
        assertEquals(
            AuditBounds.CAPACITY,
            AuditBounds.sizeAfterOverflow(AuditBounds.CAPACITY),
        )
    }

    @Test
    fun oneEntryOverTheCapDropsAWholeBlock() {
        val after = AuditBounds.sizeAfterOverflow(AuditBounds.CAPACITY + 1)
        assertEquals(AuditBounds.CAPACITY - AuditBounds.TRIM_BLOCK + 1, after)
        assertTrue(after < AuditBounds.CAPACITY)
    }

    @Test
    fun theRewriteHappensOncePerBlockNotOncePerAppend() {
        // Walk a run of appends past the cap and count the rewrites. The point
        // of the block is that this is a fiftieth of the appends, not all of
        // them: an unauthenticated peer used to be able to make every single
        // request rewrite the whole file.
        var size = AuditBounds.CAPACITY
        var rewrites = 0
        val appends = 500
        for (i in 0 until appends) {
            size++
            if (size > AuditBounds.CAPACITY) {
                size = AuditBounds.sizeAfterOverflow(size)
                rewrites++
            }
        }
        assertEquals(appends / AuditBounds.TRIM_BLOCK, rewrites)
        assertTrue(size <= AuditBounds.CAPACITY)
    }

    @Test
    fun aLogReadBackFarOverTheCapIsStillCutToOneBlockBoundary() {
        assertEquals(
            AuditBounds.CAPACITY - AuditBounds.TRIM_BLOCK + 1,
            AuditBounds.sizeAfterOverflow(5000),
        )
    }
}
