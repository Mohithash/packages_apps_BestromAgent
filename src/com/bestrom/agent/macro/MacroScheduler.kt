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

package com.bestrom.agent.macro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bestrom.agent.jobs.BackgroundReceiver

object MacroScheduler {

    fun arm(context: Context, entry: MacroEntry) {
        if (!entry.enabled) return
        if (entry.trigger == MacroLimits.TRIGGER_BOOT) return
        if (entry.nextFireMs <= 0L) return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, entry.id)
        try {
            if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.nextFireMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, entry.nextFireMs, pi)
            }
        } catch (_: Exception) {
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.nextFireMs, pi)
            } catch (_: Exception) {
            }
        }
    }

    fun cancel(context: Context, id: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context, id))
    }

    fun rearmAll(context: Context) {
        val store = MacroStore(context)
        val now = System.currentTimeMillis()
        for (e in store.list()) {
            if (!e.enabled) continue
            when (e.trigger) {
                MacroLimits.TRIGGER_BOOT -> {
                    // Boot macros run from BackgroundReceiver on BOOT; no alarm.
                }
                MacroLimits.TRIGGER_ONCE -> {
                    if (e.nextFireMs > now) arm(context, e)
                }
                MacroLimits.TRIGGER_INTERVAL, MacroLimits.TRIGGER_BATTERY_BELOW -> {
                    val next =
                        if (e.nextFireMs > now) e
                        else store.bumpNext(e.id, now) ?: continue
                    arm(context, next)
                }
            }
        }
    }

    private fun pending(context: Context, id: String): PendingIntent {
        val intent =
            Intent(context, BackgroundReceiver::class.java)
                .setAction(BackgroundReceiver.ACTION_MACRO_FIRE)
                .putExtra(BackgroundReceiver.EXTRA_MACRO_ID, id)
        return PendingIntent.getBroadcast(
            context,
            ("macro-" + id).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
