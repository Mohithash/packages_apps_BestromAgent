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

package com.bestrom.agent.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** Arms and cancels AlarmManager clocks for [ReminderStore] entries. */
object ReminderScheduler {

    fun arm(context: Context, entry: ReminderEntry) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = pending(context, entry.id)
        val show =
            PendingIntent.getActivity(
                context,
                entry.id.hashCode(),
                Intent(context, com.bestrom.agent.ui.AgentTaskActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        try {
            if (Build.VERSION.SDK_INT >= 21) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(entry.fireAtMs, show), pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, entry.fireAtMs, pi)
            }
        } catch (_: Exception) {
            // Exact-alarm permission or OEM refusal — entry stays on disk;
            // boot / next schedule attempt may still fire it.
            try {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, entry.fireAtMs, pi)
            } catch (_: Exception) {
            }
        }
    }

    fun cancel(context: Context, id: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pending(context, id))
    }

    fun rearmAll(context: Context) {
        val store = ReminderStore(context)
        val now = System.currentTimeMillis()
        for (e in store.list()) {
            if (e.fireAtMs > now) arm(context, e)
        }
    }

    private fun pending(context: Context, id: String): PendingIntent {
        val intent =
            Intent(context, ReminderReceiver::class.java)
                .setAction(ReminderReceiver.ACTION_FIRE)
                .putExtra(ReminderReceiver.EXTRA_ID, id)
        return PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
