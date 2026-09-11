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

class LogLimitsTest {

    @Test
    fun rejectsBadTagsAndPatterns() {
        assertNull(LogLimits.rejectTag(null))
        assertNull(LogLimits.rejectTag("ActivityManager"))
        assertTrue(LogLimits.rejectTag("bad tag") != null)
        assertTrue(LogLimits.rejectTag("x".repeat(80)) != null)
        assertTrue(LogLimits.rejectPattern("") != null)
        assertNull(LogLimits.rejectPattern("FATAL EXCEPTION"))
    }

    @Test
    fun redactsSecrets() {
        val raw =
            "Authorization: Bearer sk-abc123456789\napi_key=AIzaSyDummyKeyValueHere012345"
        val out = LogLimits.redact(raw)
        assertFalse(out.contains("sk-abc"))
        assertFalse(out.contains("AIzaSy"))
        assertTrue(out.contains("[redacted]"))
    }

    @Test
    fun clampsLines() {
        assertEquals(LogLimits.DEFAULT_LINES, LogLimits.clampLines(0))
        assertEquals(LogLimits.MAX_LINES, LogLimits.clampLines(9999))
        assertEquals(80, LogLimits.clampLines(80))
    }
}
