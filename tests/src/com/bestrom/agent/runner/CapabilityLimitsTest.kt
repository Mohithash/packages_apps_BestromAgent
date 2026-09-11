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

package com.bestrom.agent.runner

import com.bestrom.agent.brain.AutonomyLevel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Product limits, checked in code so the chat UI does not promise work the
 * tools and prompt refuse to do.
 */
class CapabilityLimitsTest {

    @Test
    fun toolsIncludeUiAppsScheduleAndMaintainerDiag() {
        val names = ToolSchema.TOOLS.map { it.name }.toSet()
        assertTrue(names.contains(ToolSchema.READ_SCREEN))
        assertTrue(names.contains(ToolSchema.LAUNCH_APP))
        assertTrue(names.contains(ToolSchema.CALL_FUNCTION))
        assertTrue(names.contains(ToolSchema.SCHEDULE_REMINDER))
        assertTrue(names.contains(ToolSchema.SCHEDULE_TASK))
        assertTrue(names.contains(ToolSchema.LIST_REMINDERS))
        assertTrue(names.contains(ToolSchema.CANCEL_REMINDER))
        assertTrue(names.contains(ToolSchema.LOG_TAIL))
        assertTrue(names.contains(ToolSchema.LOG_GREP))
        assertTrue(names.contains(ToolSchema.CRASH_SCAN))
        assertTrue(names.contains(ToolSchema.MEASURE_IDLE_DRAIN))
        assertTrue(names.contains(ToolSchema.BATTERYSTATS_SNIPPET))
        assertTrue(names.contains(ToolSchema.START_JOB))
        assertTrue(names.contains(ToolSchema.SAVE_MACRO))
        assertTrue(names.contains(ToolSchema.RUN_MACRO))
        assertTrue(names.contains(ToolSchema.LIST_PLAYBOOKS))
        assertTrue(names.contains(ToolSchema.RUN_PLAYBOOK))
        assertTrue(names.contains(ToolSchema.CREATE_MINIAPP))
        assertTrue(names.contains(ToolSchema.LIST_MINIAPPS))
        assertTrue(names.contains(ToolSchema.DELETE_MINIAPP))
        assertTrue(names.contains(ToolSchema.OPEN_MINIAPP))
        assertTrue(names.contains(ToolSchema.DONE))
        // No unbounded shell. Named log / batterystats tools only.
        assertFalse(names.contains("shell"))
        assertFalse(names.contains("dumpsys"))
        assertFalse(names.contains("logcat"))
        assertFalse(names.contains("run_shell"))
    }

    @Test
    fun promptAllowsSchedulesAndMaintainerLogs() {
        val prompt = SystemPrompt.INSTRUCTIONS
        assertTrue(prompt.contains("schedule_reminder"))
        assertTrue(prompt.contains("schedule_task"))
        assertTrue(prompt.contains("log_tail"))
        assertTrue(prompt.contains("measure_idle_drain"))
        assertTrue(prompt.contains("batterystats_snippet"))
        assertTrue(prompt.contains("start_job"))
        assertTrue(prompt.contains("save_macro"))
        assertTrue(prompt.contains("battery_below"))
        assertTrue(prompt.contains("run_playbook"))
        assertTrue(prompt.contains("create_miniapp"))
        assertTrue(prompt.contains("never auto-pay"))
        assertTrue(prompt.contains("no shell tool"))
        assertFalse(prompt.contains("You cannot schedule anything"))
    }

    @Test
    fun logToolsNeedMaintainerAutonomy() {
        assertTrue(
            ToolSchema.minAutonomy(ToolSchema.LOG_TAIL) == AutonomyLevel.MAINTAINER
        )
        assertTrue(
            ToolSchema.minAutonomy(ToolSchema.BATTERYSTATS_SNIPPET) == AutonomyLevel.MAINTAINER
        )
        assertTrue(
            ToolSchema.minAutonomy(ToolSchema.MEASURE_IDLE_DRAIN) == AutonomyLevel.BACKGROUND
        )
        assertTrue(ToolSchema.minAutonomy(ToolSchema.START_JOB) == AutonomyLevel.BACKGROUND)
        assertTrue(ToolSchema.minAutonomy(ToolSchema.SAVE_MACRO) == AutonomyLevel.FULL)
        assertTrue(ToolSchema.minAutonomy(ToolSchema.RUN_PLAYBOOK) == AutonomyLevel.FULL)
        assertTrue(ToolSchema.minAutonomy(ToolSchema.CREATE_MINIAPP) == AutonomyLevel.FULL)
        assertTrue(ToolSchema.minAutonomy(ToolSchema.TAP) == null)
    }

    @Test
    fun repairKeepsToolCallPairsValidForProviders() {
        val broken =
            listOf(
                com.bestrom.agent.brain.ChatMessage(
                    com.bestrom.agent.brain.ChatMessage.ASSISTANT,
                    null,
                    listOf(
                        com.bestrom.agent.brain.ChatMessage.Call("a", "launch_app", "{}"),
                        com.bestrom.agent.brain.ChatMessage.Call("b", "read_screen", "{}"),
                    ),
                ),
                com.bestrom.agent.brain.ChatMessage(
                    com.bestrom.agent.brain.ChatMessage.TOOL,
                    "opened",
                    toolCallId = "a",
                ),
                com.bestrom.agent.brain.ChatMessage(
                    com.bestrom.agent.brain.ChatMessage.USER,
                    "too early",
                ),
                com.bestrom.agent.brain.ChatMessage(
                    com.bestrom.agent.brain.ChatMessage.TOOL,
                    "screen",
                    toolCallId = "b",
                ),
            )
        val fixed = Transcript().repairToolPairs(broken)
        val afterAssistant = fixed.drop(1)
        val firstNonTool = afterAssistant.indexOfFirst { it.role != com.bestrom.agent.brain.ChatMessage.TOOL }
        assertTrue(firstNonTool >= 2) // both tool replies before the user line
        assertTrue(afterAssistant.take(firstNonTool).all { it.toolCallId != null })
    }
}
