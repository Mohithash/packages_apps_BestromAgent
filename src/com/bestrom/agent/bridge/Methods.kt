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

import android.app.KeyguardManager
import android.app.appfunctions.AppFunctionException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.os.SystemProperties
import android.util.Base64
import com.bestrom.agent.AgentState
import com.bestrom.agent.a11y.Actions
import com.bestrom.agent.a11y.TreeSerializer
import com.bestrom.agent.audit.AuditLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * The method table and the dispatcher.
 *
 * Every guardrail lives in the dispatcher and is driven by the table, so no
 * method body can forget one: a method that is declared mutating cannot run
 * without confirm, a method declared to act cannot run on a locked phone or
 * while the user is touching the screen, and a method declared rate limited
 * cannot escape the bucket.
 */
object Methods {

    const val IDLE_TIMEOUT_S = 1800
    const val RATE_LIMIT_PER_S = 10
    const val MAX_CLIENT_CHARS = 64
    const val MAX_TYPE_CHARS = 4096

    enum class AuthLevel {
        NONE,
        SESSION,
    }

    /** Per-connection state. The token itself lives in [Auth], not here. */
    class Session {
        var authenticated = false
        var strikes = 0
        var client = ""
    }

    /** What the dispatcher needs to answer a request. */
    interface Host {
        val auth: Auth
        val rateLimiter: RateLimiter
        val audit: AuditLog
        val functions: com.bestrom.agent.functions.AppFunctionsClient
        val context: android.content.Context
        val callbackExecutor: java.util.concurrent.Executor

        fun onAuthenticated()

        fun stopAgent()

        fun noteRequest()
    }

    class Spec(
        val name: String,
        val auth: AuthLevel,
        val confirm: Boolean,
        val keyguard: Boolean,
        val interaction: Boolean,
        val rateLimited: Boolean,
        val needsA11y: Boolean,
        val target: (JSONObject) -> String,
        val handler: (Host, Session, JSONObject) -> JSONObject,
    )

    val CAPABILITIES: List<String> =
        listOf(
            "functions.list",
            "functions.execute",
            "ui.tree",
            "ui.tap",
            "ui.long_press",
            "ui.swipe",
            "ui.type",
            "ui.key",
            "ui.screenshot",
            "app.launch",
            "app.list",
            "log.list",
            "log.clear",
            "agent.stop",
        )

    private val NO_TARGET: (JSONObject) -> String = { "" }

    val TABLE: Map<String, Spec> =
        listOf(
            Spec("agent.hello", AuthLevel.NONE, false, false, false, false, false,
                { it.optString("client").take(MAX_CLIENT_CHARS) }, ::hello),
            Spec("agent.pair", AuthLevel.NONE, false, false, false, false, false,
                NO_TARGET, ::pair),
            Spec("agent.auth", AuthLevel.NONE, false, false, false, false, false,
                NO_TARGET, ::auth),
            Spec("functions.list", AuthLevel.SESSION, false, false, false, false, false,
                { it.optString("package", "*") }, ::functionsList),
            Spec("functions.execute", AuthLevel.SESSION, true, true, true, true, false,
                { it.optString("package") + "/" + it.optString("function") }, ::functionsExecute),
            Spec("ui.tree", AuthLevel.SESSION, false, true, false, false, true,
                NO_TARGET, ::uiTree),
            Spec("ui.tap", AuthLevel.SESSION, true, true, true, true, true,
                ::pointTarget, ::uiTap),
            Spec("ui.long_press", AuthLevel.SESSION, true, true, true, true, true,
                ::pointTarget, ::uiLongPress),
            Spec("ui.swipe", AuthLevel.SESSION, true, true, true, true, true,
                NO_TARGET, ::uiSwipe),
            Spec("ui.type", AuthLevel.SESSION, true, true, true, true, true,
                NO_TARGET, ::uiType),
            Spec("ui.key", AuthLevel.SESSION, true, true, true, true, true,
                { it.optString("name") }, ::uiKey),
            Spec("ui.screenshot", AuthLevel.SESSION, false, true, false, false, true,
                NO_TARGET, ::uiScreenshot),
            Spec("app.launch", AuthLevel.SESSION, true, true, true, false, false,
                { it.optString("package", it.optString("component")) }, ::appLaunch),
            Spec("app.list", AuthLevel.SESSION, false, false, false, false, false,
                NO_TARGET, ::appList),
            Spec("log.list", AuthLevel.SESSION, false, false, false, false, false,
                NO_TARGET, ::logList),
            Spec("log.clear", AuthLevel.SESSION, true, false, false, false, false,
                NO_TARGET, ::logClear),
            Spec("agent.stop", AuthLevel.SESSION, true, false, false, false, false,
                NO_TARGET, ::agentStop),
        )
            .associateBy { it.name }

