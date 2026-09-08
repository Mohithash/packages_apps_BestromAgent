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
 * The app functions, one line each.
 *
 * Called once at the start of a task and then carried in the stable part of
 * the prompt, so the model can reach for a function without spending a round
 * trip discovering it.
 *
 * The parameter shapes are handled with a deliberately low opinion of them.
 * AppFunctionMetadata's parameters are a repeated GenericDocument whose
 * flattening is not schema-guaranteed, so a shape this does not recognise is
 * reported as unknown rather than guessed at: "try it and read the error" is
 * honest and costs one failed call, where a guess costs a wrong one.
 */
object FunctionCatalog {

    /** The keys a flattened parameter document has been seen to use for its name. */
    private val NAME_KEYS = listOf("name", "parameterName", "id", "key")

    private val TYPE_KEYS = listOf("dataType", "type", "valueType", "dataTypeName")

    private val REQUIRED_KEYS = listOf("isRequired", "required")

    /** Integer data types, as AppFunctionData spells them, mapped to words. */
    private val TYPE_NAMES =
        mapOf(
            0 to "unit",
            1 to "boolean",
            2 to "byte",
            3 to "object",
            4 to "double",
            5 to "float",
            6 to "int",
            7 to "long",
            8 to "string",
            9 to "pending intent",
        )

    /** One line per function, sorted, so the prompt prefix is stable. */
    fun lines(list: JSONObject, max: Int = 200): List<String> {
        val functions = list.optJSONArray("functions") ?: return emptyList()
        val out = ArrayList<String>(minOf(functions.length(), max))
        for (i in 0 until functions.length()) {
            val f = functions.optJSONObject(i) ?: continue
            if (!f.optBoolean("enabled", true)) continue
            out.add(line(f))
        }
        out.sort()
        return if (out.size <= max) out else out.subList(0, max)
    }

    /** The catalogue as one block of text, or a sentence saying it is empty. */
    fun text(list: JSONObject?): String {
        if (list == null) return "None. This device publishes no app functions."
        val lines = lines(list)
        if (lines.isEmpty()) {
            val reason = list.optString("fallback_reason")
            return if (reason.isEmpty()) "None. This device publishes no app functions."
            else "None were readable ($reason)."
        }
        return lines.joinToString("\n")
    }

    private fun line(f: JSONObject): String {
        val pkg = f.optString("package")
        val id = f.optString("function_id")
        val sb = StringBuilder()
        sb.append(pkg).append('/').append(id)

        // The client writes description as a plain string; a flattening that
        // left it as a single-element array is read the same way.
        val description = (unwrap(f.opt("description")) as? String)
            ?.let { InjectionFilter.sanitise(it, 160) }
            .orEmpty()
        if (description.isNotEmpty()) {
            sb.append(" - ").append(description)
        } else {
            val schema = f.optJSONObject("schema")
            val name = (unwrap(schema?.opt("name")) as? String).orEmpty()
            if (name.isNotEmpty()) sb.append(" - ").append(InjectionFilter.sanitise(name, 64))
        }

        sb.append(" - params: ").append(params(f.optJSONArray("parameters")))
        return sb.toString()
    }

    private fun params(parameters: JSONArray?): String {
        if (parameters == null) return "unknown, try it and read the error"
        if (parameters.length() == 0) return "none"
        val parts = ArrayList<String>(parameters.length())
        for (i in 0 until parameters.length()) {
            val p = parameters.optJSONObject(i) ?: return "unknown, try it and read the error"
            val name = firstString(p, NAME_KEYS) ?: return "unknown, try it and read the error"
            val type = typeOf(p)
            val required = firstBoolean(p, REQUIRED_KEYS)
            val sb = StringBuilder(name)
            if (type != null) sb.append('(').append(type).append(')')
            if (required == false) sb.append(" optional")
            parts.add(sb.toString())
        }
        return parts.joinToString(", ")
    }

    private fun typeOf(p: JSONObject): String? {
        for (key in TYPE_KEYS) {
            if (!p.has(key) || p.isNull(key)) continue
            val value = unwrap(p.opt(key)) ?: continue
            if (value is Number) return TYPE_NAMES[value.toInt()] ?: "type " + value.toInt()
            if (value is String && value.isNotEmpty()) return value.lowercase()
        }
        return null
    }

    private fun firstString(p: JSONObject, keys: List<String>): String? {
        for (key in keys) {
            if (!p.has(key) || p.isNull(key)) continue
            val value = unwrap(p.opt(key))
            if (value is String && value.isNotEmpty()) {
                return InjectionFilter.sanitise(value, 64)
            }
        }
        return null
    }

    private fun firstBoolean(p: JSONObject, keys: List<String>): Boolean? {
        for (key in keys) {
            if (!p.has(key) || p.isNull(key)) continue
            val value = unwrap(p.opt(key))
            if (value is Boolean) return value
            if (value is Number) return value.toInt() != 0
        }
        return null
    }

    /**
     * A flattened GenericDocument property is an array even when it holds one
     * value, and the client encodes it without collapsing singles on purpose,
     * so both shapes are read here rather than assumed.
     */
    private fun unwrap(value: Any?): Any? {
        if (value is JSONArray) {
            if (value.length() == 0) return null
            return value.opt(0)
        }
        return value
    }
}
