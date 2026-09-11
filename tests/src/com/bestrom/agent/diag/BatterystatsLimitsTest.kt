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

package com.bestrom.agent.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatterystatsLimitsTest {

    @Test
    fun argvIsFixedAndNeverShell() {
        assertEquals(
            listOf("dumpsys", "batterystats"),
            BatterystatsLimits.argvForMode("full"),
        )
        assertEquals(
            listOf("dumpsys", "batterystats", "-c"),
            BatterystatsLimits.argvForMode("checkin"),
        )
        assertFalse(BatterystatsLimits.argvForMode("full").contains("sh"))
        assertNull(BatterystatsLimits.rejectMode("full"))
        assertNull(BatterystatsLimits.rejectMode(""))
        assertTrue(BatterystatsLimits.rejectMode("shell") != null)
        assertTrue(BatterystatsLimits.rejectFocus("rm -rf") != null)
        assertNull(BatterystatsLimits.rejectFocus("cpu"))
    }

    @Test
    fun filterKeepsSummaryNeedles() {
        val dump =
            """
            Battery History:
            Capacity: 5000 mAh
            Discharge: 120 mAh
            Screen on: 2h 10m
            Some noise line
            Estimated power use (mAh):
              Wifi: 10
            """.trimIndent()
        val out = BatterystatsLimits.filterDump(dump, "full", null)
        assertTrue(out.contains("Capacity"))
        assertTrue(out.contains("Screen on"))
        assertFalse(out.contains("Some noise line"))
        assertTrue(BatterystatsLimits.looksLikePermissionDenied("Permission Denial: dumpsys"))
        assertFalse(BatterystatsLimits.looksLikePermissionDenied(out))
    }
}