    /**
     * Runs one request through every guardrail its table entry declares, then
     * the body, and records the outcome in the audit log.
     */
    fun dispatch(host: Host, session: Session, request: JsonRpc.Request): JSONObject {
        val spec =
            TABLE[request.method]
                ?: return audited(
                    host,
                    request.method,
                    "",
                    System.currentTimeMillis(),
                    JsonRpc.error(request.id, JsonRpc.METHOD_NOT_FOUND, "no such method"),
                    JsonRpc.METHOD_NOT_FOUND,
                )

        val params = request.params
        val target = spec.target(params)
        val started = System.currentTimeMillis()

        fun refuse(code: Int, message: String, data: JSONObject? = null): JSONObject =
            audited(
                host,
                spec.name,
                target,
                started,
                JsonRpc.error(request.id, code, message, data),
                code,
            )

        if (spec.auth == AuthLevel.SESSION && !session.authenticated) {
            return refuse(JsonRpc.UNAUTHENTICATED, "pair or authenticate first")
        }
        if (spec.auth == AuthLevel.SESSION) host.noteRequest()

        if (spec.confirm && params.opt("confirm") as? Boolean != true) {
            return refuse(JsonRpc.CONFIRM_REQUIRED, "confirm must be true")
        }
        if (spec.auth == AuthLevel.SESSION && !AgentState.bridgeLive.get()) {
            return refuse(JsonRpc.AGENT_DISABLED, "the bridge is shutting down")
        }
        if (spec.needsA11y && AgentState.a11y == null) {
            return refuse(JsonRpc.AGENT_DISABLED, "the accessibility service is not connected")
        }
        if (spec.keyguard && deviceLocked(host)) {
            return refuse(JsonRpc.DEVICE_LOCKED, "the device is locked")
        }
        if (spec.interaction && AgentState.a11y?.userInteracting() == true) {
            return refuse(JsonRpc.USER_INTERACTING, "the user is touching the screen")
        }
        if (spec.rateLimited) {
            val retry = host.rateLimiter.acquire(System.currentTimeMillis())
            if (retry > 0) {
                return refuse(
                    JsonRpc.RATE_LIMITED,
                    "too many requests",
                    JSONObject().put("retry_after_ms", retry),
                )
            }
        }

        return try {
            val result = spec.handler(host, session, params)
            audited(host, spec.name, target, started, JsonRpc.result(request.id, result), null)
        } catch (e: JsonRpc.RpcException) {
            audited(
                host,
                spec.name,
                target,
                started,
                JsonRpc.error(request.id, e.code, e.message ?: "error", e.data),
                e.code,
            )
        } catch (e: Exception) {
            audited(
                host,
                spec.name,
                target,
                started,
                JsonRpc.error(
                    request.id,
                    JsonRpc.INTERNAL_ERROR,
                    e.javaClass.simpleName,
                ),
                JsonRpc.INTERNAL_ERROR,
            )
        }
    }

