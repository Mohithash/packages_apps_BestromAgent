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

import com.bestrom.agent.AgentState
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
        if (toolCall.name == ToolSchema.WAIT) {
            val ms =
                toolCall.args
                    .optInt("ms", ToolSchema.DEFAULT_WAIT_MS)
                    .coerceIn(ToolSchema.MIN_WAIT_MS, ToolSchema.MAX_WAIT_MS)
            try {
                Thread.sleep(ms.toLong())
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return Outcome.Failed(JsonRpc.ACTION_FAILED, "wait interrupted", null)
            }
            return Outcome.Ok(JSONObject().put("ok", true).put("waited_ms", ms))
        }
        if (toolCall.name == ToolSchema.SCHEDULE_REMINDER ||
            toolCall.name == ToolSchema.SCHEDULE_TASK ||
            toolCall.name == ToolSchema.LIST_REMINDERS ||
            toolCall.name == ToolSchema.CANCEL_REMINDER
        ) {
            return com.bestrom.agent.schedule.ReminderTools.run(host.context, toolCall)
        }
        if (toolCall.name == ToolSchema.SCHEDULE_EVENT) {
            return com.bestrom.agent.schedule.CalendarEventTools.run(host.context, toolCall)
        }
        if (toolCall.name == ToolSchema.LOG_TAIL ||
            toolCall.name == ToolSchema.LOG_GREP ||
            toolCall.name == ToolSchema.CRASH_SCAN
        ) {
            return com.bestrom.agent.diag.LogTools.run(host.context, toolCall)
        }
        if (toolCall.name == ToolSchema.MEASURE_IDLE_DRAIN) {
            return com.bestrom.agent.diag.DrainTools.run(host.context, toolCall)
        }
        if (toolCall.name == ToolSchema.BATTERYSTATS_SNIPPET) {
            return com.bestrom.agent.diag.BatterystatsTools.run(host.context, toolCall)
        }
        if (toolCall.name == ToolSchema.START_JOB ||
            toolCall.name == ToolSchema.STOP_JOB ||
            toolCall.name == ToolSchema.LIST_JOBS
        ) {
            return com.bestrom.agent.jobs.JobTools.run(host.context, toolCall)
        }
        if (toolCall.name == ToolSchema.SAVE_MACRO ||
            toolCall.name == ToolSchema.DELETE_MACRO ||
            toolCall.name == ToolSchema.LIST_MACROS ||
            toolCall.name == ToolSchema.RUN_MACRO
        ) {
            return com.bestrom.agent.macro.MacroTools.run(host.context, toolCall)
        }
        if (toolCall.name == ToolSchema.LIST_PLAYBOOKS ||
            toolCall.name == ToolSchema.RUN_PLAYBOOK
        ) {
            return com.bestrom.agent.playbook.PlaybookTools.run(host.context, toolCall)
        }
        if (toolCall.name == ToolSchema.CREATE_MINIAPP ||
            toolCall.name == ToolSchema.LIST_MINIAPPS ||
            toolCall.name == ToolSchema.DELETE_MINIAPP ||
            toolCall.name == ToolSchema.OPEN_MINIAPP
        ) {
            return com.bestrom.agent.miniapps.MiniAppTools.run(host.context, toolCall)
        }
        val stale = staleTarget(toolCall)
        if (stale != null) {
            return Outcome.Failed(JsonRpc.STALE_TREE, stale, null)
        }
        val method =
            ToolSchema.BRIDGE[toolCall.name]
                ?: return Outcome.Failed(JsonRpc.INVALID_PARAMS, "that tool does nothing", null)
        return call(method, ToolSchema.bridgeParams(toolCall, treeId, confirm))
    }

    /**
     * Whether the target this call names is still the screen in front.
     *
     * Two cases. A node-addressed call with no tree id would reach the bridge
     * without one and be answered "tree_id is required with node_id" - an
     * error about a parameter the model has never seen and cannot supply, so
     * it is answered as a stale tree instead, which the runner knows how to
     * recover from. And a tap by x and y is checked against nothing by the
     * platform, while the policy engine classified it against the last digest,
     * so it is refused unless the window in front is still that one.
     */
    private fun staleTarget(toolCall: ToolSchema.ToolCall): String? {
        val name = toolCall.name
        if (name != ToolSchema.TAP && name != ToolSchema.LONG_PRESS && name != ToolSchema.TYPE) {
            return null
        }
        if (toolCall.args.has("node_id")) {
            return if (treeId == null) ToolSchema.errorText(JsonRpc.STALE_TREE, "") else null
        }
        // type without a node goes to the focused field, whatever that is.
        if (name == ToolSchema.TYPE) return null
        val current = digest ?: return "there is nothing to tap yet; read the screen first"
        val live = AgentState.a11y?.activeWindowPackage()
        if (live == null || live != current.windowPackage) {
            return ToolSchema.errorText(JsonRpc.STALE_TREE, "")
        }
        return null
    }

    /** The methods whose result is worth a fresh look at the screen. */
    fun changesTheScreen(name: String): Boolean = ToolSchema.CHANGES_SCREEN.contains(name)
}
