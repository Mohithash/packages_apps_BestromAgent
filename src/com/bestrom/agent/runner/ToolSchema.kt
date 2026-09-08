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

/**
 * The tools the model is offered, and the only place a tool name is written.
 *
 * Each one maps onto a single bridge method. The fields the model has no
 * business choosing are not in the schema at all and are filled in here
 * instead: the tree id, the confirm flag, the encoding, and the component and
 * intent forms of app.launch, which are never offered.
 *
 * Names, parameter names and the mapping to a method all live in this table, so
 * a rename cannot drift between what the model is told and what is dispatched.
 */
object ToolSchema {

    const val READ_SCREEN = "read_screen"
    const val TAP = "tap"
    const val LONG_PRESS = "long_press"
    const val SWIPE = "swipe"
    const val TYPE = "type"
    const val KEY = "key"
    const val LAUNCH_APP = "launch_app"
    const val LIST_FUNCTIONS = "list_functions"
    const val CALL_FUNCTION = "call_function"
    const val SCREENSHOT = "screenshot"
    const val DONE = "done"

    const val MAX_TYPE_CHARS = 4096

    /** The keys ui.key accepts, and the whole set the model may name. */
    val KEY_NAMES: List<String> =
        listOf(
            "back",
            "home",
            "recents",
            "notifications",
            "quick_settings",
            "lock_screen",
            "power_dialog",
            "dismiss_notification_shade",
        )

    class Param(
        val name: String,
        val type: String,
        val required: Boolean,
        val description: String,
        val enumValues: List<String>? = null,
        val minimum: Int? = null,
        val maximum: Int? = null,
        val itemType: String? = null,
    )

    /**
     * [method] is the bridge method this tool becomes; done has none because
     * the runner answers it without reaching the capability layer.
     */
    class Tool(
        val name: String,
        val method: String,
        val mutating: Boolean,
        val description: String,
        val params: List<Param>,
        val vision: Boolean = false,
    )

