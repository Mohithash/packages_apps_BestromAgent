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
 *
 * The descriptions are short on purpose. This block is re-sent on every step of
 * every task and nothing here caches it, so the working rules - prefer an
 * element id, prefer call_function, do not repeat an action that changed
 * nothing - are stated once in the system prompt instead of eleven times here.
 */
object ToolSchema {

    const val READ_SCREEN = "read_screen"
    const val TAP = "tap"
    const val LONG_PRESS = "long_press"
    const val SWIPE = "swipe"
    const val TYPE = "type"
    const val KEY = "key"
    const val LAUNCH_APP = "launch_app"
    const val LIST_APPS = "list_apps"
    const val LIST_FUNCTIONS = "list_functions"
    const val CALL_FUNCTION = "call_function"
    const val SCREENSHOT = "screenshot"
    const val WAIT = "wait"
    const val DONE = "done"
    const val SCHEDULE_REMINDER = "schedule_reminder"
    const val SCHEDULE_TASK = "schedule_task"
    const val LIST_REMINDERS = "list_reminders"
    const val CANCEL_REMINDER = "cancel_reminder"
    const val LOG_TAIL = "log_tail"
    const val LOG_GREP = "log_grep"
    const val CRASH_SCAN = "crash_scan"
    const val MEASURE_IDLE_DRAIN = "measure_idle_drain"
    const val BATTERYSTATS_SNIPPET = "batterystats_snippet"
    const val START_JOB = "start_job"
    const val STOP_JOB = "stop_job"
    const val LIST_JOBS = "list_jobs"
    const val SAVE_MACRO = "save_macro"
    const val DELETE_MACRO = "delete_macro"
    const val LIST_MACROS = "list_macros"
    const val RUN_MACRO = "run_macro"
    const val LIST_PLAYBOOKS = "list_playbooks"
    const val RUN_PLAYBOOK = "run_playbook"
    const val OFFER_CHOICES = "offer_choices"
    const val DESCRIBE_ALERT_OPTIONS = "describe_alert_options"
    const val CREATE_MINIAPP = "create_miniapp"
    const val LIST_MINIAPPS = "list_miniapps"
    const val DELETE_MINIAPP = "delete_miniapp"
    const val OPEN_MINIAPP = "open_miniapp"

