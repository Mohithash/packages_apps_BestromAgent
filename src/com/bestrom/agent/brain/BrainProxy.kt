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

package com.bestrom.agent.brain

import android.app.KeyguardManager
import android.content.Context
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import com.bestrom.agent.audit.AuditLog
import com.bestrom.agent.runner.InjectionFilter
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs [IBrainProxy] work on the caller's binder thread.
 *
 * Holds the sealed key inside this process: callers never see it. Rate-limits
 * by UID so a buggy launcher loop cannot burn the user's quota.
 */
object BrainProxy {

    private data class Window(var startMs: Long, var count: Int)

    private val windows = ConcurrentHashMap<Int, Window>()

    fun status(context: Context): Bundle {
        val config = BrainPrefs.read(context)
        val locked = deviceLocked(context)
        val keySet = !config.preset.keyRequired || ApiKeyStore.isSet(context)
        val configured = config.configured()
        val ready = configured && keySet && !locked
        return Bundle().apply {
            putBoolean(BrainProxyContract.KEY_OK, true)
            putBoolean(BrainProxyContract.KEY_CONFIGURED, configured)
            putBoolean(BrainProxyContract.KEY_KEY_SET, keySet)
            putBoolean(BrainProxyContract.KEY_LOCKED, locked)
            putBoolean(BrainProxyContract.KEY_READY, ready)
            putString(BrainProxyContract.KEY_MODEL, config.model)
            putString(BrainProxyContract.KEY_PRESET, config.preset.name)
        }
    }

    fun complete(context: Context, systemPrompt: String?, userMessage: String?): Bundle {
        val uid = Binder.getCallingUid()
        val caller =
            context.packageManager.getPackagesForUid(uid)?.joinToString(",") ?: "uid:$uid"
        val started = SystemClock.elapsedRealtime()

        if (!allow(uid)) {
            audit(context, caller, "rate_limited", started, uid)
            return fail("too many brain calls; try again in a minute")
        }

        if (deviceLocked(context)) {
            audit(context, caller, "locked", started, uid)
            return fail("unlock the phone first")
        }

        val user = userMessage?.trim().orEmpty()
        if (user.isEmpty()) {
            return fail("user message is empty")
        }
        if (user.length > BrainProxyContract.MAX_USER_CHARS) {
            return fail("user message is too long")
        }
        val system =
            InjectionFilter.sanitise(systemPrompt?.trim().orEmpty(), BrainProxyContract.MAX_SYSTEM_CHARS)
        val safeUser = InjectionFilter.sanitise(user, BrainProxyContract.MAX_USER_CHARS)

        val config = BrainPrefs.read(context)
        if (!config.configured()) {
            audit(context, caller, "not_configured", started, uid)
            return fail("set the brain in BestROM Agent first")
        }
        if (config.preset.keyRequired && !ApiKeyStore.isSet(context)) {
            audit(context, caller, "no_key", started, uid)
            return fail("set the brain API key in BestROM Agent first")
        }

        val client =
            OpenAiCompatClient(
                config = config,
                keySupplier = { ApiKeyStore.lookup(context) },
            )
        val outcome =
            client.complete(
                systemPrompt = system.ifEmpty { "You are a helpful assistant on the user's phone." },
                messages = listOf(ChatMessage(role = "user", content = safeUser)),
                tools = null,
                maxTokens = BrainProxyContract.MAX_TOKENS,
            )

        return when (outcome) {
            is OpenAiCompatClient.Outcome.Ok -> {
                val text = outcome.response.text?.trim().orEmpty()
                if (text.isEmpty()) {
                    audit(context, caller, "empty", started, uid)
                    fail("the model returned no text")
                } else {
                    audit(context, caller, "ok", started, uid)
                    Bundle().apply {
                        putBoolean(BrainProxyContract.KEY_OK, true)
                        putString(BrainProxyContract.KEY_TEXT, text)
                        putString(BrainProxyContract.KEY_CALLER, caller)
                        putString(BrainProxyContract.KEY_MODEL, outcome.response.model ?: config.model)
                    }
                }
            }
            is OpenAiCompatClient.Outcome.Fail -> {
                audit(context, caller, "fail", started, uid)
                fail(outcome.error.sentence)
            }
        }
    }

    private fun fail(message: String): Bundle =
        Bundle().apply {
            putBoolean(BrainProxyContract.KEY_OK, false)
            putString(BrainProxyContract.KEY_ERROR, message)
        }

    private fun deviceLocked(context: Context): Boolean {
        val kg = context.getSystemService(KeyguardManager::class.java) ?: return true
        return kg.isDeviceLocked || kg.isKeyguardLocked
    }

    private fun allow(uid: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        val window = windows.getOrPut(uid) { Window(now, 0) }
        synchronized(window) {
            if (now - window.startMs >= 60_000L) {
                window.startMs = now
                window.count = 0
            }
            if (window.count >= BrainProxyContract.MAX_CALLS_PER_MINUTE) {
                return false
            }
            window.count++
            return true
        }
    }

    private fun audit(
        context: Context,
        caller: String,
        result: String,
        started: Long,
        uid: Int,
    ) {
        try {
            AuditLog.get(context.filesDir)
                .append(
                    method = "brain.proxy",
                    target = caller,
                    result = result,
                    errorCode = null,
                    durationMs = SystemClock.elapsedRealtime() - started,
                    peerUid = uid,
                )
        } catch (_: Exception) {
            // Audit must not take the proxy down.
        }
    }
}