    /**
     * The order is fixed. A caching provider sees a byte-identical prefix only
     * while the tools serialise the same way every step of every task.
     */
    val TOOLS: List<Tool> =
        listOf(
            Tool(
                READ_SCREEN,
                "ui.tree",
                false,
                "Read what is currently on screen. Returns one line per element with an id " +
                    "you pass to tap, long_press or type. Call this before acting when you do " +
                    "not already know what is on screen.",
                listOf(
                    Param(
                        "max_nodes",
                        "integer",
                        false,
                        "How many elements to read at most. 120 by default.",
                        minimum = 20,
                        maximum = 400,
                    )
                ),
            ),
            Tool(
                TAP,
                "ui.tap",
                true,
                "Tap an element by its id from read_screen, or a point on screen. Prefer the id.",
                listOf(
                    Param("node_id", "integer", false, "The element id from read_screen."),
                    Param("x", "integer", false, "Screen x, when no element matches."),
                    Param("y", "integer", false, "Screen y, when no element matches."),
                ),
            ),
            Tool(
                LONG_PRESS,
                "ui.long_press",
                true,
                "Press and hold an element or a point.",
                listOf(
                    Param("node_id", "integer", false, "The element id from read_screen."),
                    Param("x", "integer", false, "Screen x, when no element matches."),
                    Param("y", "integer", false, "Screen y, when no element matches."),
                    Param(
                        "duration_ms",
                        "integer",
                        false,
                        "How long to hold. 600 by default.",
                        minimum = 1,
                        maximum = 3000,
                    ),
                ),
            ),
            Tool(
                SWIPE,
                "ui.swipe",
                true,
                "Swipe from one point to another. To scroll a list down, swipe from a point " +
                    "low on screen to a point high on it.",
                listOf(
                    Param("from", "array", true, "The point to start at, [x, y].", itemType = "integer"),
                    Param("to", "array", true, "The point to end at, [x, y].", itemType = "integer"),
                    Param(
                        "duration_ms",
                        "integer",
                        false,
                        "How long the swipe takes. 300 by default.",
                        minimum = 1,
                        maximum = 3000,
                    ),
                ),
            ),
            Tool(
                TYPE,
                "ui.type",
                true,
                "Type text into a text field. Password fields are refused.",
                listOf(
                    Param("text", "string", true, "The text to type."),
                    Param(
                        "node_id",
                        "integer",
                        false,
                        "The field to type into. The focused field when left out.",
                    ),
                    Param(
                        "replace",
                        "boolean",
                        false,
                        "Replace what is in the field. True by default.",
                    ),
                ),
            ),
            Tool(
                KEY,
                "ui.key",
                true,
                "Press a system key.",
                listOf(Param("name", "string", true, "Which key.", enumValues = KEY_NAMES)),
            ),
            Tool(
                LAUNCH_APP,
                "app.launch",
                true,
                "Open an app by package name. The installed apps are listed in your instructions.",
                listOf(Param("package", "string", true, "The package name to open.")),
            ),
            Tool(
                LIST_FUNCTIONS,
                "functions.list",
                false,
                "List the app functions this device publishes. The catalogue is already in " +
                    "your instructions; call this again only if you need one app's functions " +
                    "in more detail.",
                listOf(Param("package", "string", false, "Only this app's functions.")),
            ),
            Tool(
                CALL_FUNCTION,
                "functions.execute",
                true,
                "Call an app function. This is the direct way to change a device setting; " +
                    "prefer it over navigating Settings by hand.",
                listOf(
                    Param("package", "string", true, "The app that publishes the function."),
                    Param("function", "string", true, "The function id from the catalogue."),
                    Param("params", "object", false, "The function's own parameters."),
                ),
            ),
            Tool(
                SCREENSHOT,
                "ui.screenshot",
                false,
                "Take a screenshot. Use it only when read_screen does not describe what you " +
                    "need, for example an image or a chart.",
                emptyList(),
                vision = true,
            ),
            Tool(
                DONE,
                "",
                false,
                "Finish the task. answer must contain the actual result the user asked for, " +
                    "not a description of what you did. If the task cannot be done, say why here.",
                listOf(Param("answer", "string", true, "What the user reads.")),
            ),
        )

    private val BY_NAME: Map<String, Tool> = TOOLS.associateBy { it.name }

    fun tool(name: String): Tool? = BY_NAME[name]

    /** The names offered for a given vision setting, in schema order. */
    fun names(vision: Boolean): List<String> =
        TOOLS.filter { vision || !it.vision }.map { it.name }

    /**
     * The tools array, exactly as it goes on the wire.
     *
     * There is no "strict" field: Anthropic's compatibility layer ignores it
     * and some LAN servers answer 400 for it.
     */
    fun tools(vision: Boolean): JSONArray {
        val array = JSONArray()
        for (tool in TOOLS) {
            if (tool.vision && !vision) continue
            array.put(describe(tool))
        }
        return array
    }

