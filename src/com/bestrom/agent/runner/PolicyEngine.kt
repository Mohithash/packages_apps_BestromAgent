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

/**
 * What may run, what has to be asked about, and what is refused outright.
 *
 * Every decision is made from the tool name and its arguments. What the model
 * says about a call is never an input, because an app that can put text on the
 * screen can talk to the model - so a classifier the model cannot address is
 * the only kind worth having.
 *
 * The engine is built once per task, from the goal and the installed app list,
 * and nothing later can widen it: the set of apps the user named is frozen at
 * that point, so no amount of text arriving from the device can make the agent
 * decide the user asked for a banking app.
 */
class PolicyEngine(
    private val goal: String,
    apps: List<AppFacts>,
    private val excluded: Set<String>,
    /** Snapshotted from settings when the task started, never re-read. */
    private val autonomous: Boolean,
    /**
     * The packages that can change a secure setting: Settings itself, and
     * whatever else on this device holds WRITE_SECURE_SETTINGS. Resolved once
     * by the caller, because this class knows nothing about a PackageManager.
     */
    settingsPackages: Set<String> = setOf(SETTINGS),
) {

    /** What the phone knows about an installed app, and nothing it claims itself. */
    class AppFacts(
        val packageName: String,
        val label: String,
        /** Requests com.android.vending.BILLING. */
        val billing: Boolean = false,
        /** Publishes a HOST_APDU_SERVICE - the tap-to-pay signal. */
        val hce: Boolean = false,
    )

    enum class Tier {
        READ,
        MUTATE,
        ALWAYS_CONFIRM,
        FORBIDDEN,
    }

    sealed class Decision {
        object Allow : Decision()

        /**
         * [what] and [target] are the two lines on the sheet. [sensitive] hides
         * the Allow all button and adds the payment line.
         */
        class NeedsConfirm(val what: String, val target: String, val sensitive: Boolean) :
            Decision()

        /** [reason] is shown in the transcript and told to the model verbatim. */
        class Refuse(val reason: String) : Decision()
    }

    companion object {
        /** The agent may not drive itself, whatever the package is called. */
        const val SELF = "bestrom.agent"

        const val SETTINGS = "com.android.settings"

        /**
         * Settings keys the agent never touches.
         *
         * Two of these are load-bearing beyond the obvious. "accessibility",
         * because the agent must not be able to reach its own kill switch; and
         * "adb" and "developer", because the Phase 1 bridge is reached over adb
         * and a task that could turn it on could open a door for somebody else.
         */
        val FORBIDDEN_KEY_PARTS: List<String> =
            listOf(
                "lock",
                "keyguard",
                "password",
                "passwd",
                "pin",
                "pattern",
                "credential",
                "biometric",
                "fingerprint",
                "face_unlock",
                "trust_agent",
                "smart_lock",
                "screen_pinning",
                "device_admin",
                "oem_unlock",
                "bootloader",
                "factory_reset",
                "erase",
                "wipe",
                "encrypt",
                "adb",
                "usb_debug",
                // "develop", not "developer": the platform's own key is
                // development_settings_enabled, which "developer" misses.
                "develop",
                "unknown_source",
                "install_unknown",
                "accessibility",
                "sepolicy",
                "selinux",
                "verified_boot",
            )

        /**
         * The same refusals as the keys above, spelled the way the interface
         * spells them.
         *
         * The key list only ever protected call_function. The same lock screen
         * is two taps away in Settings, and a tap was tier MUTATE.
         */
        val FORBIDDEN_UI_PARTS: List<String> =
            listOf(
                "screen lock",
                "fingerprint",
                "face unlock",
                "developer options",
                "usb debugging",
                "oem unlocking",
                "factory reset",
                "erase all data",
                "encryption",
                "accessibility",
                "device admin",
                "install unknown apps",
            )

        /** How long a one-word app label has to be before the goal can name it. */
        const val MIN_LABEL_CHARS = 6

        /** How far into a function's own parameters the key search goes. */
        const val MAX_PARAM_DEPTH = 6

        /**
         * Packages that handle money or credentials.
         *
         * A name list is not a taxonomy and it will miss a bank. It is the
         * backstop: the two tests that read the APK - billing and tap-to-pay -
         * are the load-bearing ones, and the Excluded apps list is the user's
         * own answer for anything all three miss.
         */
        val SENSITIVE_PREFIXES: List<String> =
            listOf(
                "com.google.android.apps.walletnfcrel",
                "com.google.android.apps.nbu.paisa.user",
                "com.paypal.",
                "com.squareup.cash",
                "com.venmo",
                "com.revolut.",
                "com.wise.",
                "com.phonepe",
                "net.one97.paytm",
                "in.org.npci.upiapp",
                "com.axis.",
                "com.sbi.",
                "com.icicibank.",
                "com.snapwork.hdfc",
                "com.msf.kbank.mobile",
                "com.dreamplug.androidapp",
                "com.coinbase.android",
                "com.binance.dev",
                "org.electrum.",
                "com.bitcoin.",
                "com.authy.authy",
                "com.google.android.apps.authenticator2",
                "com.azure.authenticator",
                "me.proton.pass.",
                "com.bitwarden.",
                "com.x8bit.bitwarden",
                "com.lastpass.lpandroid",
                "keepass2android.",
                "org.keepassdroid",
                "com.agilebits.onepassword",
                // A GMS surface is where an account or a payment prompt lives.
                "com.google.android.gms",
            )

        /** Lowercased, punctuation to spaces, split. Used for both sides of the test. */
        fun tokens(text: String): List<String> {
            val sb = StringBuilder(text.length)
            for (c in text.lowercase()) {
                sb.append(if (c.isLetterOrDigit()) c else ' ')
            }
            return sb.toString().split(' ').filter { it.isNotEmpty() }
        }

        /** True when [whole] contains [part] as a run of whole tokens. */
        fun containsSequence(whole: List<String>, part: List<String>): Boolean {
            if (part.isEmpty() || part.size > whole.size) return false
            outer@ for (i in 0..whole.size - part.size) {
                for (j in part.indices) {
                    if (whole[i + j] != part[j]) continue@outer
                }
                return true
            }
            return false
        }
    }

    private val byPackage: Map<String, AppFacts> = apps.associateBy { it.packageName }

    /** Settings, and anything else that can write a secure setting. */
    private val settingsSurfaces: Set<String> = settingsPackages + SETTINGS

    /** Computed once, from properties of the APK and then from the name list. */
    val sensitive: Set<String> =
        apps.filter { it.billing || it.hce || matchesPrefix(it.packageName) }
            .map { it.packageName }
            .toSet()

    /**
     * The apps the goal names, frozen at construction.
     *
     * Whole tokens only: "change the wallpaper" must not unlock Wallet.
     */
    val named: Set<String> =
        run {
            val goalTokens = tokens(goal)
            apps.filter { namesApp(goalTokens, it) }.map { it.packageName }.toSet()
        }

    /**
     * Whether the goal names this app.
     *
     * The package name is the good half of this test: nobody else chooses it.
     * A label is chosen by the app itself, so a one-word label short enough to
     * be an ordinary word - "Pay", "Phone", "Card" - would make most goals
     * name most apps. Two tokens, or one long one, is the floor.
     */
    private fun namesApp(goalTokens: List<String>, app: AppFacts): Boolean {
        if (containsSequence(goalTokens, tokens(app.packageName))) return true
        if (app.label.isEmpty()) return false
        val labelTokens = tokens(app.label)
        if (labelTokens.isEmpty()) return false
        if (labelTokens.size < 2 && labelTokens[0].length < MIN_LABEL_CHARS) return false
        return containsSequence(goalTokens, labelTokens)
    }

    private fun matchesPrefix(packageName: String): Boolean =
        SENSITIVE_PREFIXES.any { packageName == it || packageName.startsWith(it) }

    fun isSensitive(packageName: String?): Boolean =
        packageName != null && sensitive.contains(packageName)

    fun label(packageName: String): String =
        byPackage[packageName]?.label?.takeIf { it.isNotEmpty() } ?: packageName

    /**
     * The tier, and then what to do about it.
     *
     * [screen] is the last digest, which is where the foreground package and
     * the password fields come from: what is actually on screen, not what the
     * model said is on screen.
     */
    fun decide(
        call: ToolSchema.ToolCall,
        screen: ScreenDigest.Digest?,
        allowAllForThisTask: Boolean,
    ): Decision {
        val refusal = forbidden(call, screen)
        if (refusal != null) return Decision.Refuse(refusal)

        val tier = tier(call, screen)
        if (tier == Tier.READ) return Decision.Allow
        val what = describe(call, screen)
        if (tier == Tier.ALWAYS_CONFIRM) {
            return Decision.NeedsConfirm(what.first, what.second, true)
        }
        if (autonomous || allowAllForThisTask) return Decision.Allow
        return Decision.NeedsConfirm(what.first, what.second, false)
    }

    /** The tier alone, so a test can read it without the confirm wording. */
    fun tier(call: ToolSchema.ToolCall, screen: ScreenDigest.Digest?): Tier {
        if (forbidden(call, screen) != null) return Tier.FORBIDDEN
        if (!call.tool.mutating) return Tier.READ
        val target = targetPackage(call, screen)
        if (isSensitive(target)) return Tier.ALWAYS_CONFIRM
        // A screen that can change a secure setting asks every time. Neither
        // Autonomous nor Allow all covers it, because the rows the agent must
        // never touch are two taps from most of them.
        if (target != null && settingsSurfaces.contains(target)) return Tier.ALWAYS_CONFIRM
        return Tier.MUTATE
    }

    /**
     * The package an action lands on.
     *
     * For a screen action that is the window the last read_screen reported, not
     * anything the model asserted about it.
     */
    fun targetPackage(call: ToolSchema.ToolCall, screen: ScreenDigest.Digest?): String? =
        when (call.name) {
            ToolSchema.LAUNCH_APP, ToolSchema.CALL_FUNCTION -> call.args.optString("package")
            ToolSchema.TAP, ToolSchema.LONG_PRESS, ToolSchema.TYPE, ToolSchema.SWIPE ->
                screen?.windowPackage
            else -> null
        }?.takeIf { it.isNotEmpty() }

    /** null when nothing is refused; otherwise the sentence saying why. */
    private fun forbidden(call: ToolSchema.ToolCall, screen: ScreenDigest.Digest?): String? {
        val target = targetPackage(call, screen)

        // The dispatcher refuses an excluded package too. Refusing here as well
        // costs no rate-limit token and says something the model can act on.
        if (target != null && excluded.contains(target)) {
            return "$target is on the excluded apps list"
        }

        when (call.name) {
            ToolSchema.CALL_FUNCTION -> {
                val pkg = call.args.optString("package")
                if (pkg.contains(SELF)) return "the agent may not call its own functions"
                val settingsKey = forbiddenSettingsKey(call)
                if (settingsKey != null) {
                    return "$settingsKey is a setting the agent never changes"
                }
            }
            ToolSchema.LAUNCH_APP -> {
                val pkg = call.args.optString("package")
                if (pkg.contains(SELF)) return "the agent may not launch itself"
                if (isSensitive(pkg) && !named.contains(pkg)) {
                    return "${label(pkg)} handles payments or credentials and the task did " +
                        "not name it"
                }
            }
            ToolSchema.TYPE -> {
                if (call.args.has("node_id") &&
                    screen?.isPassword(call.args.optInt("node_id")) == true
                ) {
                    return "that is a password field"
                }
            }
        }

        // Every other tool lands on whatever window is in front, and the
        // agent's own screens are where Autonomous is turned on for good.
        if (target != null && target.contains(SELF)) {
            return "the agent may not drive its own screens"
        }

        val row = forbiddenRow(call, screen)
        if (row != null) return "\"$row\" is a setting the agent never changes"
        return null
    }

    /**
     * The refused settings, reached by tapping rather than by function.
     *
     * Only on a screen that can write a secure setting, and only for the three
     * tools that act on an element: a coordinate tap has no element to read,
     * and is covered by the confirm tier instead.
     */
    private fun forbiddenRow(
        call: ToolSchema.ToolCall,
        screen: ScreenDigest.Digest?,
    ): String? {
        if (call.name != ToolSchema.TAP &&
            call.name != ToolSchema.LONG_PRESS &&
            call.name != ToolSchema.TYPE
        ) {
            return null
        }
        if (screen == null || !settingsSurfaces.contains(screen.windowPackage)) return null
        if (!call.args.has("node_id")) return null
        val node = screen.node(call.args.optInt("node_id")) ?: return null
        val text = (node.label + " " + node.resId).lowercase()
        for (phrase in FORBIDDEN_UI_PARTS) {
            if (text.contains(phrase)) return phrase
        }
        // Whole words for the key spellings, so "Clock" is not "lock".
        val words = tokens(text)
        for (part in FORBIDDEN_KEY_PARTS) {
            val parts = tokens(part)
            if (parts.size > 1) {
                if (containsSequence(words, parts)) return part
            } else if (words.contains(part)) {
                return part
            }
        }
        return null
    }

    /**
     * The settings key a call would change, when that key is one of the
     * refused ones.
     *
     * Matched case-insensitively on a substring, because the same setting is
     * spelled differently by different functions and a miss here is a lock
     * screen the agent could turn off.
     */
    fun forbiddenSettingsKey(call: ToolSchema.ToolCall): String? {
        if (call.name != ToolSchema.CALL_FUNCTION) return null
        // No package pin and no "set" prefix: a vendor settings app, a
        // function called updateX, or a key one level down all changed the
        // same setting and none of them was looked at.
        val params = call.args.optJSONObject("params") ?: return null
        return forbiddenValue(params, 0)
    }

    /** Every string in a function's parameters, however deep it is nested. */
    private fun forbiddenValue(value: Any?, depth: Int): String? {
        if (depth > MAX_PARAM_DEPTH) return null
        when (value) {
            is String -> {
                val lower = value.lowercase()
                for (part in FORBIDDEN_KEY_PARTS) {
                    if (lower.contains(part)) return value
                }
            }
            is org.json.JSONObject -> {
                val names = value.keys()
                while (names.hasNext()) {
                    val name = names.next() as? String ?: continue
                    forbiddenValue(value.opt(name), depth + 1)?.let { return it }
                }
            }
            is org.json.JSONArray -> {
                for (i in 0 until value.length()) {
                    forbiddenValue(value.opt(i), depth + 1)?.let { return it }
                }
            }
        }
        return null
    }

    /** The two lines the confirm sheet shows: what, and to what. */
    fun describe(call: ToolSchema.ToolCall, screen: ScreenDigest.Digest?): Pair<String, String> {
        val target = targetPackage(call, screen)
        val node =
            if (call.args.has("node_id")) screen?.node(call.args.optInt("node_id")) else null
        val element = node?.label.orEmpty()

        val what =
            when (call.name) {
                ToolSchema.TAP -> if (element.isEmpty()) "Tap the screen" else "Tap $element"
                ToolSchema.LONG_PRESS ->
                    if (element.isEmpty()) "Press and hold the screen" else "Press and hold $element"
                ToolSchema.SWIPE -> "Swipe the screen"
                ToolSchema.TYPE ->
                    "Type " + call.args.optString("text").length + " characters" +
                        if (element.isEmpty()) "" else " into $element"
                ToolSchema.KEY -> "Press " + call.args.optString("name").replace('_', ' ')
                ToolSchema.LAUNCH_APP -> "Open " + label(call.args.optString("package"))
                ToolSchema.CALL_FUNCTION ->
                    "Call " + call.args.optString("function") + " in " +
                        label(call.args.optString("package"))
                else -> call.name
            }

        val where =
            when {
                target == null -> ""
                else -> label(target) + " (" + target + ")"
            }
        return Pair(what, where)
    }
}
