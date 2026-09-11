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

package com.bestrom.agent.schedule

import com.bestrom.agent.runner.ToolSchema
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderTimeTest {

    @Test
    fun inMinutesResolvesFromNow() {
        val now = 1_000_000L
        val at = ReminderTime.fireAtMs(JSONObject().put("in_minutes", 5), now)
        assertEquals(now + 5 * 60_000L, at)
    }

    @Test
    fun rejectsTooSoonAbsolute() {
        val now = 1_000_000L
        assertNotNull(ReminderTime.rejectWhen(JSONObject().put("at_unix_ms", now + 10_000L), now))
    }

    @Test
    fun acceptsAbsoluteOneMinuteOut() {
        val now = 1_000_000L
        assertNull(ReminderTime.rejectWhen(JSONObject().put("at_unix_ms", now + 60_000L), now))
    }

    @Test
    fun scheduleReminderToolValidates() {
        val bad =
            ToolSchema.validate(
                "c1",
                ToolSchema.SCHEDULE_REMINDER,
                """{"message":"hi"}""",
                false,
            )
        assertTrue(bad is ToolSchema.Validation.Invalid)

        val ok =
            ToolSchema.validate(
                "c1",
                ToolSchema.SCHEDULE_REMINDER,
                """{"message":"Stretch","in_minutes":10}""",
                false,
            )
        assertTrue(ok is ToolSchema.Validation.Valid)
    }
}
