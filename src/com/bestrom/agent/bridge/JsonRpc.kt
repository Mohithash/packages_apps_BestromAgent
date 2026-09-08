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


package com.bestrom.agent.bridge

import org.json.JSONObject

/** JSON-RPC 2.0 request and response objects plus the error code table. */
object JsonRpc {

    const val VERSION = "2.0"
    const val PROTOCOL = 1

    // Standard JSON-RPC.
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // Agent bridge.
    const val UNAUTHENTICATED = -32001
    const val BAD_PAIRING_CODE = -32002
    const val CONFIRM_REQUIRED = -32003
    const val AGENT_DISABLED = -32004
    const val DEVICE_LOCKED = -32005
    const val USER_INTERACTING = -32006
    const val RATE_LIMITED = -32007
    const val NODE_NOT_FOUND = -32008
    const val ACTION_FAILED = -32009
    const val APP_FUNCTION_ERROR = -32010
    const val STALE_TREE = -32011
    const val SECURE_WINDOW = -32012
    const val SCREENSHOT_UNAVAILABLE = -32013
    const val TIMEOUT = -32014
    const val NOT_INSTALLED = -32015

    /** A parsed request. [id] is the raw JSON value so it can be echoed unchanged. */
    class Request(
        val id: Any?,
        val method: String,
        val params: JSONObject,
    )

    /** Raised by a method body; the dispatcher turns it into an error response. */
    class RpcException(
        val code: Int,
        message: String,
        val data: JSONObject? = null,
    ) : Exception(message)

    fun result(id: Any?, result: JSONObject): JSONObject =
        JSONObject()
            .put("jsonrpc", VERSION)
            .put("id", id ?: JSONObject.NULL)
            .put("result", result)

    fun error(id: Any?, code: Int, message: String, data: JSONObject? = null): JSONObject {
        val err = JSONObject().put("code", code).put("message", message)
        if (data != null) err.put("data", data)
        return JSONObject()
            .put("jsonrpc", VERSION)
            .put("id", id ?: JSONObject.NULL)
            .put("error", err)
    }

    /**
     * Parses one line into a [Request].
     *
     * Batches and notifications are rejected here rather than in the dispatcher,
     * so no method body can be reached by either shape.
     */
    fun parse(line: String): Request {
        val trimmed = line.trim()
        if (trimmed.startsWith("[")) {
            throw RpcException(INVALID_REQUEST, "batch requests are not supported")
        }
        val obj =
            try {
                JSONObject(trimmed)
            } catch (e: Exception) {
                throw RpcException(PARSE_ERROR, "line is not a JSON object")
            }
        if (obj.optString("jsonrpc") != VERSION) {
            throw RpcException(INVALID_REQUEST, "jsonrpc must be \"2.0\"")
        }
        if (!obj.has("id") || obj.isNull("id")) {
            throw RpcException(INVALID_REQUEST, "notifications are not supported")
        }
        val id = obj.get("id")
        if (id !is String && id !is Int && id !is Long) {
            throw RpcException(INVALID_REQUEST, "id must be a string or an integer")
        }
        val method = obj.optString("method", "")
        if (method.isEmpty()) {
            throw RpcException(INVALID_REQUEST, "method is required")
        }
        val params = obj.optJSONObject("params") ?: JSONObject()
        return Request(id, method, params)
    }
}
