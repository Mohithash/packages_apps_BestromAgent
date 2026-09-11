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

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaTest {

    private fun names(array: JSONArray): List<String> {
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            out.add(array.getJSONObject(i).getJSONObject("function").getString("name"))
        }
        return out
    }

    private fun valid(name: String, args: String, vision: Boolean = false): ToolSchema.ToolCall {
        val v = ToolSchema.validate("call_1", name, args, vision)
        assertTrue(
            "expected a valid call, got " + (v as? ToolSchema.Validation.Invalid)?.reason,
            v is ToolSchema.Validation.Valid,
        )
        return (v as ToolSchema.Validation.Valid).call
    }

    private fun invalid(name: String, args: String, vision: Boolean = false): String {
        val v = ToolSchema.validate("call_1", name, args, vision)
        assertTrue("expected a refusal for $name $args", v is ToolSchema.Validation.Invalid)
        return (v as ToolSchema.Validation.Invalid).reason
    }

    @Test
    fun screenshotIsTheOnlyDifferenceVisionMakes() {
        val without = names(ToolSchema.tools(false))
        val with = names(ToolSchema.tools(true))
        assertEquals(36, without.size)
        assertEquals(37, with.size)
        assertEquals(listOf(ToolSchema.SCREENSHOT), with - without.toSet())
        assertFalse(without.contains(ToolSchema.SCREENSHOT))
    }

    @Test
    fun everyToolIsAStrictObjectSchemaAndNoneCarriesStrict() {
        val tools = ToolSchema.tools(true)
        for (i in 0 until tools.length()) {
            val entry = tools.getJSONObject(i)
            assertEquals("function", entry.getString("type"))
            val function = entry.getJSONObject("function")
            assertFalse(function.has("strict"))
            assertTrue(function.getString("description").isNotEmpty())
            val parameters = function.getJSONObject("parameters")
            assertEquals("object", parameters.getString("type"))
            assertFalse(parameters.getBoolean("additionalProperties"))
            assertTrue(parameters.has("properties"))
            assertTrue(parameters.has("required"))
            // Every required name is a property that exists.
            val properties = parameters.getJSONObject("properties")
            val required = parameters.getJSONArray("required")
            for (r in 0 until required.length()) {
                assertTrue(properties.has(required.getString(r)))
            }
        }
    }

    @Test
    fun theOrderIsStableSoACachedPrefixStaysByteIdentical() {
        assertEquals(ToolSchema.tools(true).toString(), ToolSchema.tools(true).toString())
        assertEquals(
            listOf(
                "read_screen",
                "tap",
                "long_press",
                "swipe",
                "type",
                "key",
                "launch_app",
                "list_apps",
                "list_functions",
                "call_function",
                "screenshot",
                "wait",
                "schedule_reminder",
                "schedule_task",
                "list_reminders",
                "cancel_reminder",
                "log_tail",
                "log_grep",
                "crash_scan",
                "measure_idle_drain",
                "batterystats_snippet",
                "start_job",
                "stop_job",
                "list_jobs",
                "save_macro",
                "delete_macro",
                "list_macros",
                "run_macro",
                "list_playbooks",
                "run_playbook",
                "describe_alert_options",
                "offer_choices",
                "create_miniapp",
                "list_miniapps",
                "delete_miniapp",
                "open_miniapp",
                "done",
            ),
            names(ToolSchema.tools(true)),
        )
    }

    @Test
    fun everyToolInTheSchemaHasABridgeMethodOrIsTerminal() {
        // The dispatcher reads ToolSchema.BRIDGE, so this is the mapping that
        // actually runs and not a description of it.
        val local =
            setOf(
                ToolSchema.DONE,
                ToolSchema.WAIT,
                ToolSchema.SCHEDULE_REMINDER,
                ToolSchema.SCHEDULE_TASK,
                ToolSchema.LIST_REMINDERS,
                ToolSchema.CANCEL_REMINDER,
                ToolSchema.LOG_TAIL,
                ToolSchema.LOG_GREP,
                ToolSchema.CRASH_SCAN,
                ToolSchema.MEASURE_IDLE_DRAIN,
                ToolSchema.BATTERYSTATS_SNIPPET,
                ToolSchema.START_JOB,
                ToolSchema.STOP_JOB,
                ToolSchema.LIST_JOBS,
                ToolSchema.SAVE_MACRO,
                ToolSchema.DELETE_MACRO,
                ToolSchema.LIST_MACROS,
                ToolSchema.RUN_MACRO,
                ToolSchema.LIST_PLAYBOOKS,
                ToolSchema.RUN_PLAYBOOK,
                ToolSchema.DESCRIBE_ALERT_OPTIONS,
                ToolSchema.OFFER_CHOICES,
                ToolSchema.CREATE_MINIAPP,
                ToolSchema.LIST_MINIAPPS,
                ToolSchema.DELETE_MINIAPP,
                ToolSchema.OPEN_MINIAPP,
            )
        assertEquals(
            ToolSchema.TOOLS.map { it.name }.filter { it !in local }.toSet(),
            ToolSchema.BRIDGE.keys,
        )
        for (name in local) {
            assertEquals(name, "", ToolSchema.tool(name)!!.method)
        }
        for (tool in ToolSchema.TOOLS) {
            if (tool.name in local) continue
            val method = ToolSchema.BRIDGE[tool.name]!!
            assertEquals(tool.name, tool.method, method)
            assertTrue(tool.name, method.startsWith("ui.") ||
                method.startsWith("app.") || method.startsWith("functions."))
            // A tool with no branch in bridgeParams' when() dispatches an
            // empty params object, which is the failure this catches.
            val params = ToolSchema.bridgeParams(sample(tool), "t1", true)
            assertTrue(tool.name, params.length() > 0)
        }
    }

    @Test
    fun theScreenChangingSetIsMutatingAndDispatched() {
        for (name in ToolSchema.CHANGES_SCREEN) {
            assertTrue(name, ToolSchema.BRIDGE.containsKey(name))
            assertTrue(name, ToolSchema.tool(name)!!.mutating)
        }
        // A read never triggers a fresh screen read of its own.
        assertFalse(ToolSchema.CHANGES_SCREEN.contains(ToolSchema.READ_SCREEN))
        assertFalse(ToolSchema.CHANGES_SCREEN.contains(ToolSchema.SCREENSHOT))
    }

    @Test
    fun aNodeAddressedCallIsNeverBuiltWithoutATree() {
        // Without a tree id the bridge answers -32602 "tree_id is required
        // with node_id", which is an error about a parameter the model has
        // never seen. It is answered as a stale tree instead.
        for (name in listOf(ToolSchema.TAP, ToolSchema.LONG_PRESS)) {
            val params = ToolSchema.bridgeParams(valid(name, """{"node_id":4}"""), null, true)
            assertFalse(name, params.has("node_id"))
            assertFalse(name, params.has("tree_id"))
        }
        val type =
            ToolSchema.bridgeParams(
                valid(ToolSchema.TYPE, """{"text":"hi","node_id":4}"""),
                null,
                true,
            )
        assertFalse(type.has("node_id"))
        assertEquals("hi", type.getString("text"))
    }

    @Test
    fun theToolBlockStaysUnderItsBudget() {
        // Re-sent on every step of every task, and nothing caches it on most
        // presets. Measured, not guessed.
        assertTrue(
            "tools(false) is " + ToolSchema.tools(false).toString().length,
            ToolSchema.tools(false).toString().length < 14000,
        )
    }

    /** A minimal well-formed call for each tool, used to prove it dispatches. */
    private fun sample(tool: ToolSchema.Tool): ToolSchema.ToolCall =
        when (tool.name) {
            ToolSchema.READ_SCREEN -> valid(tool.name, "{}")
            ToolSchema.TAP -> valid(tool.name, """{"node_id":4}""")
            ToolSchema.LONG_PRESS -> valid(tool.name, """{"node_id":4}""")
            ToolSchema.SWIPE -> valid(tool.name, """{"from":[100,1800],"to":[100,400]}""")
            ToolSchema.TYPE -> valid(tool.name, """{"text":"hello"}""")
            ToolSchema.KEY -> valid(tool.name, """{"name":"back"}""")
            ToolSchema.LAUNCH_APP -> valid(tool.name, """{"package":"com.android.settings"}""")
            ToolSchema.LIST_APPS -> valid(tool.name, "{}")
            ToolSchema.LIST_FUNCTIONS -> valid(tool.name, "{}")
            ToolSchema.CALL_FUNCTION ->
                valid(tool.name, """{"package":"com.android.settings","function":"f"}""")
            ToolSchema.SCREENSHOT -> valid(tool.name, "{}", vision = true)
            ToolSchema.WAIT -> valid(tool.name, """{"ms":200}""")
            ToolSchema.SCHEDULE_REMINDER ->
                valid(tool.name, """{"message":"Drink water","in_minutes":15}""")
            ToolSchema.SCHEDULE_TASK ->
                valid(tool.name, """{"goal":"Turn on Wi-Fi","in_minutes":30}""")
            ToolSchema.LIST_REMINDERS -> valid(tool.name, "{}")
            ToolSchema.CANCEL_REMINDER -> valid(tool.name, """{"id":"abcd1234"}""")
            ToolSchema.LOG_TAIL -> valid(tool.name, """{"lines":80}""")
            ToolSchema.LOG_GREP -> valid(tool.name, """{"pattern":"FATAL","lines":40}""")
            ToolSchema.CRASH_SCAN -> valid(tool.name, """{"max":8}""")
            ToolSchema.MEASURE_IDLE_DRAIN -> valid(tool.name, "{}")
            ToolSchema.BATTERYSTATS_SNIPPET ->
                valid(tool.name, """{"mode":"full","focus":"summary"}""")
            ToolSchema.START_JOB ->
                valid(tool.name, """{"kind":"idle_drain","interval_minutes":30}""")
            ToolSchema.STOP_JOB -> valid(tool.name, """{"id":"abcd1234"}""")
            ToolSchema.LIST_JOBS -> valid(tool.name, "{}")
            ToolSchema.SAVE_MACRO ->
                valid(
                    tool.name,
                    """{"name":"Night drain","trigger":"interval","interval_minutes":60,"steps":"[{\"tool\":\"measure_idle_drain\"}]"}""",
                )
            ToolSchema.DELETE_MACRO -> valid(tool.name, """{"id":"abcd1234"}""")
            ToolSchema.LIST_MACROS -> valid(tool.name, "{}")
            ToolSchema.RUN_MACRO -> valid(tool.name, """{"id":"abcd1234"}""")
            ToolSchema.LIST_PLAYBOOKS -> valid(tool.name, "{}")
            ToolSchema.RUN_PLAYBOOK ->
                valid(tool.name, """{"id":"order_food","detail":"biryani"}""")
            ToolSchema.DESCRIBE_ALERT_OPTIONS -> valid(tool.name, "{}")
            ToolSchema.OFFER_CHOICES ->
                valid(
                    tool.name,
                    """{"prompt":"How to alert?","options":"[\"notification\",\"toast\",\"dialog\"]"}""",
                )
            ToolSchema.CREATE_MINIAPP ->
                valid(
                    tool.name,
                    """{"name":"Pushups","kind":"counter","unit":"reps"}""",
                )
            ToolSchema.LIST_MINIAPPS -> valid(tool.name, "{}")
            ToolSchema.DELETE_MINIAPP -> valid(tool.name, """{"id":"abcd1234"}""")
            ToolSchema.OPEN_MINIAPP -> valid(tool.name, """{"id":"abcd1234"}""")
            ToolSchema.DONE -> valid(tool.name, """{"answer":"done"}""")
            else -> valid(ToolSchema.DONE, """{"answer":"done"}""")
        }

    @Test
    fun aWellFormedCallIsAccepted() {
        val call = valid(ToolSchema.TAP, """{"node_id":12}""")
        assertEquals("tap", call.name)
        assertEquals(12, call.args.getInt("node_id"))
        assertTrue(call.tool.mutating)
    }

    @Test
    fun anUnknownToolNameIsRefused() {
        assertTrue(invalid("open_bank", "{}").contains("no tool"))
    }

    @Test
    fun argumentsThatAreNotAnObjectAreRefused() {
        assertTrue(invalid(ToolSchema.TAP, "{").contains("not a JSON object"))
        assertTrue(invalid(ToolSchema.TAP, "[1,2]").contains("not a JSON object"))
    }

    @Test
    fun aMissingRequiredParameterIsRefused() {
        assertTrue(invalid(ToolSchema.TYPE, "{}").contains("text"))
        assertTrue(invalid(ToolSchema.CALL_FUNCTION, """{"package":"a"}""").contains("function"))
        assertTrue(invalid(ToolSchema.DONE, "{}").contains("answer"))
    }

    @Test
    fun tapNeedsExactlyOneOfNodeIdAndAPoint() {
        assertTrue(invalid(ToolSchema.TAP, """{"node_id":1,"x":10,"y":20}""").contains("not both"))
        assertTrue(invalid(ToolSchema.TAP, "{}").contains("either node_id"))
        assertTrue(invalid(ToolSchema.TAP, """{"x":10}""").contains("either node_id"))
        // And the two legal shapes are accepted.
        valid(ToolSchema.TAP, """{"node_id":1}""")
        valid(ToolSchema.TAP, """{"x":10,"y":20}""")
    }

    @Test
    fun aKeyOutsideTheEnumIsRefused() {
        assertTrue(invalid(ToolSchema.KEY, """{"name":"power_off"}""").contains("must be one of"))
        for (name in ToolSchema.KEY_NAMES) valid(ToolSchema.KEY, """{"name":"$name"}""")
    }

    @Test
    fun textLongerThanTheLimitIsRefused() {
        val long = "a".repeat(ToolSchema.MAX_TYPE_CHARS + 1)
        assertTrue(invalid(ToolSchema.TYPE, JSONObject().put("text", long).toString())
            .contains("longer than"))
        val fits = "a".repeat(ToolSchema.MAX_TYPE_CHARS)
        valid(ToolSchema.TYPE, JSONObject().put("text", fits).toString())
    }

    @Test
    fun screenshotIsRefusedWhenItIsNotInTheSchema() {
        assertTrue(invalid(ToolSchema.SCREENSHOT, "{}").contains("screenshots are turned off"))
        valid(ToolSchema.SCREENSHOT, "{}", vision = true)
    }

    @Test
    fun aParameterOutsideItsRangeIsBroughtIntoIt() {
        assertEquals(400, valid(ToolSchema.READ_SCREEN, """{"max_nodes":5000}""")
            .args.getInt("max_nodes"))
        assertEquals(20, valid(ToolSchema.READ_SCREEN, """{"max_nodes":1}""")
            .args.getInt("max_nodes"))
    }

    @Test
    fun aParameterTheSchemaDoesNotNameIsNotPassedOn() {
        val call = valid(ToolSchema.TAP, """{"node_id":3,"confirm":true,"tree_id":"stolen"}""")
        // The model cannot smuggle the two fields the runner owns.
        assertFalse(call.args.has("confirm"))
        assertFalse(call.args.has("tree_id"))
        val params = ToolSchema.bridgeParams(call, "real-tree", false)
        assertEquals("real-tree", params.getString("tree_id"))
        assertFalse(params.has("confirm"))
    }

    @Test
    fun theRunnerFillsInTheTreeIdAndTheConfirmFlag() {
        val tap = valid(ToolSchema.TAP, """{"node_id":7}""")
        val params = ToolSchema.bridgeParams(tap, "abc123", true)
        assertEquals(7, params.getInt("node_id"))
        assertEquals("abc123", params.getString("tree_id"))
        assertTrue(params.getBoolean("confirm"))

        // A point-addressed tap carries no tree id: there is no node to be
        // stale against.
        val point = ToolSchema.bridgeParams(valid(ToolSchema.TAP, """{"x":1,"y":2}"""), "abc", true)
        assertFalse(point.has("tree_id"))

        // A read never carries confirm, whatever it is asked for.
        val read = ToolSchema.bridgeParams(valid(ToolSchema.READ_SCREEN, "{}"), "abc", true)
        assertFalse(read.has("confirm"))
    }

    @Test
    fun launchAppOffersOnlyThePackageForm() {
        val launch = valid(ToolSchema.LAUNCH_APP, """{"package":"com.android.settings"}""")
        val params = ToolSchema.bridgeParams(launch, null, true)
        assertEquals("com.android.settings", params.getString("package"))
        // The confused-deputy surface is not in the schema and cannot be
        // reached by naming it.
        assertFalse(params.has("component"))
        assertFalse(params.has("intent_uri"))
        val tool = ToolSchema.tool(ToolSchema.LAUNCH_APP)!!
        assertEquals(listOf("package"), tool.params.map { it.name })
    }

    @Test
    fun theToolsNotOfferedAreNotOffered() {
        // Bridge-only methods must not appear as model tool *names*.
        for (name in listOf("app.list", "log.list", "log.clear", "agent.stop", "agent.pair")) {
            assertNull(name, ToolSchema.tool(name))
        }
        // list_apps maps to app.list on the bridge; audit log.* stays off the model.
        val methods = ToolSchema.TOOLS.map { it.method }
        assertTrue(methods.contains("app.list"))
        assertFalse(methods.contains("log.list"))
        assertFalse(methods.contains("log.clear"))
        assertFalse(methods.contains("agent.stop"))
    }

    @Test
    fun everyBridgeErrorBecomesASentenceRatherThanACode() {
        for (code in listOf(-32003, -32005, -32006, -32011, -32012, -32602, -32999)) {
            val text = ToolSchema.errorText(code, "a message")
            assertTrue(text.isNotEmpty())
            assertFalse(text.contains(code.toString()))
        }
    }
}
