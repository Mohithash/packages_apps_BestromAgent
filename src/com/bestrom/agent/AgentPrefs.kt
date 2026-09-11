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

package com.bestrom.agent

import android.content.Context
import com.bestrom.agent.alert.AlertDelivery
import com.bestrom.agent.brain.ApiKeyStore
import com.bestrom.agent.ui.ChatHistoryStore
import com.bestrom.agent.ui.ChatStore
import com.bestrom.agent.jobs.JobStore
import com.bestrom.agent.macro.MacroStore
import com.bestrom.agent.schedule.ReminderStore
import java.io.File

/**
 * Agent mode preferences that are not the brain endpoint.
 *
 * [remoteAdb] is off by default: chat and on-device actions use Accessibility
 * only. Turning it on opens the localabstract:bestrom_agent socket for a
 * computer over adb — that path is optional.
 */
object AgentPrefs {

    private const val PREFS = "agent"
    private const val KEY_REMOTE_ADB = "remote_adb"
    private const val KEY_ALERT_DELIVERY = "alert_delivery"
    private const val KEY_SECURITY_WATCH = "security_watch"
    private const val KEY_WATER_ENABLED = "miniapp_water"
    private const val KEY_WEIGHT_ENABLED = "miniapp_weight"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** When true, AgentBridgeService binds the adb JSON-RPC socket. */
    fun remoteAdb(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REMOTE_ADB, false)

    fun setRemoteAdb(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_REMOTE_ADB, enabled).apply()
    }

    fun alertDelivery(context: Context): String =
        AlertDelivery.normalize(prefs(context).getString(KEY_ALERT_DELIVERY, null))

    fun setAlertDelivery(context: Context, delivery: String) {
        prefs(context)
            .edit()
            .putString(KEY_ALERT_DELIVERY, AlertDelivery.normalize(delivery))
            .apply()
    }

    fun securityWatch(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SECURITY_WATCH, false)

    fun setSecurityWatch(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_SECURITY_WATCH, enabled).apply()
    }

    fun waterEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WATER_ENABLED, true)

    fun setWaterEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WATER_ENABLED, enabled).apply()
    }

    fun weightEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WEIGHT_ENABLED, true)

    fun setWeightEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_WEIGHT_ENABLED, enabled).apply()
    }
}

/**
 * Clears agent-owned data (macros, jobs, reminders, chat, mini-app stores).
 * Does not claim to undo Settings taps the agent made in other apps — say so
 * in the UI. API key is kept unless [wipeApiKey].
 */
object AgentReset {

    fun resetAgentData(context: Context, wipeApiKey: Boolean = false): String {
        val dir = context.filesDir
        MacroStore(context).let { store ->
            for (e in store.list()) store.remove(e.id)
        }
        JobStore(context).let { store ->
            for (e in store.list()) store.remove(e.id)
        }
        ReminderStore(context).let { store ->
            for (e in store.list()) store.remove(e.id)
        }
        ChatHistoryStore(context).clearAll()
        ChatStore.clear()
        File(dir, "agent_water.json").delete()
        File(dir, "agent_weight.json").delete()
        File(dir, "agent_miniapp_recipes.json").delete()
        File(dir, "miniapp_data").deleteRecursively()
        File(dir, "agent-audit.log").delete()
        if (wipeApiKey) {
            ApiKeyStore.clear(context)
        }
        return "Cleared macros, jobs, reminders, chat history, mini-app data, and audit log." +
            if (wipeApiKey) " API key wiped."
            else " API key kept."
    }
}
