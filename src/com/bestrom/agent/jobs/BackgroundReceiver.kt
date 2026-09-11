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

package com.bestrom.agent.jobs

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.bestrom.agent.macro.MacroLimits
import com.bestrom.agent.macro.MacroRunner
import com.bestrom.agent.macro.MacroScheduler
import com.bestrom.agent.macro.MacroStore

/**
 * Fires background jobs and macros. Re-arms after boot. Never enables Agent
 * mode (same contract as ReminderReceiver).
 */
class BackgroundReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_JOB_FIRE = "com.bestrom.agent.action.JOB_FIRE"
        const val ACTION_MACRO_FIRE = "com.bestrom.agent.action.MACRO_FIRE"
        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_MACRO_ID = "macro_id"
        const val CHANNEL_ID = "agent_background"
        const val NOTIFICATION_BASE = 8000
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                JobScheduler.rearmAll(context)
                MacroScheduler.rearmAll(context)
                runBootMacros(context)
            }
            Intent.ACTION_BATTERY_LOW, Intent.ACTION_BATTERY_OKAY -> {
                evaluateBatteryMacros(context)
            }
            ACTION_JOB_FIRE -> {
                val id = intent.getStringExtra(EXTRA_JOB_ID) ?: return
                fireJob(context, id)
            }
            ACTION_MACRO_FIRE -> {
                val id = intent.getStringExtra(EXTRA_MACRO_ID) ?: return
                fireMacro(context, id)
            }
        }
    }

    private fun fireJob(context: Context, id: String) {
        val store = JobStore(context)
        val entry = store.get(id) ?: return
        val summary = JobRunner.run(context, entry)
        val next = store.bumpNext(id, System.currentTimeMillis())
        if (next != null) JobScheduler.arm(context, next)
        notify(context, entry.label, summary, id.hashCode())
    }

    private fun fireMacro(context: Context, id: String) {
        val store = MacroStore(context)
        val entry = store.get(id) ?: return
        if (!entry.enabled) return
        when (entry.trigger) {
            MacroLimits.TRIGGER_BATTERY_BELOW -> {
                evaluateOneBatteryMacro(context, store, entry)
                val next = store.bumpNext(id, System.currentTimeMillis())
                if (next != null) MacroScheduler.arm(context, next)
            }
            else -> {
                val summary = MacroRunner.run(context, entry)
                when (entry.trigger) {
                    MacroLimits.TRIGGER_ONCE -> {
                        store.remove(id)
                        MacroScheduler.cancel(context, id)
                    }
                    MacroLimits.TRIGGER_INTERVAL -> {
                        val next = store.bumpNext(id, System.currentTimeMillis())
                        if (next != null) MacroScheduler.arm(context, next)
                    }
                }
                notify(context, entry.name, summary, id.hashCode())
            }
        }
    }

    private fun evaluateBatteryMacros(context: Context) {
        val store = MacroStore(context)
        for (e in store.list()) {
            if (!e.enabled || e.trigger != MacroLimits.TRIGGER_BATTERY_BELOW) continue
            evaluateOneBatteryMacro(context, store, e)
        }
    }

    private fun evaluateOneBatteryMacro(
        context: Context,
        store: MacroStore,
        entry: com.bestrom.agent.macro.MacroEntry,
    ) {
        val level = batteryLevelPct(context)
        if (level < 0) return
        if (level <= entry.batteryBelowPct) {
            if (!entry.batteryLatched) {
                val summary = MacroRunner.run(context, entry)
                store.setBatteryLatched(entry.id, true)
                notify(
                    context,
                    entry.name,
                    "Battery $level% ≤ ${entry.batteryBelowPct}%\n$summary",
                    entry.id.hashCode(),
                )
            }
        } else if (entry.batteryLatched) {
            store.setBatteryLatched(entry.id, false)
        }
    }

    private fun batteryLevelPct(context: Context): Int {
        val sticky =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?: return -1
        val level = sticky.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = sticky.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return -1
        return (level * 100) / scale
    }

    private fun runBootMacros(context: Context) {
        for (e in MacroStore(context).list()) {
            if (!e.enabled || e.trigger != MacroLimits.TRIGGER_BOOT) continue
            val summary = MacroRunner.run(context, e)
            notify(context, e.name, summary, e.id.hashCode())
        }
    }

    private fun notify(context: Context, title: String, text: String, salt: Int) {
        com.bestrom.agent.alert.AlertPoster.post(context, title, text, salt = salt)
    }
}