    private fun describe(tool: Tool): JSONObject {
        val properties = JSONObject()
        val required = JSONArray()
        for (p in tool.params) {
            val schema = JSONObject().put("type", p.type).put("description", p.description)
            if (p.enumValues != null) {
                val values = JSONArray()
                for (v in p.enumValues) values.put(v)
                schema.put("enum", values)
            }
            if (p.minimum != null) schema.put("minimum", p.minimum)
            if (p.maximum != null) schema.put("maximum", p.maximum)
            if (p.itemType != null) {
                schema.put("items", JSONObject().put("type", p.itemType))
                schema.put("minItems", 2)
                schema.put("maxItems", 2)
            }
            properties.put(p.name, schema)
            if (p.required) required.put(p.name)
        }
        return JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", tool.name)
                    .put("description", tool.description)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", properties)
                            .put("required", required)
                            .put("additionalProperties", false),
                    ),
            )
    }

    // ------------------------------------------------------------- validation

    /** A call the schema accepted. [args] holds only parameters the schema names. */
    class ToolCall(val id: String, val tool: Tool, val args: JSONObject) {
        val name: String
            get() = tool.name
    }

    sealed class Validation {
        class Valid(val call: ToolCall) : Validation()

        /** [reason] is what the model is told, verbatim. */
        class Invalid(val reason: String) : Validation()
    }

    /**
     * Checks a call from the model before anything else looks at it.
     *
     * A bad call is never a transport failure: it is reported back as a tool
     * result the model can correct, and three in a row end the task.
     */
    fun validate(id: String, name: String, argumentsJson: String, vision: Boolean): Validation {
        val tool = BY_NAME[name] ?: return Validation.Invalid("no tool is called \"$name\"")
        if (tool.vision && !vision) {
            return Validation.Invalid("$name is not available; screenshots are turned off")
        }
        val raw =
            try {
                JSONObject(if (argumentsJson.isBlank()) "{}" else argumentsJson)
            } catch (e: Exception) {
                return Validation.Invalid("the arguments are not a JSON object")
            }

        val args = JSONObject()
        for (p in tool.params) {
            if (!raw.has(p.name) || raw.isNull(p.name)) {
                if (p.required) return Validation.Invalid("$name needs ${p.name}")
                continue
            }
            when (p.type) {
                "integer" -> {
                    val v =
                        raw.opt(p.name).let {
                            when (it) {
                                is Number -> it.toInt()
                                is String -> it.toIntOrNull()
                                else -> null
                            }
                        } ?: return Validation.Invalid("${p.name} must be a whole number")
                    var value = v
                    if (p.minimum != null) value = maxOf(value, p.minimum)
                    if (p.maximum != null) value = minOf(value, p.maximum)
                    args.put(p.name, value)
                }
                "boolean" -> {
                    val v =
                        raw.opt(p.name).let {
                            when (it) {
                                is Boolean -> it
                                is String -> it.toBooleanStrictOrNull()
                                else -> null
                            }
                        } ?: return Validation.Invalid("${p.name} must be true or false")
                    args.put(p.name, v)
                }
                "string" -> {
                    val v = raw.opt(p.name)
                    if (v !is String) return Validation.Invalid("${p.name} must be text")
                    if (p.enumValues != null && !p.enumValues.contains(v)) {
                        return Validation.Invalid(
                            "${p.name} must be one of " + p.enumValues.joinToString(", ")
                        )
                    }
                    args.put(p.name, v)
                }
                "array" -> {
                    val v = raw.optJSONArray(p.name)
                    if (v == null || v.length() != 2) {
                        return Validation.Invalid("${p.name} must be [x, y]")
                    }
                    val point = JSONArray()
                    for (i in 0 until 2) {
                        val n = v.opt(i)
                        val asInt =
                            when (n) {
                                is Number -> n.toInt()
                                is String -> n.toIntOrNull()
                                else -> null
                            } ?: return Validation.Invalid("${p.name} must be [x, y]")
                        point.put(asInt)
                    }
                    args.put(p.name, point)
                }
                "object" -> {
                    val v = raw.optJSONObject(p.name)
                        ?: return Validation.Invalid("${p.name} must be an object")
                    args.put(p.name, v)
                }
            }
        }

        val constraint = constraintFailure(tool, args)
        if (constraint != null) return Validation.Invalid(constraint)
        return Validation.Valid(ToolCall(id, tool, args))
    }

    private fun constraintFailure(tool: Tool, args: JSONObject): String? {
        when (tool.name) {
            TAP, LONG_PRESS -> {
                val hasNode = args.has("node_id")
                val hasX = args.has("x")
                val hasY = args.has("y")
                if (hasNode && (hasX || hasY)) {
                    return "give either node_id, or x and y, not both"
                }
                if (!hasNode && !(hasX && hasY)) {
                    return "give either node_id, or x and y"
                }
            }
            TYPE -> {
                val text = args.optString("text")
                if (text.length > MAX_TYPE_CHARS) {
                    return "text is longer than $MAX_TYPE_CHARS characters"
                }
            }
            LAUNCH_APP -> {
                if (args.optString("package").isEmpty()) return "package must not be empty"
            }
            CALL_FUNCTION -> {
                if (args.optString("package").isEmpty()) return "package must not be empty"
                if (args.optString("function").isEmpty()) return "function must not be empty"
            }
            DONE -> {
                if (args.optString("answer").isEmpty()) return "answer must not be empty"
            }
        }
        return null
    }

    // -------------------------------------------------------------- dispatch

    /**
     * The params for the bridge method, with the runner-owned fields filled in.
     *
     * [treeId] is what the last read_screen came back with; the model never
     * sees it, so a stale tree is caught by the platform rather than papered
     * over by re-issuing the tap against whatever is on screen now.
     */
    fun bridgeParams(call: ToolCall, treeId: String?, confirm: Boolean): JSONObject {
        val out = JSONObject()
        when (call.name) {
            READ_SCREEN -> {
                out.put("max_nodes", call.args.optInt("max_nodes", DEFAULT_MAX_NODES))
            }
            TAP, LONG_PRESS -> {
                // node_id without a tree_id is answered -32602 by the bridge,
                // so it is never built: the runner reads the screen again
                // instead.
                if (call.args.has("node_id") && treeId != null) {
                    out.put("node_id", call.args.optInt("node_id"))
                    out.put("tree_id", treeId)
                } else if (!call.args.has("node_id")) {
                    out.put("x", call.args.optInt("x"))
                    out.put("y", call.args.optInt("y"))
                }
                if (call.args.has("duration_ms")) {
                    out.put("duration_ms", call.args.optInt("duration_ms"))
                }
            }
            SWIPE -> {
                out.put("from", call.args.optJSONArray("from"))
                out.put("to", call.args.optJSONArray("to"))
                if (call.args.has("duration_ms")) {
                    out.put("duration_ms", call.args.optInt("duration_ms"))
                }
            }
            TYPE -> {
                out.put("text", call.args.optString("text"))
                if (call.args.has("node_id") && treeId != null) {
                    out.put("node_id", call.args.optInt("node_id"))
                    out.put("tree_id", treeId)
                }
                if (call.args.has("replace")) out.put("replace", call.args.optBoolean("replace"))
            }
            KEY -> out.put("name", call.args.optString("name"))
            LAUNCH_APP -> out.put("package", call.args.optString("package"))
            LIST_FUNCTIONS -> {
                if (call.args.has("package")) out.put("package", call.args.optString("package"))
                out.put("include_schema", true)
            }
            CALL_FUNCTION -> {
                out.put("package", call.args.optString("package"))
                out.put("function", call.args.optString("function"))
                out.put("params", call.args.optJSONObject("params") ?: JSONObject())
            }
            SCREENSHOT -> out.put("encoding", "base64")
        }
        // The confirm floor in Methods.dispatch is untouched: the runner has to
        // put this here, and it only does so after the policy engine allowed
        // the call.
        if (call.tool.mutating && confirm) out.put("confirm", true)
        return out
    }

    const val DEFAULT_MAX_NODES = 120

    /**
     * A JSON-RPC error turned into the one line the model is told.
     *
     * Short on purpose: the model's job is to recover, and a stack of codes
     * spends tokens without helping it do that.
     */
    fun errorText(code: Int, message: String): String =
        when (code) {
            -32003 -> "that action needs the user's approval and did not have it"
            -32004 -> "Agent mode was turned off"
            -32005 -> "the phone is locked"
            -32006 -> "the user is touching the screen"
            -32007 -> "too many actions too quickly"
            -32008 -> "there is no such element on screen"
            -32009 -> "the action did not take effect"
            -32010 -> "the app function failed: $message"
            -32011 -> "the screen changed; read it again"
            -32012 -> "refused: $message"
            -32013 -> "the screenshot was refused"
            -32014 -> "the app function did not answer in time"
            -32015 -> "that package is not installed"
            -32602 -> "invalid arguments: $message"
            else -> message
        }
}
