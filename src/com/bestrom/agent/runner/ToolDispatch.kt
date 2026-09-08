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

import com.bestrom.agent.audit.AuditLog
import com.bestrom.agent.bridge.JsonRpc
import com.bestrom.agent.bridge.Methods
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * The one place a model's tool call becomes a bridge call.
 *
 * It goes through Methods.dispatch, exactly as an adb client's request does,
 * with a session that is authenticated and whose peer uid says the phone asked.
 * That is the whole point: the keyguard check, the user-is-touching check, the
 * excluded apps list, the password refusal, the rate limiter, the confirm floor
 * and the audit entry are the same code for the runner as for the bridge, not a
 * second copy of them that can drift.
 */
class ToolDispatch(private val host: Methods.Host) {

    companion object {
        /** The session every runner request carries. */
        fun agentSession(): Methods.Session {
            val session = Methods.Session()
            session.authenticated = true
            session.peerUid = AuditLog.UID_AGENT
            session.connectionId = 0
            session.client = "agent"
            return session
        }
    }

    private val session = agentSession()
    private val ids = AtomicInteger(0)

    /** The tree the last read_screen came back with. The model never sees it. */
    @Volatile
    var treeId: String? = null
        private set

    /** The last screen the model was shown, for the policy engine and the diff. */
    @Volatile
    var digest: ScreenDigest.Digest? = null
        private set

    sealed class Outcome {
        class Ok(val result: JSONObject) : Outcome()

        /** [text] is the sentence the model is given; [code] is for the runner. */
        class Failed(val code: Int, val text: String, val data: JSONObject?) : Outcome()
    }

    /** One bridge call, with every guardrail the table declares for it. */
    fun call(method: String, params: JSONObject): Outcome {
        val request = JsonRpc.Request(ids.incrementAndGet(), method, params)
        val response = Methods.dispatch(host, session, request)
        val error = response.optJSONObject("error")
        if (error != null) {
            val code = error.optInt("code")
            if (code == JsonRpc.STALE_TREE) treeId = null
            return Outcome.Failed(
                code,
                ToolSchema.errorText(code, error.optString("message")),
                error.optJSONObject("data"),
            )
        }
        return Outcome.Ok(response.optJSONObject("result") ?: JSONObject())
    }

    /**
     * ui.tree, turned into the digest and remembered.
     *
     * The tree id is kept here rather than handed to the model, so a tap that
     * arrives after the screen moved is refused by the platform instead of
     * landing on whatever took the old node's place.
     */
    fun readScreen(maxNodes: Int = ToolSchema.DEFAULT_MAX_NODES): Outcome {
        val outcome = call("ui.tree", JSONObject().put("max_nodes", maxNodes))
        if (outcome is Outcome.Ok) {
            val fresh = ScreenDigest.of(outcome.result)
            treeId = fresh.treeId.ifEmpty { null }
            digest = fresh
        }
        return outcome
    }

    /**
     * A validated, policy-allowed tool call.
     *
     * [confirm] is put in the params only for a mutating method and only after
     * the policy engine allowed it; without it the dispatcher answers -32003.
     */
    fun run(toolCall: ToolSchema.ToolCall, confirm: Boolean): Outcome {
        if (toolCall.name == ToolSchema.READ_SCREEN) {
            return readScreen(toolCall.args.optInt("max_nodes", ToolSchema.DEFAULT_MAX_NODES))
        }
        return call(toolCall.tool.method, ToolSchema.bridgeParams(toolCall, treeId, confirm))
    }

    /** The methods whose result is worth a fresh look at the screen. */
    fun changesTheScreen(name: String): Boolean =
        name == ToolSchema.TAP ||
            name == ToolSchema.LONG_PRESS ||
            name == ToolSchema.SWIPE ||
            name == ToolSchema.TYPE ||
            name == ToolSchema.KEY ||
            name == ToolSchema.LAUNCH_APP
}