    private fun audited(
        host: Host,
        method: String,
        target: String,
        started: Long,
        response: JSONObject,
        errorCode: Int?,
    ): JSONObject {
        host.audit.append(
            method,
            target,
            if (errorCode == null) "ok" else "error",
            errorCode,
            System.currentTimeMillis() - started,
        )
        return response
    }

    private fun deviceLocked(host: Host): Boolean {
        val keyguard = host.context.getSystemService(KeyguardManager::class.java) ?: return false
        return keyguard.isDeviceLocked
    }

    private fun pointTarget(params: JSONObject): String =
        if (params.has("node_id")) "node:" + params.optInt("node_id")
        else params.optInt("x").toString() + "," + params.optInt("y")

    private fun utc(millis: Long): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date(millis))
    }

    private fun capabilitiesJson(): JSONArray {
        val array = JSONArray()
        for (c in CAPABILITIES) array.put(c)
        return array
    }

    // ---------------------------------------------------------------- agent.*

    private fun hello(host: Host, session: Session, params: JSONObject): JSONObject {
        if (params.optInt("protocol", -1) != JsonRpc.PROTOCOL) {
            throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "protocol must be 1")
        }
        session.client = params.optString("client").take(MAX_CLIENT_CHARS)

        val pm = host.context.packageManager
        val version =
            try {
                pm.getPackageInfo(host.context.packageName, 0).versionName ?: "1"
            } catch (e: PackageManager.NameNotFoundException) {
                "1"
            }

        val device =
            JSONObject()
                .put("model", Build.MODEL)
                .put("device", Build.DEVICE)
                .put("sdk", Build.VERSION.SDK_INT)
                .put("fingerprint", Build.FINGERPRINT)
                .put("bestrom_version", SystemProperties.get("ro.bestrom.version", ""))

        val state =
            JSONObject()
                .put("paired", host.auth.isPaired())
                .put("a11y_connected", AgentState.a11y != null)
                .put("keyguard_locked", deviceLocked(host))
                .put("rate_limit_per_s", RATE_LIMIT_PER_S)
                .put("idle_timeout_s", IDLE_TIMEOUT_S)
                .put("audit_capacity", AuditLog.CAPACITY)

        return JSONObject()
            .put("protocol", JsonRpc.PROTOCOL)
            .put("app_version", version)
            .put("device", device)
            .put("capabilities", capabilitiesJson())
            .put("state", state)
    }

    private fun pair(host: Host, session: Session, params: JSONObject): JSONObject {
        val code = params.optString("code")
        val outcome = host.auth.pair(code, System.currentTimeMillis())
        if (outcome is Auth.PairResult.Cooldown) {
            throw JsonRpc.RpcException(
                JsonRpc.RATE_LIMITED,
                "too many wrong codes",
                JSONObject().put("retry_after_ms", outcome.retryAfterMs),
            )
        }
        if (outcome !is Auth.PairResult.Ok) {
            session.strikes++
            throw JsonRpc.RpcException(JsonRpc.BAD_PAIRING_CODE, "wrong pairing code")
        }
        session.authenticated = true
        session.strikes = 0
        host.onAuthenticated()
        return JSONObject()
            .put("token", outcome.token)
            .put("expires_utc", utc(outcome.expiresAtMs))
            .put("capabilities", capabilitiesJson())
    }

    private fun auth(host: Host, session: Session, params: JSONObject): JSONObject {
        if (!host.auth.verify(params.optString("token"))) {
            session.strikes++
            throw JsonRpc.RpcException(JsonRpc.UNAUTHENTICATED, "bad token")
        }
        session.authenticated = true
        session.strikes = 0
        host.onAuthenticated()
        return JSONObject().put("ok", true)
    }

    private fun agentStop(host: Host, session: Session, params: JSONObject): JSONObject {
        host.stopAgent()
        return JSONObject().put("ok", true)
    }

    // ------------------------------------------------------------ functions.*

    private fun functionsList(host: Host, session: Session, params: JSONObject): JSONObject {
        val filter = params.optString("package").takeIf { it.isNotEmpty() }
        val includeSchema = params.optBoolean("include_schema", true)
        return host.functions.list(filter, includeSchema)
    }

    private fun functionsExecute(host: Host, session: Session, params: JSONObject): JSONObject {
        val pkg = params.optString("package")
        val function = params.optString("function")
        if (pkg.isEmpty() || function.isEmpty()) {
            throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "package and function are required")
        }
        if (!host.functions.isInstalled(pkg)) {
            throw JsonRpc.RpcException(JsonRpc.NOT_INSTALLED, "no such package")
        }
        val timeout = params.optLong("timeout_ms", 30000L).coerceIn(1L, 120000L)
        val callParams = params.optJSONObject("params") ?: JSONObject()

        val outcome = host.functions.execute(pkg, function, callParams, timeout)
        if (outcome.timedOut) {
            throw JsonRpc.RpcException(JsonRpc.TIMEOUT, "the function did not answer in time")
        }
        val code = outcome.errorCode
        if (code != null) {
            throw JsonRpc.RpcException(
                JsonRpc.APP_FUNCTION_ERROR,
                errorName(code),
                JSONObject()
                    .put("code", code)
                    .put("category", outcome.errorCategory ?: JSONObject.NULL)
                    .put("message", outcome.errorMessage ?: JSONObject.NULL),
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("result", outcome.result ?: JSONObject())
            .put("extras", outcome.extras)
            .put("duration_ms", outcome.durationMs)
    }

    private fun errorName(code: Int): String =
        when (code) {
            AppFunctionException.ERROR_DENIED -> "denied"
            AppFunctionException.ERROR_INVALID_ARGUMENT -> "invalid argument"
            AppFunctionException.ERROR_DISABLED -> "function disabled"
            AppFunctionException.ERROR_FUNCTION_NOT_FOUND -> "function not found"
            AppFunctionException.ERROR_SYSTEM_ERROR -> "system error"
            AppFunctionException.ERROR_CANCELLED -> "cancelled"
            AppFunctionException.ERROR_ENTERPRISE_POLICY_DISALLOWED -> "disallowed by policy"
            AppFunctionException.ERROR_APP_UNKNOWN_ERROR -> "app error"
            else -> "app function error"
        }

    // ------------------------------------------------------------------- ui.*

    private fun uiTree(host: Host, session: Session, params: JSONObject): JSONObject {
        val service =
            AgentState.a11y
                ?: throw JsonRpc.RpcException(JsonRpc.AGENT_DISABLED, "not connected")
        val maxDepth = params.optInt("max_depth", TreeSerializer.DEFAULT_MAX_DEPTH).coerceIn(1, 100)
        val maxNodes = params.optInt("max_nodes", TreeSerializer.DEFAULT_MAX_NODES).coerceIn(1, 5000)
        val includeInvisible = params.optBoolean("include_invisible", false)
        val snapshot =
            service.snapshot(maxDepth, maxNodes, includeInvisible)
                ?: throw JsonRpc.RpcException(
                    JsonRpc.SECURE_WINDOW,
                    "the active window is not readable",
                )
        return snapshot.json
    }

    private fun resolveNode(params: JSONObject): android.view.accessibility.AccessibilityNodeInfo? {
        val service = AgentState.a11y ?: return null
        if (!params.has("node_id")) return null
        val treeId =
            params.optString("tree_id").ifEmpty {
                throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "tree_id is required with node_id")
            }
        if (service.currentTreeId() != treeId) {
            throw JsonRpc.RpcException(JsonRpc.STALE_TREE, "that tree is no longer the current one")
        }
        return service.nodeAt(treeId, params.optInt("node_id"))
            ?: throw JsonRpc.RpcException(JsonRpc.NODE_NOT_FOUND, "no such node in that tree")
    }

    private fun requirePointOrNode(params: JSONObject) {
        val hasNode = params.has("node_id")
        val hasPoint = params.has("x") || params.has("y")
        if (hasNode == hasPoint) {
            throw JsonRpc.RpcException(
                JsonRpc.INVALID_PARAMS,
                "give either tree_id and node_id, or x and y",
            )
        }
    }

    private fun centreOf(node: android.view.accessibility.AccessibilityNodeInfo): IntArray {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        return intArrayOf(rect.centerX(), rect.centerY())
    }

    private fun uiTap(host: Host, session: Session, params: JSONObject): JSONObject {
        val service =
            AgentState.a11y ?: throw JsonRpc.RpcException(JsonRpc.AGENT_DISABLED, "not connected")
        requirePointOrNode(params)
        val node = resolveNode(params)
        if (node != null) {
            if (Actions.clickNode(node)) {
                return JSONObject()
                    .put("ok", true)
                    .put("method", "node")
                    .put("target", node.viewIdResourceName ?: node.className?.toString() ?: "node")
            }
            val centre = centreOf(node)
            if (!Actions.tapGesture(service, centre[0], centre[1])) {
                throw JsonRpc.RpcException(JsonRpc.ACTION_FAILED, "the tap did not take effect")
            }
            return JSONObject()
                .put("ok", true)
                .put("method", "gesture")
                .put("target", centre[0].toString() + "," + centre[1])
        }
        val point = requirePoint(service, params)
        if (!Actions.tapGesture(service, point[0], point[1])) {
            throw JsonRpc.RpcException(JsonRpc.ACTION_FAILED, "the tap did not take effect")
        }
        return JSONObject()
            .put("ok", true)
            .put("method", "gesture")
            .put("target", point[0].toString() + "," + point[1])
    }

    private fun uiLongPress(host: Host, session: Session, params: JSONObject): JSONObject {
        val service =
            AgentState.a11y ?: throw JsonRpc.RpcException(JsonRpc.AGENT_DISABLED, "not connected")
        requirePointOrNode(params)
        val duration = params.optInt("duration_ms", 600).coerceIn(1, 3000)
        val node = resolveNode(params)
        if (node != null) {
            if (Actions.longClickNode(node)) return JSONObject().put("ok", true)
            val centre = centreOf(node)
            if (!Actions.longPressGesture(service, centre[0], centre[1], duration)) {
                throw JsonRpc.RpcException(JsonRpc.ACTION_FAILED, "the long press did not take effect")
            }
            return JSONObject().put("ok", true)
        }
        val point = requirePoint(service, params)
        if (!Actions.longPressGesture(service, point[0], point[1], duration)) {
            throw JsonRpc.RpcException(JsonRpc.ACTION_FAILED, "the long press did not take effect")
        }
        return JSONObject().put("ok", true)
    }

    private fun requirePoint(
        service: com.bestrom.agent.a11y.AgentAccessibilityService,
        params: JSONObject,
    ): IntArray {
        val x = params.optInt("x", -1)
        val y = params.optInt("y", -1)
        val bounds = service.displayBounds()
        if (x < bounds.left || y < bounds.top || x > bounds.right || y > bounds.bottom) {
            throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "the point is outside the display")
        }
        return intArrayOf(x, y)
    }

    private fun uiSwipe(host: Host, session: Session, params: JSONObject): JSONObject {
        val service =
            AgentState.a11y ?: throw JsonRpc.RpcException(JsonRpc.AGENT_DISABLED, "not connected")
        val from = pointArray(params, "from")
        val to = pointArray(params, "to")
        val bounds = service.displayBounds()
        for (p in listOf(from, to)) {
            if (p[0] < bounds.left || p[1] < bounds.top || p[0] > bounds.right || p[1] > bounds.bottom) {
                throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "a point is outside the display")
            }
        }
        val duration = params.optInt("duration_ms", 300).coerceIn(1, 3000)
        if (!Actions.swipeGesture(service, from[0], from[1], to[0], to[1], duration)) {
            throw JsonRpc.RpcException(JsonRpc.ACTION_FAILED, "the swipe did not take effect")
        }
        return JSONObject().put("ok", true)
    }

    private fun pointArray(params: JSONObject, key: String): IntArray {
        val array =
            params.optJSONArray(key)
                ?: throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "$key must be [x, y]")
        if (array.length() != 2) {
            throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "$key must be [x, y]")
        }
        return intArrayOf(array.optInt(0), array.optInt(1))
    }

    private fun uiType(host: Host, session: Session, params: JSONObject): JSONObject {
        val service =
            AgentState.a11y ?: throw JsonRpc.RpcException(JsonRpc.AGENT_DISABLED, "not connected")
        val text = params.optString("text")
        if (text.length > MAX_TYPE_CHARS) {
            throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "text is too long")
        }
        val replace = params.optBoolean("replace", true)
        val node =
            resolveNode(params)
                ?: service.focusedEditable()
                ?: throw JsonRpc.RpcException(JsonRpc.NODE_NOT_FOUND, "no editable node is focused")
        if (node.isPassword) {
            throw JsonRpc.RpcException(JsonRpc.SECURE_WINDOW, "password fields are never typed into")
        }
        if (!Actions.setText(node, text, replace)) {
            throw JsonRpc.RpcException(JsonRpc.ACTION_FAILED, "the text was not set")
        }
        // The length only. The text itself is never echoed and never logged.
        return JSONObject().put("ok", true).put("chars", text.length)
    }

    private fun uiKey(host: Host, session: Session, params: JSONObject): JSONObject {
        val service =
            AgentState.a11y ?: throw JsonRpc.RpcException(JsonRpc.AGENT_DISABLED, "not connected")
        val action =
            Actions.globalActionFor(params.optString("name"))
                ?: throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "unknown key name")
        if (!Actions.performGlobal(service, action)) {
            throw JsonRpc.RpcException(JsonRpc.ACTION_FAILED, "the key action was refused")
        }
        return JSONObject().put("ok", true)
    }

    private fun uiScreenshot(host: Host, session: Session, params: JSONObject): JSONObject {
        val service =
            AgentState.a11y ?: throw JsonRpc.RpcException(JsonRpc.AGENT_DISABLED, "not connected")
        val encoding = params.optString("encoding", "base64")
        val outcome = Actions.screenshot(service, host.callbackExecutor)
        if (outcome.png == null) {
            throw JsonRpc.RpcException(
                JsonRpc.SCREENSHOT_UNAVAILABLE,
                "the platform refused the screenshot",
                JSONObject().put("reason", outcome.errorCode),
            )
        }
        val result =
            JSONObject()
                .put("width", outcome.width)
                .put("height", outcome.height)
                .put("bytes", outcome.png.size)
        if (encoding != "none") {
            result.put("png_base64", Base64.encodeToString(outcome.png, Base64.NO_WRAP))
        }
        return result
    }

    // ------------------------------------------------------------------ app.*

    private fun appLaunch(host: Host, session: Session, params: JSONObject): JSONObject {
        val given =
            listOf("package", "component", "intent_uri").count {
                params.has(it) && params.optString(it).isNotEmpty()
            }
        if (given != 1) {
            throw JsonRpc.RpcException(
                JsonRpc.INVALID_PARAMS,
                "give exactly one of package, component or intent_uri",
            )
        }
        val pm = host.context.packageManager
        val intent: Intent

        if (params.has("package") && params.optString("package").isNotEmpty()) {
            val pkg = params.optString("package")
            if (!host.functions.isInstalled(pkg)) {
                throw JsonRpc.RpcException(JsonRpc.NOT_INSTALLED, "no such package")
            }
            intent =
                pm.getLaunchIntentForPackage(pkg)
                    ?: throw JsonRpc.RpcException(JsonRpc.ACTION_FAILED, "the package has no launcher entry")
        } else if (params.has("component") && params.optString("component").isNotEmpty()) {
            val component =
                ComponentName.unflattenFromString(params.optString("component"))
                    ?: throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "component must be pkg/cls")
            if (!host.functions.isInstalled(component.packageName)) {
                throw JsonRpc.RpcException(JsonRpc.NOT_INSTALLED, "no such package")
            }
            intent = Intent(Intent.ACTION_MAIN).setComponent(component)
        } else {
            val parsed =
                try {
                    Intent.parseUri(params.optString("intent_uri"), Intent.URI_ANDROID_APP_SCHEME)
                } catch (e: Exception) {
                    throw JsonRpc.RpcException(JsonRpc.INVALID_PARAMS, "intent_uri does not parse")
                }
            val grants =
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            if (parsed.flags and grants != 0 || parsed.selector != null) {
                throw JsonRpc.RpcException(
                    JsonRpc.INVALID_PARAMS,
                    "intent_uri may not carry a uri grant or a selector",
                )
            }
            intent = parsed
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved =
            intent.resolveActivity(pm)
                ?: throw JsonRpc.RpcException(JsonRpc.ACTION_FAILED, "nothing resolves that intent")
        try {
            host.context.startActivity(intent)
        } catch (e: Exception) {
            throw JsonRpc.RpcException(JsonRpc.ACTION_FAILED, "the activity did not start")
        }
        return JSONObject().put("ok", true).put("component", resolved.flattenToString())
    }

    private fun appList(host: Host, session: Session, params: JSONObject): JSONObject {
        val pm = host.context.packageManager
        val launchableOnly = params.optBoolean("launchable_only", true)
        val apps = JSONArray()
        var count = 0

        if (launchableOnly) {
            val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            for (info in pm.queryIntentActivities(main, 0)) {
                val app = info.activityInfo?.applicationInfo ?: continue
                apps.put(describe(pm, app))
                count++
            }
        } else {
            for (info in pm.getInstalledPackages(0)) {
                val app = info.applicationInfo ?: continue
                apps.put(
                    describe(pm, app)
                        .put("version_name", info.versionName ?: JSONObject.NULL)
                        .put("version_code", info.longVersionCode)
                )
                count++
            }
        }
        return JSONObject().put("apps", apps).put("count", count)
    }

    private fun describe(pm: PackageManager, app: ApplicationInfo): JSONObject {
        val out =
            JSONObject()
                .put("package", app.packageName)
                .put("label", pm.getApplicationLabel(app).toString())
                .put("system", app.flags and ApplicationInfo.FLAG_SYSTEM != 0)
                .put("enabled", app.enabled)
        try {
            val info = pm.getPackageInfo(app.packageName, 0)
            out.put("version_name", info.versionName ?: JSONObject.NULL)
            out.put("version_code", info.longVersionCode)
        } catch (e: PackageManager.NameNotFoundException) {
            out.put("version_name", JSONObject.NULL)
            out.put("version_code", 0L)
        }
        return out
    }

    // ------------------------------------------------------------------ log.*

    private fun logList(host: Host, session: Session, params: JSONObject): JSONObject {
        val limit = params.optInt("limit", 100).coerceIn(1, AuditLog.CAPACITY)
        val since = if (params.has("since_utc")) params.optString("since_utc") else null
        val entries = JSONArray()
        for (e in host.audit.list(limit, since)) entries.put(e.toJson())
        return JSONObject()
            .put("entries", entries)
            .put("total", host.audit.size())
            .put("capacity", AuditLog.CAPACITY)
    }

    private fun logClear(host: Host, session: Session, params: JSONObject): JSONObject {
        val cleared = host.audit.clear()
        return JSONObject().put("cleared", cleared)
    }
}
