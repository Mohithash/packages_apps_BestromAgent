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
        assertEquals(10, without.size)
        assertEquals(11, with.size)
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
                "list_functions",
                "call_function",
                "screenshot",
                "done",
            ),
            names(ToolSchema.tools(true)),
        )
    }

    @Test
    fun everyToolInTheSchemaHasABridgeMethodOrIsTerminal() {
        // The dispatcher's when() is driven by this table, so a tool added
        // without a method would be dispatched to nothing.
        for (tool in ToolSchema.TOOLS) {
            if (tool.name == ToolSchema.DONE) {
                assertEquals("", tool.method)
            } else {
                assertTrue(tool.name, tool.method.startsWith("ui.") ||
                    tool.method.startsWith("app.") || tool.method.startsWith("functions."))
                // And it builds params without throwing, which is the other
                // half of "wired up".
                assertNotNull(ToolSchema.bridgeParams(sample(tool), "t1", true))
            }
        }
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
            ToolSchema.LIST_FUNCTIONS -> valid(tool.name, "{}")
            ToolSchema.CALL_FUNCTION ->
                valid(tool.name, """{"package":"com.android.settings","function":"f"}""")
            ToolSchema.SCREENSHOT -> valid(tool.name, "{}", vision = true)
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
        for (name in listOf("app.list", "log.list", "log.clear", "agent.stop", "agent.pair")) {
            assertNull(name, ToolSchema.tool(name))
        }
        val methods = ToolSchema.TOOLS.map { it.method }
        assertFalse(methods.contains("app.list"))
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