    const val MAX_TYPE_CHARS = 4096
    const val MIN_WAIT_MS = 100
    const val MAX_WAIT_MS = 10000
    const val DEFAULT_WAIT_MS = 1000

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
                "Read the screen: one line per element, with an id.",
                listOf(
                    Param(
                        "max_nodes",
                        "integer",
                        false,
                        "120 by default.",
                        minimum = 20,
                        maximum = 400,
                    )
                ),
            ),
            Tool(
                TAP,
                "ui.tap",
                true,
                "Tap an element by id, or a point.",
                listOf(
                    Param("node_id", "integer", false, "An id from read_screen."),
                    Param("x", "integer", false, ""),
                    Param("y", "integer", false, ""),
                ),
            ),
            Tool(
                LONG_PRESS,
                "ui.long_press",
                true,
                "Press and hold an element or a point.",
                listOf(
                    Param("node_id", "integer", false, "An id from read_screen."),
                    Param("x", "integer", false, ""),
                    Param("y", "integer", false, ""),
                    Param(
                        "duration_ms",
                        "integer",
                        false,
                        "600 by default.",
                        minimum = 1,
                        maximum = 3000,
                    ),
                ),
            ),
            Tool(
                SWIPE,
                "ui.swipe",
                true,
                "Swipe between two points.",
                listOf(
                    Param("from", "array", true, "", itemType = "integer"),
                    Param("to", "array", true, "", itemType = "integer"),
                    Param(
                        "duration_ms",
                        "integer",
                        false,
                        "300 by default.",
                        minimum = 1,
                        maximum = 3000,
                    ),
                ),
            ),
            Tool(
                TYPE,
                "ui.type",
                true,
                "Type into a text field.",
                listOf(
                    Param("text", "string", true, ""),
                    Param("node_id", "integer", false, "The focused field when left out."),
                    Param("replace", "boolean", false, "True by default."),
                ),
            ),
            Tool(
                KEY,
                "ui.key",
                true,
                "Press a system key.",
                listOf(Param("name", "string", true, "", enumValues = KEY_NAMES)),
            ),
            Tool(
                LAUNCH_APP,
                "app.launch",
                true,
                "Open an app by package name.",
                listOf(Param("package", "string", true, "")),
            ),
            Tool(
                LIST_APPS,
                "app.list",
                false,
                "List installed apps (package and label).",
                emptyList(),
            ),
            Tool(
                LIST_FUNCTIONS,
                "functions.list",
                false,
                "List app functions in detail.",
                listOf(Param("package", "string", false, "Only this app's.")),
            ),
            Tool(
                CALL_FUNCTION,
                "functions.execute",
                true,
                "Call an app function.",
                listOf(
                    Param("package", "string", true, ""),
                    Param("function", "string", true, "The id from the catalogue."),
                    Param("params", "object", false, ""),
                ),
            ),
            Tool(
                SCREENSHOT,
                "ui.screenshot",
                false,
                "Take a screenshot.",
                emptyList(),
                vision = true,
            ),
            Tool(
                WAIT,
                "",
                false,
                "Pause before reading the screen again.",
                listOf(
                    Param(
                        "ms",
                        "integer",
                        false,
                        "$DEFAULT_WAIT_MS by default.",
                        minimum = MIN_WAIT_MS,
                        maximum = MAX_WAIT_MS,
                    )
                ),
            ),
            Tool(
                SCHEDULE_REMINDER,
                "",
                true,
                "Schedule a local notification reminder.",
                listOf(
                    Param("message", "string", true, "What the notification says."),
                    Param(
                        "in_minutes",
                        "integer",
                        false,
                        "Fire after this many minutes (1..43200).",
                        minimum = 1,
                        maximum = 43200,
                    ),
                    Param("at_unix_ms", "integer", false, "Or an absolute unix time in ms."),
                ),
            ),
            Tool(
                SCHEDULE_TASK,
                "",
                true,
                "Schedule an agent goal to run later (needs Agent mode on at fire time).",
                listOf(
                    Param("goal", "string", true, "The goal to run later."),
                    Param(
                        "in_minutes",
                        "integer",
                        false,
                        "Fire after this many minutes (1..43200).",
                        minimum = 1,
                        maximum = 43200,
                    ),
                    Param("at_unix_ms", "integer", false, "Or an absolute unix time in ms."),
                ),
            ),
            Tool(
                LIST_REMINDERS,
                "",
                false,
                "List pending reminders and scheduled tasks.",
                emptyList(),
            ),
            Tool(
                CANCEL_REMINDER,
                "",
                true,
                "Cancel a reminder or scheduled task by id.",
                listOf(Param("id", "string", true, "From list_reminders.")),
            ),
            Tool(
                LOG_TAIL,
                "",
                false,
                "Read recent logcat (Maintainer+). Bounded and redacted.",
                listOf(
                    Param(
                        "lines",
                        "integer",
                        false,
                        "How many lines (20..400).",
                        minimum = com.bestrom.agent.diag.LogLimits.MIN_LINES,
                        maximum = com.bestrom.agent.diag.LogLimits.MAX_LINES,
                    ),
                    Param("tag", "string", false, "Optional logcat tag filter."),
                ),
            ),
            Tool(
                LOG_GREP,
                "",
                false,
                "Grep recent logcat for a pattern (Maintainer+).",
                listOf(
                    Param("pattern", "string", true, "Case-insensitive substring."),
                    Param(
                        "lines",
                        "integer",
                        false,
                        "Max matching lines to return.",
                        minimum = com.bestrom.agent.diag.LogLimits.MIN_LINES,
                        maximum = com.bestrom.agent.diag.LogLimits.MAX_LINES,
                    ),
                    Param("tag", "string", false, "Optional logcat tag filter."),
                ),
            ),
            Tool(
                CRASH_SCAN,
                "",
                false,
                "List recent process exits / ANRs (Maintainer+).",
                listOf(
                    Param(
                        "max",
                        "integer",
                        false,
                        "Max entries (1..16).",
                        minimum = 1,
                        maximum = com.bestrom.agent.diag.LogLimits.MAX_CRASH_ENTRIES,
                    ),
                ),
            ),
            Tool(
                MEASURE_IDLE_DRAIN,
                "",
                false,
                "Battery snapshot; call twice for idle drain delta (Background+).",
                emptyList(),
            ),
            Tool(
                BATTERYSTATS_SNIPPET,
                "",
                false,
                "Bounded batterystats dump (Maintainer+).",
                listOf(
                    Param(
                        "mode",
                        "string",
                        false,
                        "full or checkin.",
                        enumValues =
                            listOf(
                                com.bestrom.agent.diag.BatterystatsLimits.MODE_FULL,
                                com.bestrom.agent.diag.BatterystatsLimits.MODE_CHECKIN,
                            ),
                    ),
                    Param(
                        "focus",
                        "string",
                        false,
                        "summary, discharge, screen, wifi, cpu, uid, …",
                    ),
                    Param(
                        "max_bytes",
                        "integer",
                        false,
                        "Size cap.",
                        minimum = com.bestrom.agent.diag.BatterystatsLimits.MIN_MAX_BYTES,
                        maximum = com.bestrom.agent.diag.BatterystatsLimits.MAX_MAX_BYTES,
                    ),
                ),
            ),
            Tool(
                START_JOB,
                "",
                true,
                "Start a repeating background job (Background+).",
                listOf(
                    Param(
                        "kind",
                        "string",
                        true,
                        "idle_drain or error_watch.",
                        enumValues =
                            listOf(
                                com.bestrom.agent.jobs.JobLimits.KIND_IDLE_DRAIN,
                                com.bestrom.agent.jobs.JobLimits.KIND_ERROR_WATCH,
                            ),
                    ),
                    Param(
                        "interval_minutes",
                        "integer",
                        true,
                        "15..1440.",
                        minimum = com.bestrom.agent.jobs.JobLimits.MIN_INTERVAL_MINUTES,
                        maximum = com.bestrom.agent.jobs.JobLimits.MAX_INTERVAL_MINUTES,
                    ),
                    Param("label", "string", false, "Short name for the notification."),
                ),
            ),
            Tool(
                STOP_JOB,
                "",
                true,
                "Stop a background job by id.",
                listOf(Param("id", "string", true, "From list_jobs.")),
            ),
            Tool(
                LIST_JOBS,
                "",
                false,
                "List repeating background jobs.",
                emptyList(),
            ),
            Tool(
                SAVE_MACRO,
                "",
                true,
                "Save a macro (Full).",
                listOf(
                    Param("name", "string", true, "Macro name."),
                    Param(
                        "trigger",
                        "string",
                        true,
                        "interval, once, boot, or battery_below.",
                        enumValues =
                            listOf(
                                com.bestrom.agent.macro.MacroLimits.TRIGGER_INTERVAL,
                                com.bestrom.agent.macro.MacroLimits.TRIGGER_ONCE,
                                com.bestrom.agent.macro.MacroLimits.TRIGGER_BOOT,
                                com.bestrom.agent.macro.MacroLimits.TRIGGER_BATTERY_BELOW,
                            ),
                    ),
                    Param(
                        "interval_minutes",
                        "integer",
                        false,
                        "For trigger=interval.",
                        minimum = com.bestrom.agent.macro.MacroLimits.MIN_INTERVAL_MINUTES,
                        maximum = com.bestrom.agent.macro.MacroLimits.MAX_INTERVAL_MINUTES,
                    ),
                    Param("at_unix_ms", "integer", false, "For trigger=once."),
                    Param(
                        "battery_below_pct",
                        "integer",
                        false,
                        "For trigger=battery_below. Fires once per dip under this percent.",
                        minimum = com.bestrom.agent.macro.MacroLimits.MIN_BATTERY_BELOW_PCT,
                        maximum = com.bestrom.agent.macro.MacroLimits.MAX_BATTERY_BELOW_PCT,
                    ),
                    Param(
                        "steps",
                        "string",
                        true,
                        "JSON array of {tool,args}. Tools: measure_idle_drain, crash_scan, log_grep, notify, schedule_reminder, run_goal.",
                    ),
                ),
            ),
            Tool(
                DELETE_MACRO,
                "",
                true,
                "Delete a macro by id.",
                listOf(Param("id", "string", true, "From list_macros.")),
            ),
            Tool(
                LIST_MACROS,
                "",
                false,
                "List saved macros.",
                emptyList(),
            ),
            Tool(
                RUN_MACRO,
                "",
                true,
                "Run a macro once now.",
                listOf(Param("id", "string", true, "From list_macros.")),
            ),
            Tool(
                LIST_PLAYBOOKS,
                "",
                false,
                "List curated app playbooks.",
                emptyList(),
            ),
            Tool(
                RUN_PLAYBOOK,
                "",
                true,
                "Run a curated playbook goal (Full). Pay still confirms.",
                listOf(
                    Param("id", "string", true, "From list_playbooks."),
                    Param(
                        "detail",
                        "string",
                        false,
                        "When needs_detail (food, place, alarm).",
                    ),
                ),
            ),
            Tool(
                DESCRIBE_ALERT_OPTIONS,
                "",
                false,
                "Explain toast / notification / dialog alert options.",
                emptyList(),
            ),
            Tool(
                OFFER_CHOICES,
                "",
                true,
                "Ask the user to pick one option on the phone.",
                listOf(
                    Param("prompt", "string", true, "Question shown on the sheet."),
                    Param(
                        "options",
                        "string",
                        true,
                        "JSON array of short labels, e.g. [\"notification\",\"toast\",\"dialog\"].",
                    ),
                ),
            ),
            Tool(
                CREATE_MINIAPP,
                "",
                true,
                "Create a vibecode mini app (Full). Kinds: counter, checklist, daily_log, timer.",
                listOf(
                    Param("name", "string", true, "Short title."),
                    Param(
                        "kind",
                        "string",
                        true,
                        "counter, checklist, daily_log, or timer.",
                        enumValues =
                            listOf(
                                com.bestrom.agent.miniapps.MiniAppLimits.KIND_COUNTER,
                                com.bestrom.agent.miniapps.MiniAppLimits.KIND_CHECKLIST,
                                com.bestrom.agent.miniapps.MiniAppLimits.KIND_DAILY_LOG,
                                com.bestrom.agent.miniapps.MiniAppLimits.KIND_TIMER,
                            ),
                    ),
                    Param("goal", "string", false, "Optional blurb."),
                    Param("unit", "string", false, "For counter (cups, reps, …)."),
                    Param(
                        "items",
                        "string",
                        false,
                        "JSON string array for checklist.",
                    ),
                    Param(
                        "timer_minutes",
                        "integer",
                        false,
                        "For timer.",
                        minimum = com.bestrom.agent.miniapps.MiniAppLimits.MIN_TIMER_MINUTES,
                        maximum = com.bestrom.agent.miniapps.MiniAppLimits.MAX_TIMER_MINUTES,
                    ),
                ),
            ),
            Tool(
                LIST_MINIAPPS,
                "",
                false,
                "List vibecode mini apps.",
                emptyList(),
            ),
            Tool(
                DELETE_MINIAPP,
                "",
                true,
                "Delete a vibecode mini app by id.",
                listOf(Param("id", "string", true, "From list_miniapps.")),
            ),
            Tool(
                OPEN_MINIAPP,
                "",
                true,
                "Open a vibecode mini app on screen.",
                listOf(Param("id", "string", true, "From list_miniapps.")),
            ),
            Tool(
                DONE,
                "",
                false,
                "Finish. answer carries the result itself.",
                listOf(Param("answer", "string", true, "What the user reads.")),
            ),
        )

    /**
     * Minimum [com.bestrom.agent.brain.AutonomyLevel] for tools that unlock
     * above Assist/Task. null means any level (normal UI / schedule tools).
     */
    fun minAutonomy(name: String): com.bestrom.agent.brain.AutonomyLevel? =
        when (name) {
            MEASURE_IDLE_DRAIN, START_JOB, STOP_JOB, LIST_JOBS ->
                com.bestrom.agent.brain.AutonomyLevel.BACKGROUND
            LOG_TAIL, LOG_GREP, CRASH_SCAN, BATTERYSTATS_SNIPPET ->
                com.bestrom.agent.brain.AutonomyLevel.MAINTAINER
            SAVE_MACRO, DELETE_MACRO, LIST_MACROS, RUN_MACRO,
            LIST_PLAYBOOKS, RUN_PLAYBOOK,
            CREATE_MINIAPP, LIST_MINIAPPS, DELETE_MINIAPP, OPEN_MINIAPP ->
                com.bestrom.agent.brain.AutonomyLevel.FULL
            else -> null
        }

    private val BY_NAME: Map<String, Tool> = TOOLS.associateBy { it.name }

    /**
     * Tool name to bridge method, for everything but the terminal tool.
     *
     * The dispatcher reads this rather than keeping a second copy, so a tool
     * added to the table above without a method - or without a branch in
     * [bridgeParams] - fails a host test instead of dispatching an empty
     * params object on the phone.
     */
    val BRIDGE: Map<String, String> =
        TOOLS.filter { it.method.isNotEmpty() }.associate { it.name to it.method }

    /** The tools whose result is worth a fresh look at the screen. */
    val CHANGES_SCREEN: Set<String> =
        setOf(TAP, LONG_PRESS, SWIPE, TYPE, KEY, LAUNCH_APP)

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
            val schema = JSONObject().put("type", p.type)
            // A description that says no more than the name and the type does
            // is left out: this block is re-sent on every step of every task.
            if (p.description.isNotEmpty()) schema.put("description", p.description)
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
        val source =
            if (tool.name == CALL_FUNCTION) FunctionCatalog.normalizeFunctionArgs(raw) else raw

        val args = JSONObject()
        for (p in tool.params) {
            if (!source.has(p.name) || source.isNull(p.name)) {
                if (p.required) return Validation.Invalid("$name needs ${p.name}")
                continue
            }
            when (p.type) {
                "integer" -> {
                    val v =
                        source.opt(p.name).let {
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
                        source.opt(p.name).let {
                            when (it) {
                                is Boolean -> it
                                is String -> it.toBooleanStrictOrNull()
                                else -> null
                            }
                        } ?: return Validation.Invalid("${p.name} must be true or false")
                    args.put(p.name, v)
                }
                "string" -> {
                    val v = source.opt(p.name)
                    if (v !is String) return Validation.Invalid("${p.name} must be text")
                    if (p.enumValues != null && !p.enumValues.contains(v)) {
                        return Validation.Invalid(
                            "${p.name} must be one of " + p.enumValues.joinToString(", ")
                        )
                    }
                    args.put(p.name, v)
                }
                "array" -> {
                    val v = source.optJSONArray(p.name)
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
                    val v = source.optJSONObject(p.name)
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
            WAIT -> {
                if (args.has("ms")) {
                    val ms = args.optInt("ms")
                    if (ms < MIN_WAIT_MS || ms > MAX_WAIT_MS) {
                        return "ms must be between $MIN_WAIT_MS and $MAX_WAIT_MS"
                    }
                }
            }
            SCHEDULE_REMINDER -> {
                val msg = com.bestrom.agent.schedule.ReminderTime.rejectText(args.optString("message"))
                if (msg != null) return msg
                return com.bestrom.agent.schedule.ReminderTime.rejectWhen(args)
            }
            SCHEDULE_TASK -> {
                val msg = com.bestrom.agent.schedule.ReminderTime.rejectText(args.optString("goal"))
                if (msg != null) return msg.replace("message", "goal")
                return com.bestrom.agent.schedule.ReminderTime.rejectWhen(args)
            }
            CANCEL_REMINDER -> {
                if (args.optString("id").isEmpty()) return "id must not be empty"
            }
            LOG_TAIL -> {
                val tagErr = com.bestrom.agent.diag.LogLimits.rejectTag(args.optString("tag"))
                if (tagErr != null) return tagErr
            }
            LOG_GREP -> {
                val pErr = com.bestrom.agent.diag.LogLimits.rejectPattern(args.optString("pattern"))
                if (pErr != null) return pErr
                val tagErr = com.bestrom.agent.diag.LogLimits.rejectTag(args.optString("tag"))
                if (tagErr != null) return tagErr
            }
            CRASH_SCAN -> {
                if (args.has("max")) {
                    val m = args.optInt("max")
                    if (m < 1 || m > com.bestrom.agent.diag.LogLimits.MAX_CRASH_ENTRIES) {
                        return "max must be between 1 and ${com.bestrom.agent.diag.LogLimits.MAX_CRASH_ENTRIES}"
                    }
                }
            }
            BATTERYSTATS_SNIPPET -> {
                com.bestrom.agent.diag.BatterystatsLimits.rejectMode(args.optString("mode"))
                    ?.let {
                        return it
                    }
                return com.bestrom.agent.diag.BatterystatsLimits.rejectFocus(
                    args.optString("focus")
                )
            }
            OFFER_CHOICES -> {
                if (args.optString("prompt").trim().isEmpty()) return "prompt must not be empty"
                val raw = args.opt("options")
                val arr =
                    when (raw) {
                        is org.json.JSONArray -> raw
                        is String ->
                            try {
                                org.json.JSONArray(raw)
                            } catch (_: Exception) {
                                null
                            }
                        else -> null
                    }
                if (arr == null || arr.length() == 0) return "options must be a JSON array"
                if (arr.length() > 6) return "at most 6 options"
            }
            CREATE_MINIAPP -> {
                return com.bestrom.agent.miniapps.MiniAppLimits.rejectCreate(
                    args.optString("name"),
                    args.optString("kind"),
                    args.optString("goal"),
                    args.optString("unit"),
                    com.bestrom.agent.miniapps.MiniAppLimits.parseItems(args.opt("items")),
                    args.optInt("timer_minutes", 25),
                )
            }
            DELETE_MINIAPP, OPEN_MINIAPP -> {
                if (args.optString("id").isEmpty()) return "id must not be empty"
            }
            START_JOB -> {
                com.bestrom.agent.jobs.JobLimits.rejectKind(args.optString("kind"))?.let {
                    return it
                }
                com.bestrom.agent.jobs.JobLimits.rejectInterval(args.optInt("interval_minutes", -1))
                    ?.let {
                        return it
                    }
                com.bestrom.agent.jobs.JobLimits.rejectLabel(args.optString("label"))?.let {
                    return it
                }
            }
            STOP_JOB, DELETE_MACRO, RUN_MACRO -> {
                if (args.optString("id").isEmpty()) return "id must not be empty"
            }
            RUN_PLAYBOOK -> {
                com.bestrom.agent.playbook.PlaybookCatalog.rejectId(args.optString("id"))
                    ?.let {
                        return it
                    }
                val p =
                    com.bestrom.agent.playbook.PlaybookCatalog.get(args.optString("id"))
                        ?: return "unknown playbook id"
                return com.bestrom.agent.playbook.PlaybookCatalog.rejectDetail(
                    p,
                    args.optString("detail"),
                )
            }
            SAVE_MACRO -> {
                com.bestrom.agent.macro.MacroLimits.rejectName(args.optString("name"))?.let {
                    return it
                }
                com.bestrom.agent.macro.MacroLimits.rejectTrigger(args.optString("trigger"))?.let {
                    return it
                }
                val steps = com.bestrom.agent.macro.MacroLimits.parseSteps(args.opt("steps"))
                com.bestrom.agent.macro.MacroLimits.rejectSteps(steps)?.let {
                    return it
                }
                return com.bestrom.agent.macro.MacroLimits.rejectSchedule(
                    args.optString("trigger"),
                    args.optInt("interval_minutes", 0),
                    args.optLong("at_unix_ms", 0L),
                    args.optInt("battery_below_pct", 0),
                )
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
            LIST_APPS -> out.put("launchable_only", true)
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
            WAIT -> {
                out.put(
                    "ms",
                    call.args.optInt("ms", DEFAULT_WAIT_MS).coerceIn(MIN_WAIT_MS, MAX_WAIT_MS),
                )
            }
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
            -32010 ->
                if (message.contains("not found", ignoreCase = true)) {
                    "function not found — use an exact id from the catalogue, or drive the UI with launch_app/tap"
                } else {
                    "the app function failed: $message"
                }
            -32011 -> "the screen changed; read it again"
            -32012 -> "refused: $message"
            -32013 -> "the screenshot was refused"
            -32014 -> "the app function did not answer in time"
            -32015 -> "that package is not installed"
            -32602 -> "invalid arguments: $message"
            else -> message
        }
}
