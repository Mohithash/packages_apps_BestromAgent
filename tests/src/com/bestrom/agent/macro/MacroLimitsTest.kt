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

package com.bestrom.agent.macro

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroLimitsTest {

    @Test
    fun rejectsBadStepsAndAllowsWhitelist() {
        assertTrue(MacroLimits.rejectSteps(null) != null)
        assertTrue(MacroLimits.rejectSteps(JSONArray()) != null)
        val good =
            JSONArray()
                .put(
                    JSONObject()
                        .put("tool", MacroLimits.STEP_NOTIFY)
                        .put("args", JSONObject().put("message", "hi"))
                )
                .put(JSONObject().put("tool", MacroLimits.STEP_MEASURE_IDLE_DRAIN))
                .put(
                    JSONObject()
                        .put("tool", MacroLimits.STEP_SCHEDULE_REMINDER)
                        .put(
                            "args",
                            JSONObject().put("message", "later").put("in_minutes", 30),
                        )
                )
        assertNull(MacroLimits.rejectSteps(good))
        val bad =
            JSONArray().put(JSONObject().put("tool", "tap").put("args", JSONObject()))
        assertTrue(MacroLimits.rejectSteps(bad) != null)
        val badReminder =
            JSONArray()
                .put(
                    JSONObject()
                        .put("tool", MacroLimits.STEP_SCHEDULE_REMINDER)
                        .put("args", JSONObject().put("message", "no when"))
                )
        assertTrue(MacroLimits.rejectSteps(badReminder) != null)
    }

    @Test
    fun scheduleRulesMatchTrigger() {
        val now = 1_000_000L
        assertNull(
            MacroLimits.rejectSchedule(MacroLimits.TRIGGER_INTERVAL, 30, 0L, 0, now)
        )
        assertTrue(
            MacroLimits.rejectSchedule(MacroLimits.TRIGGER_INTERVAL, 1, 0L, 0, now) != null
        )
        assertNull(MacroLimits.rejectSchedule(MacroLimits.TRIGGER_BOOT, 0, 0L, 0, now))
        assertNull(
            MacroLimits.rejectSchedule(MacroLimits.TRIGGER_BATTERY_BELOW, 0, 0L, 20, now)
        )
        assertTrue(
            MacroLimits.rejectSchedule(MacroLimits.TRIGGER_BATTERY_BELOW, 0, 0L, 2, now) !=
                null
        )
        assertNull(MacroLimits.rejectTrigger(MacroLimits.TRIGGER_BATTERY_BELOW))
    }
}
