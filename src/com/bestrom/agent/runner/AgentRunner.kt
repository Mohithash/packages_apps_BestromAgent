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

import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.bestrom.agent.AgentState
import com.bestrom.agent.Denylist
import com.bestrom.agent.audit.AuditLog
import com.bestrom.agent.brain.ApiKeyStore
import com.bestrom.agent.brain.BrainConfig
import com.bestrom.agent.brain.BrainError
import com.bestrom.agent.brain.ChatMessage
import com.bestrom.agent.brain.ChatResponse
import com.bestrom.agent.brain.OpenAiCompatClient
import com.bestrom.agent.bridge.JsonRpc
import com.bestrom.agent.bridge.Methods
import org.json.JSONArray
import org.json.JSONObject

/**
 * The loop.
 *
 *     goal -> read screen -> model -> policy -> act -> look again -> ... -> done
 *
 * It runs on one thread inside the service that is already there, started when
 * a task starts and gone when it ends. There is no service for it, no job and
 * nothing at boot.
 *
 * Every action goes through Methods.dispatch, so the lock screen check, the
 * user-is-touching check, the excluded apps list, the password refusal, the
 * rate limiter, the confirm floor and the audit entry are the same code the
 * adb bridge runs, not a second copy of them.
 *
 * The loop's shape - compact, call, execute in order, feed back, stop on a
 * finish tool or a cap - and the trick of attaching a fresh screen to the
 * result of every acting tool are derived from PokeClaw (Apache-2.0).
 */
class AgentRunner(
    private val host: Methods.Host,
    private val task: Task,
    private val config: BrainConfig,
    /** Moves the ongoing notification between its three states. */
    private val notify: (Mode, String, Int) -> Unit,
    private val finished: () -> Unit,
) : Runnable {

    /** What the ongoing notification is saying at any moment. */
    enum class Mode {
        ACTING,
        WAITING,
        IDLE,
    }

    companion object {
        /** How long the interface is given to settle before it is read again. */
        const val SETTLE_MS = 500L

        /** -32006 is a race with a person, so it is the one error worth waiting out. */
        const val INTERACTING_RETRIES = 3
        const val INTERACTING_WAIT_MS = 1600L

        /** -32007 is the phone's own rate limiter, and it is worth one wait. */
        const val RATE_LIMIT_RETRIES = 1

        const val MAX_MALFORMED_IN_A_ROW = 3
        const val MAX_TEXT_ONLY_IN_A_ROW = 2

        /** Enough of the goal for a notification line. */
        const val GOAL_IN_NOTIFICATION = 60

        private const val HCE_ACTION = "android.nfc.cardemulation.action.HOST_APDU_SERVICE"
        private const val BILLING = "com.android.vending.BILLING"
        private const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    }

    private val context = host.context
    private val dispatch = ToolDispatch(host)

    /** This task's framings. Drawn once, named in the prompt, never reused. */
    private val boundary = InjectionFilter.boundary()
    private val transcript = Transcript()
    private val guard = StepGuard(task.stepCap, task.tokenCap)
    private var policy: PolicyEngine? = null
    private var systemPrompt = ""

    @Volatile
    private var client: OpenAiCompatClient? = null

    /** The thread this runner is on, so Stop can interrupt a sleep. */
    @Volatile
    private var thread: Thread? = null

    /** Filled in with the app list, because both come off the same scan. */
    private var settingsPackages: Set<String> = setOf(PolicyEngine.SETTINGS)

    private var malformedInARow = 0
    private var textOnlyInARow = 0
    private var lengthWarnings = 0

    /**
     * User / image turns that must not land between an assistant tool_calls
     * block and its tool replies (providers answer HTTP 400 for that).
     */
    private val afterTools = ArrayList<() -> Unit>(4)

    /** Drops the model call under the read, so Stop is not a sixty second wait. */
    fun cancel() {
        task.stop.set(true)
        client?.cancel()
        // A sheet nobody will now answer must not hold the thread for two
        // minutes before it notices the task is over.
        AgentState.confirm?.answer(PendingConfirm.Answer.DENY)
        AgentState.choice?.answer(null)
        // And a settle wait or a model backoff must not either.
        thread?.interrupt()
    }

    override fun run() {
        thread = Thread.currentThread()
        try {
            if (!prepare()) return
            loop()
        } catch (e: Exception) {
            terminate(Task.BRAIN_ERROR, "the task ended unexpectedly: " + e.javaClass.simpleName)
        } finally {
            thread = null
            finished()
        }
    }

    // ----------------------------------------------------------------- prepare

    private fun prepare(): Boolean {
        if (!AgentState.bridgeLive.get()) return refuse(Task.STOPPED, "Agent mode is off.")
        if (AgentState.a11y == null) {
            return refuse(Task.NO_BRAIN, "The accessibility service is not connected.")
        }
        if (deviceLocked()) return refuse(Task.LOCKED, "The phone is locked.")
        if (!config.configured()) {
            return refuse(Task.NO_BRAIN, "No brain is set up yet.")
        }
        if (config.preset.keyRequired && !ApiKeyStore.isSet(context)) {
            return refuse(Task.NO_BRAIN, "The brain has no API key.")
        }

        step(0, StepEvent.Kind.THINKING, "preparing")

        val apps = launchableApps()
        policy =
            PolicyEngine(
                task.goal,
                apps,
                Denylist.read(context).toSet(),
                task.autonomy,
                settingsPackages,
            )
        val functions = functionCatalogue()

        systemPrompt = SystemPrompt.build(deviceLine(), boundary)

        host.audit.append(
            "agent.start",
            // The goal is text the user typed and is never written down.
            "",
            "ok",
            null,
            0,
            AuditLog.UID_AGENT,
            0,
        )
        notify(Mode.ACTING, task.goal.take(GOAL_IN_NOTIFICATION), 0)

        val screen = dispatch.readScreen()
        // The app list and the function catalogue are strings other apps chose
        // for themselves, so they arrive as device output rather than as part
        // of the instructions.
        val opening = StringBuilder(task.goal)
        opening
            .append("\n\n")
            .append(
                boundary.envelope(
                    "app.list",
                    "",
                    SystemPrompt.appList(apps.map { it.packageName + "  " + it.label }),
                )
            )
            .append("\n\n")
            .append(
                boundary.envelope(
                    "functions.list",
                    "",
                    SystemPrompt.functionList(
                        FunctionCatalog.lines(functions ?: JSONObject())
                    ),
                )
            )
        transcript.addUser(opening.toString())
        // Its own entry, so it ages like every other screen instead of riding
        // in the first user message for the whole task.
        if (screen is ToolDispatch.Outcome.Ok) {
            val digest = dispatch.digest
            if (digest != null) {
                transcript.addScreen(
                    boundary.envelope(
                        ToolSchema.READ_SCREEN,
                        digest.windowPackage,
                        digest.text,
                    )
                )
            }
        }
        task.state = TaskState.THINKING
        return true
    }

    private fun deviceLocked(): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java) ?: return false
        return keyguard.isDeviceLocked || keyguard.isKeyguardLocked
    }

    private fun deviceLine(): String {
        val metrics = AgentState.a11y?.displayBounds()
        return SystemPrompt.deviceLine(
            Build.MODEL,
            Build.VERSION.RELEASE,
            Build.VERSION.SDK_INT,
            metrics?.width() ?: 0,
            metrics?.height() ?: 0,
        )
    }

    /**
     * The launchable apps, with the two facts the policy engine reads off the
     * APK rather than off a name: does it ask for billing, and does it publish
     * a tap-to-pay service.
     */
    private fun launchableApps(): List<PolicyEngine.AppFacts> {
        val pm = context.packageManager
        val result = dispatch.call("app.list", JSONObject().put("launchable_only", true))
        val list =
            (result as? ToolDispatch.Outcome.Ok)?.result?.optJSONArray("apps")
                ?: return emptyList()

        val hce = HashSet<String>()
        try {
            for (info in pm.queryIntentServices(Intent(HCE_ACTION), PackageManager.GET_META_DATA)) {
                info.serviceInfo?.packageName?.let { hce.add(it) }
            }
        } catch (e: Exception) {
            // A device with no NFC answers nothing; the name list still applies.
        }

        val billing = HashSet<String>()
        val secure = HashSet<String>()
        secure.add(PolicyEngine.SETTINGS)
        try {
            for (info in pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)) {
                val requested = info.requestedPermissions ?: continue
                if (requested.contains(BILLING)) billing.add(info.packageName)
                // Anything that can write a secure setting is a settings
                // surface, whatever it is called.
                if (requested.contains(WRITE_SECURE_SETTINGS) &&
                    pm.checkPermission(WRITE_SECURE_SETTINGS, info.packageName) ==
                        PackageManager.PERMISSION_GRANTED
                ) {
                    secure.add(info.packageName)
                }
            }
        } catch (e: Exception) {
            // Same: the classification degrades to the name list.
        }
        settingsPackages = secure

        val out = ArrayList<PolicyEngine.AppFacts>(list.length())
        val seen = HashSet<String>()
        for (i in 0 until list.length()) {
            val app = list.optJSONObject(i) ?: continue
            val pkg = app.optString("package")
            if (pkg.isEmpty() || !seen.add(pkg)) continue
            out.add(
                PolicyEngine.AppFacts(
                    pkg,
                    InjectionFilter.oneLine(app.optString("label"), 64),
                    billing.contains(pkg),
                    hce.contains(pkg),
                )
            )
        }
        out.sortBy { it.packageName }
        return out
    }

    private fun functionCatalogue(): JSONObject? {
        val result =
            dispatch.call("functions.list", JSONObject().put("include_schema", true))
        return (result as? ToolDispatch.Outcome.Ok)?.result
    }

    // -------------------------------------------------------------------- loop

    private fun loop() {
        while (true) {
            if (task.stopped()) return terminate(Task.STOPPED, "")
            if (!guard.nextStep()) return terminate(Task.STEP_CAP, "")
            task.step = guard.steps
            task.state = TaskState.THINKING

            if (guard.steps % SystemPrompt.REASSERT_EVERY == 0) {
                transcript.addUser(SystemPrompt.reassertion(boundary.control, task.goal))
            }

            step(task.step, StepEvent.Kind.THINKING, "thinking")
            notify(Mode.ACTING, task.goal.take(GOAL_IN_NOTIFICATION), task.step)

            val response = think() ?: return
            if (task.stopped()) return terminate(Task.STOPPED, "")

            transcript.addAssistant(response.text, response.toolCalls.map {
                ChatMessage.Call(it.id, it.name, it.argumentsJson)
            })

            if (response.toolCalls.isEmpty()) {
                if (finishText(response)) return
                continue
            }
            textOnlyInARow = 0

            task.state = TaskState.ACTING
            for (call in response.toolCalls) {
                if (task.stopped()) {
                    flushAfterTools()
                    return terminate(Task.STOPPED, "")
                }
                if (act(call)) {
                    flushAfterTools()
                    return
                }
            }
            flushAfterTools()

            when (guard.tokenVerdict()) {
                StepGuard.Signal.TERMINATE -> return terminate(Task.TOKEN_CAP, "")
                StepGuard.Signal.HINT -> transcript.addUser(guard.softLimitMessage(boundary.control))
                else -> {}
            }
        }
    }

    /** One model call, with the errors it can end a task on. */
    private fun think(): ChatResponse? {
        // The stop predicate closes the window between the constructor and
        // this assignment, where cancel() has nothing to cancel yet.
        val brain = OpenAiCompatClient(config, { ApiKeyStore.lookup(context) }, { task.stopped() })
        client = brain
        val started = System.currentTimeMillis()
        val outcome =
            brain.complete(
                systemPrompt,
                transcript.forSend(),
                ToolSchema.tools(task.vision),
            )
        client = null

        when (outcome) {
            is OpenAiCompatClient.Outcome.Ok -> {
                // The model id, and nothing about what was said to it.
                host.audit.append(
                    "brain.call",
                    config.model,
                    "ok",
                    null,
                    System.currentTimeMillis() - started,
                    AuditLog.UID_AGENT,
                    0,
                )
                guard.addUsage(outcome.response.usage, outcome.sentChars, outcome.receivedChars)
                if (outcome.response.finishReason == ChatResponse.FINISH_LENGTH) {
                    lengthWarnings++
                    if (lengthWarnings >= 2) {
                        terminate(Task.BRAIN_ERROR, "the model kept running out of room")
                        return null
                    }
                    transcript.addUser(
                        boundary.say("Your answer was cut off. Be brief and act rather than explain.")
                    )
                }
                return outcome.response
            }
            is OpenAiCompatClient.Outcome.Fail -> {
                host.audit.append(
                    "brain.call",
                    config.model,
                    "error",
                    null,
                    outcome.durationMs,
                    AuditLog.UID_AGENT,
                    0,
                )
                if (outcome.error is BrainError.Cancelled) terminate(Task.STOPPED, "")
                else terminate(Task.BRAIN_ERROR, outcome.error.sentence)
                return null
            }
        }
    }

    /** The model answered in prose. Nudge once, then take the prose as the answer. */
    private fun finishText(response: ChatResponse): Boolean {
        textOnlyInARow++
        val text = response.text.orEmpty().trim()
        if (textOnlyInARow < MAX_TEXT_ONLY_IN_A_ROW) {
            transcript.addUser(
                boundary.say("Call done(answer=...) to finish, or keep going with a tool.")
            )
            return false
        }
        task.answer = text
        terminate(Task.DONE, text)
        return true
    }

    /**
     * One tool call, from validation to the fresh screen after it.
     *
     * True when the task ended inside this call.
     */
    private fun act(raw: ChatResponse.ToolCall): Boolean {
        val validation = ToolSchema.validate(raw.id, raw.name, raw.argumentsJson, task.vision)
        if (validation is ToolSchema.Validation.Invalid) {
            malformedInARow++
            step(task.step, StepEvent.Kind.ERROR, "invalid tool call - " + validation.reason)
            transcript.addToolResult(raw.id, "invalid tool call: " + validation.reason, false)
            if (malformedInARow >= MAX_MALFORMED_IN_A_ROW) {
                terminate(Task.MALFORMED, "")
                return true
            }
            return false
        }
        malformedInARow = 0
        val call = (validation as ToolSchema.Validation.Valid).call

        if (call.name == ToolSchema.DONE) {
            val answer = call.args.optString("answer")
            task.answer = answer
            // Answer the tool_call so a later forSend() is still well-formed
            // if the model returned DONE alongside other tools.
            transcript.addToolResult(call.id, "done", false)
            terminate(Task.DONE, answer)
            return true
        }

        if (call.name == ToolSchema.DESCRIBE_ALERT_OPTIONS) {
            val brief = com.bestrom.agent.alert.AlertDelivery.capabilityBrief()
            transcript.addToolResult(call.id, brief, false)
            step(task.step, StepEvent.Kind.RESULT, "alert options")
            return false
        }

        if (call.name == ToolSchema.OFFER_CHOICES) {
            val prompt = call.args.optString("prompt").trim()
            val options = parseChoiceOptions(call.args.opt("options"))
            if (prompt.isEmpty() || options.isEmpty()) {
                transcript.addToolResult(call.id, "offer_choices needs prompt and options", false)
                return false
            }
            val picked = askChoices(prompt, options)
            if (picked == null) {
                transcript.addToolResult(call.id, "user cancelled or timed out", false)
            } else {
                transcript.addToolResult(
                    call.id,
                    "user chose: $picked\n" +
                        com.bestrom.agent.alert.AlertDelivery.capabilityBrief(),
                    false,
                )
            }
            step(task.step, StepEvent.Kind.RESULT, "offer_choices")
            return false
        }

        if (call.name == ToolSchema.LAUNCH_APP) {
            // Chat goes to PiP before the target covers it.
            AgentState.keepVisible.set(true)
        }

        val engine = policy
        val screen = dispatch.digest
        // No classifier, no action: the default on a missing policy engine is
        // a refusal, not a free pass.
        val decision =
            engine?.decide(call, screen, task.allowAllForThisTask)
                ?: PolicyEngine.Decision.Refuse("no policy engine")

        when (decision) {
            is PolicyEngine.Decision.Refuse -> {
                step(task.step, StepEvent.Kind.REFUSED, "refused by policy - " + decision.reason)
                host.audit.append(
                    "agent.refused",
                    call.name,
                    "error",
                    null,
                    0,
                    AuditLog.UID_AGENT,
                    0,
                )
                transcript.addToolResult(
                    call.id,
                    "refused by policy: " + decision.reason,
                    false,
                )
                return false
            }
            is PolicyEngine.Decision.NeedsConfirm -> {
                val answer = ask(decision)
                if (answer == PendingConfirm.Answer.DENY) {
                    if (task.stopped()) {
                        terminate(Task.STOPPED, "")
                        return true
                    }
                    step(task.step, StepEvent.Kind.CONFIRM, "you denied " + decision.what)
                    transcript.addToolResult(call.id, "the user denied this action", false)
                    return false
                }
                if (answer == PendingConfirm.Answer.ALLOW_ALL) task.allowAllForThisTask = true
            }
            is PolicyEngine.Decision.Allow -> {}
        }

        step(task.step, StepEvent.Kind.ACTION, describe(call))

        // Counted before the call, not after it: a call that keeps failing is
        // exactly the loop worth catching.
        val repeated = guard.noteCall(signature(call))
        if (repeated == StepGuard.Signal.TERMINATE) {
            terminate(Task.STUCK, "")
            return true
        }
        if (execute(call)) return true
        if (repeated != StepGuard.Signal.NONE) {
            deferUser(guard.message(repeated, boundary.control))
        }
        return false
    }

    private fun deferUser(text: String) {
        afterTools.add { transcript.addUser(text) }
    }

    private fun deferImage(caption: String, pngBase64: String) {
        afterTools.add { transcript.addImage(caption, pngBase64) }
    }

    private fun flushAfterTools() {
        for (action in afterTools) action()
        afterTools.clear()
    }

    private fun ask(decision: PolicyEngine.Decision.NeedsConfirm): PendingConfirm.Answer {
        val pending = PendingConfirm(decision.what, decision.target, decision.sensitive)
        task.state = TaskState.WAITING
        AgentState.confirm = pending
        step(task.step, StepEvent.Kind.CONFIRM, "waiting for you - " + decision.what)
        notify(Mode.WAITING, "", task.step)
        val answer = pending.await()
        AgentState.confirm = null
        task.state = TaskState.ACTING
        notify(Mode.ACTING, task.goal.take(GOAL_IN_NOTIFICATION), task.step)
        return answer
    }

    private fun askChoices(prompt: String, options: List<String>): String? {
        val pending = PendingChoice(prompt, options)
        task.state = TaskState.WAITING
        AgentState.choice = pending
        step(task.step, StepEvent.Kind.CONFIRM, "waiting for a choice - $prompt")
        notify(Mode.WAITING, "", task.step)
        val answer = pending.await()
        AgentState.choice = null
        task.state = TaskState.ACTING
        notify(Mode.ACTING, task.goal.take(GOAL_IN_NOTIFICATION), task.step)
        return answer
    }

    private fun parseChoiceOptions(raw: Any?): List<String> {
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
            } ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until arr.length().coerceAtMost(6)) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty() && s.length <= 40) out.add(s)
        }
        return out
    }

    /** Runs the call, retrying only the one error that is a race with a person. */
    private fun execute(call: ToolSchema.ToolCall): Boolean {
        // Two counters, not one: a rate-limit wait used to spend one of the
        // three retries a race with the user is allowed, and the other way
        // round a single -32006 retry turned the rate-limit retry off.
        var interacting = 0
        var rateLimited = 0
        while (true) {
            if (task.stopped()) {
                terminate(Task.STOPPED, "")
                return true
            }
            val outcome = dispatch.run(call, call.tool.mutating)
            when (outcome) {
                is ToolDispatch.Outcome.Ok -> return succeeded(call, outcome.result)
                is ToolDispatch.Outcome.Failed -> {
                    if (outcome.code == JsonRpc.USER_INTERACTING &&
                        interacting < INTERACTING_RETRIES
                    ) {
                        interacting++
                        sleep(INTERACTING_WAIT_MS)
                        continue
                    }
                    if (outcome.code == JsonRpc.RATE_LIMITED &&
                        rateLimited < RATE_LIMIT_RETRIES
                    ) {
                        rateLimited++
                        sleep(outcome.data?.optLong("retry_after_ms", 200L) ?: 200L)
                        continue
                    }
                    if (outcome.code == JsonRpc.STALE_TREE) return staleTree(call)
                    if (outcome.code == JsonRpc.DEVICE_LOCKED) {
                        terminate(Task.LOCKED, "")
                        return true
                    }
                    if (outcome.code == JsonRpc.AGENT_DISABLED) {
                        terminate(Task.STOPPED, "")
                        return true
                    }
                    step(task.step, StepEvent.Kind.ERROR, outcome.text)
                    transcript.addToolResult(call.id, outcome.text, false)
                    val signal = guard.noteError(outcome.text)
                    if (signal == StepGuard.Signal.TERMINATE) {
                        terminate(Task.STUCK, "")
                        return true
                    }
                    if (signal != StepGuard.Signal.NONE) {
                        deferUser(guard.message(signal, boundary.control))
                    }
                    return false
                }
            }
        }
    }

    /**
     * The screen moved out from under a call.
     *
     * The cached tree id is gone by now, so the screen is read again here and
     * the fresh digest goes back with the error. The call is never re-issued:
     * the model decides what to do with the screen it is now looking at.
     */
    private fun staleTree(call: ToolSchema.ToolCall): Boolean {
        step(task.step, StepEvent.Kind.ERROR, "the screen changed; it was read again")
        val body =
            StringBuilder("the screen changed before this could run, so it was read again")
        val read = dispatch.readScreen()
        val digest = dispatch.digest
        if (read is ToolDispatch.Outcome.Ok && digest != null) {
            body.append("\n\n").append(digest.text)
            transcript.addToolResult(
                call.id,
                boundary.envelope(ToolSchema.READ_SCREEN, digest.windowPackage, body.toString()),
                true,
            )
            if (noteScreen(digest)) return true
            return false
        }
        transcript.addToolResult(
            call.id,
            boundary.envelope(call.name, "", body.toString()),
            false,
        )
        val signal = guard.noteError("stale tree")
        if (signal == StepGuard.Signal.TERMINATE) {
            terminate(Task.STUCK, "")
            return true
        }
        if (signal != StepGuard.Signal.NONE) {
            deferUser(guard.message(signal, boundary.control))
        }
        return false
    }

    /**
     * The result, plus a fresh look at the screen when the action moved it.
     *
     * Attaching the new screen here rather than making the model call
     * read_screen halves the number of model calls a UI task costs.
     */
    private fun succeeded(call: ToolSchema.ToolCall, result: JSONObject): Boolean {
        val previous = dispatch.digest
        val body = StringBuilder()

        if (call.name == ToolSchema.READ_SCREEN) {
            val digest = dispatch.digest
            transcript.addToolResult(
                call.id,
                boundary.envelope(
                    call.name,
                    digest?.windowPackage.orEmpty(),
                    digest?.text.orEmpty(),
                ),
                true,
            )
            step(
                task.step,
                StepEvent.Kind.RESULT,
                "read screen - " + (digest?.windowPackage ?: "?") + ", " +
                    (digest?.shown ?: 0) + " elements",
            )
            if (digest != null && noteScreen(digest)) return true
            return false
        }

        if (call.name == ToolSchema.SCREENSHOT) {
            val png = result.optString("png_base64")
            transcript.addToolResult(call.id, "screenshot taken", false)
            if (png.isNotEmpty()) deferImage("The screenshot.", png)
            step(task.step, StepEvent.Kind.RESULT, "screenshot")
            return false
        }

        if (call.name == ToolSchema.LIST_FUNCTIONS) {
            val lines = FunctionCatalog.text(result)
            transcript.addToolResult(
                call.id,
                boundary.envelope(call.name, "", lines),
                false,
            )
            step(task.step, StepEvent.Kind.RESULT, "listed app functions")
            return false
        }

        if (call.name == ToolSchema.LIST_APPS) {
            val apps = result.optJSONArray("apps") ?: JSONArray()
            val sb = StringBuilder()
            for (i in 0 until apps.length()) {
                val app = apps.optJSONObject(i) ?: continue
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(app.optString("package"))
                    .append(" - ")
                    .append(app.optString("label"))
            }
            transcript.addToolResult(
                call.id,
                boundary.envelope(call.name, "", sb.toString().ifEmpty { "none" }),
                false,
            )
            step(task.step, StepEvent.Kind.RESULT, "listed apps")
            return false
        }

        if (call.name == ToolSchema.WAIT) {
            val waited = "waited " + result.optInt("waited_ms") + " ms"
            step(task.step, StepEvent.Kind.RESULT, "waited")
            sleep(SETTLE_MS)
            val read = dispatch.readScreen()
            val digest = dispatch.digest
            if (read is ToolDispatch.Outcome.Ok && digest != null) {
                // Same tool_call_id — a second fake id (id:screen) is a 400.
                transcript.addToolResult(
                    call.id,
                    waited +
                        "\n\n" +
                        boundary.envelope(
                            ToolSchema.READ_SCREEN,
                            digest.windowPackage,
                            digest.text,
                        ),
                    true,
                )
                noteScreen(digest)
            } else {
                transcript.addToolResult(call.id, waited, false)
            }
            return false
        }

        if (call.name == ToolSchema.SCHEDULE_REMINDER ||
            call.name == ToolSchema.SCHEDULE_TASK ||
            call.name == ToolSchema.LIST_REMINDERS ||
            call.name == ToolSchema.CANCEL_REMINDER
        ) {
            val line =
                when (call.name) {
                    ToolSchema.LIST_REMINDERS ->
                        "reminders: " + result.optInt("count") + "\n" +
                            result.optJSONArray("reminders").toString()
                    ToolSchema.CANCEL_REMINDER ->
                        "cancelled " + result.optString("id")
                    else ->
                        "scheduled " +
                            result.optString("kind") +
                            " id=" +
                            result.optString("id") +
                            " at " +
                            result.optString("fire_at_utc")
                }
            transcript.addToolResult(
                call.id,
                boundary.envelope(call.name, "", line),
                false,
            )
            step(task.step, StepEvent.Kind.RESULT, call.name)
            return false
        }

        if (call.name == ToolSchema.SCHEDULE_EVENT) {
            transcript.addToolResult(
                call.id,
                boundary.envelope(
                    call.name,
                    "",
                    "event id=" + result.optLong("event_id") + " uri=" + result.optString("uri"),
                ),
                false,
            )
            step(task.step, StepEvent.Kind.RESULT, call.name)
            return false
        }

        if (call.name == ToolSchema.LOG_TAIL ||
            call.name == ToolSchema.LOG_GREP ||
            call.name == ToolSchema.CRASH_SCAN ||
            call.name == ToolSchema.MEASURE_IDLE_DRAIN ||
            call.name == ToolSchema.BATTERYSTATS_SNIPPET
        ) {
            val line =
                when (call.name) {
                    ToolSchema.CRASH_SCAN ->
                        "exits=" + result.optInt("count") + "\n" +
                            result.optJSONArray("exits").toString()
                    ToolSchema.MEASURE_IDLE_DRAIN ->
                        "level=" +
                            result.optInt("level_pct") +
                            "% status=" +
                            result.optString("status") +
                            " current_ua=" +
                            result.optLong("current_now_ua") +
                            if (result.has("estimated_avg_drain_ma")) {
                                " avg_ma=" + result.optDouble("estimated_avg_drain_ma")
                            } else if (result.optBoolean("first_sample")) {
                                " (first sample)"
                            } else {
                                ""
                            }
                    else -> {
                        val matched =
                            if (result.has("matched")) " matched=" + result.optInt("matched")
                            else ""
                        "chars=" + result.optInt("chars") + matched + "\n" +
                            result.optString("text")
                    }
                }
            transcript.addToolResult(
                call.id,
                boundary.envelope(call.name, "", line),
                false,
            )
            step(task.step, StepEvent.Kind.RESULT, call.name)
            return false
        }

        if (call.name == ToolSchema.START_JOB ||
            call.name == ToolSchema.STOP_JOB ||
            call.name == ToolSchema.LIST_JOBS ||
            call.name == ToolSchema.SAVE_MACRO ||
            call.name == ToolSchema.DELETE_MACRO ||
            call.name == ToolSchema.LIST_MACROS ||
            call.name == ToolSchema.RUN_MACRO ||
            call.name == ToolSchema.LIST_PLAYBOOKS ||
            call.name == ToolSchema.RUN_PLAYBOOK ||
            call.name == ToolSchema.CREATE_MINIAPP ||
            call.name == ToolSchema.LIST_MINIAPPS ||
            call.name == ToolSchema.DELETE_MINIAPP ||
            call.name == ToolSchema.OPEN_MINIAPP
        ) {
            val line =
                when (call.name) {
                    ToolSchema.LIST_JOBS ->
                        "jobs=" + result.optInt("count") + "\n" +
                            result.optJSONArray("jobs").toString()
                    ToolSchema.LIST_MACROS ->
                        "macros=" + result.optInt("count") + "\n" +
                            result.optJSONArray("macros").toString()
                    ToolSchema.LIST_PLAYBOOKS ->
                        "playbooks=" + result.optInt("count") + "\n" +
                            result.optJSONArray("playbooks").toString()
                    ToolSchema.LIST_MINIAPPS ->
                        "miniapps=" + result.optInt("count") + "\n" +
                            result.optJSONArray("miniapps").toString()
                    ToolSchema.RUN_MACRO ->
                        "macro " + result.optString("id") + "\n" + result.optString("result")
                    ToolSchema.RUN_PLAYBOOK ->
                        "playbook " +
                            result.optString("id") +
                            " started=" +
                            result.optBoolean("started") +
                            "\n" +
                            result.optString("goal") +
                            "\n" +
                            result.optString("note")
                    ToolSchema.START_JOB ->
                        "job " +
                            result.optString("kind") +
                            " id=" +
                            result.optString("id") +
                            " next=" +
                            result.optString("next_fire_utc")
                    ToolSchema.SAVE_MACRO ->
                        "macro " +
                            result.optString("name") +
                            " id=" +
                            result.optString("id") +
                            " trigger=" +
                            result.optString("trigger")
                    ToolSchema.CREATE_MINIAPP ->
                        "miniapp " +
                            result.optString("name") +
                            " id=" +
                            result.optString("id") +
                            " kind=" +
                            result.optString("kind") +
                            "\n" +
                            result.optString("note")
                    else ->
                        call.name + " id=" + result.optString("id") + " ok"
                }
            transcript.addToolResult(
                call.id,
                boundary.envelope(call.name, "", line),
                false,
            )
            step(task.step, StepEvent.Kind.RESULT, call.name)
            return false
        }

        body.append(summary(call, result))
        step(task.step, StepEvent.Kind.RESULT, "ok")

        if (dispatch.changesTheScreen(call.name)) {
            sleep(SETTLE_MS)
            val read = dispatch.readScreen()
            val digest = dispatch.digest
            if (read is ToolDispatch.Outcome.Ok && digest != null) {
                val diff = digest.diff(previous)
                // A screen that moved is progress, whatever the call looked
                // like. Without this, three taps on the Next button of three
                // different pages read as one call repeated three times.
                if (diff != ScreenDigest.NO_CHANGE) guard.noteProgress()
                body.append("\n\n").append(diff).append('\n').append(digest.text)
                transcript.addToolResult(
                    call.id,
                    boundary.envelope(call.name, digest.windowPackage, body.toString()),
                    true,
                )
                if (noteScreen(digest)) return true
                return false
            }
        }
        transcript.addToolResult(
            call.id,
            boundary.envelope(call.name, "", body.toString()),
            false,
        )
        return false
    }

    /** True when the screen has not moved often enough to end the task. */
    private fun noteScreen(digest: ScreenDigest.Digest): Boolean {
        val signal = guard.noteScreen(digest.hash())
        if (signal == StepGuard.Signal.TERMINATE) {
            terminate(Task.STUCK, "")
            return true
        }
        if (signal != StepGuard.Signal.NONE) deferUser(guard.message(signal, boundary.control))
        return false
    }

    /** What a non-screen result says, without repeating anything private. */
    private fun summary(call: ToolSchema.ToolCall, result: JSONObject): String =
        when (call.name) {
            ToolSchema.TYPE -> "typed " + result.optInt("chars") + " characters"
            ToolSchema.LAUNCH_APP -> "opened " + result.optString("component")
            ToolSchema.CALL_FUNCTION ->
                "the function answered: " +
                    InjectionFilter.sanitise(
                        (result.optJSONObject("result") ?: JSONObject()).toString(),
                        1024,
                    )
            else -> "ok"
        }

    /**
     * One line for the transcript the user reads.
     *
     * describe() already starts with a verb, so the tool name in front of it
     * read as "tap  Tap Battery saver". The resource id goes on the end, which
     * is what tells two rows with the same label apart.
     */
    private fun describe(call: ToolSchema.ToolCall): String {
        val engine = policy ?: return call.name
        val described = engine.describe(call, dispatch.digest)
        val node =
            if (call.args.has("node_id")) dispatch.digest?.node(call.args.optInt("node_id"))
            else null
        val resId = node?.resId.orEmpty()
        return if (resId.isEmpty()) described.first else described.first + "  #" + resId
    }

    /** The identity a repeated call is recognised by. */
    private fun signature(call: ToolSchema.ToolCall): String =
        call.name + " " + call.args.toString()

    // --------------------------------------------------------------- terminal

    private fun refuse(reason: String, sentence: String): Boolean {
        task.answer = sentence
        terminate(reason, sentence)
        return false
    }

    private fun terminate(reason: String, detail: String) {
        if (task.finished()) return
        AgentState.keepVisible.set(false)
        afterTools.clear()
        task.reason = reason
        if (detail.isNotEmpty() && task.answer.isEmpty()) task.answer = detail
        task.state = TaskState.FINISHED
        host.audit.append("agent.end", reason, "ok", null,
            System.currentTimeMillis() - task.startedMs, AuditLog.UID_AGENT, 0)
        // The step number, not a dash: the ending is the last line of a run
        // and the user wants to see where in the run it happened. And when no
        // endpoint sent a usage block, the token count was a guess; say so
        // rather than showing a number that looks measured.
        step(
            task.step,
            StepEvent.Kind.DONE,
            task.ending() + if (guard.estimated) "  (tokens estimated)" else "",
        )
        notify(Mode.IDLE, "", 0)
    }

    private fun step(index: Int, kind: StepEvent.Kind, text: String) {
        AgentState.addStep(StepEvent(index, kind, text))
    }

    private fun sleep(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
