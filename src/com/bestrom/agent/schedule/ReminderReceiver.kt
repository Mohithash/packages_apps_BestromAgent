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

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.bestrom.agent.AgentState
import com.bestrom.agent.R
import com.bestrom.agent.ui.AgentTaskActivity

/**
 * Fires a reminder notification, or starts a deferred agent goal when Agent
 * mode is still on. Also re-arms alarms after boot.
 */
class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_FIRE = "com.bestrom.agent.action.REMINDER_FIRE"
        const val ACTION_BOOT = Intent.ACTION_BOOT_COMPLETED
        const val EXTRA_ID = "reminder_id"
        const val CHANNEL_ID = "agent_reminders"
        const val NOTIFICATION_BASE = 7000
    }

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED -> {
                ReminderScheduler.rearmAll(context)
            }
            ACTION_FIRE -> {
                val id = intent.getStringExtra(EXTRA_ID) ?: return
                val store = ReminderStore(context)
                val entry = store.remove(id) ?: return
                ReminderScheduler.cancel(context, id)
                when (entry.kind) {
                    ReminderTime.KIND_TASK -> fireTask(context, entry)
                    else -> notifyReminder(context, entry)
                }
            }
        }
    }

    private fun fireTask(context: Context, entry: ReminderEntry) {
        val bridge = AgentState.bridge
        if (AgentState.bridgeLive.get() && bridge != null) {
            val error = bridge.startTask(entry.text)
            if (error == null) {
                notifyReminder(
                    context,
                    entry.copy(text = "Running scheduled task: " + entry.text),
                )
                return
            }
        }
        notifyReminder(
            context,
            entry.copy(text = "Scheduled task ready — open Agent and run: " + entry.text),
        )
    }

    private fun notifyReminder(context: Context, entry: ReminderEntry) {
        ensureChannel(context)
        val open =
            PendingIntent.getActivity(
                context,
                entry.id.hashCode(),
                Intent(context, AgentTaskActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_agent_notification)
                .setContentTitle(
                    if (entry.kind == ReminderTime.KIND_TASK) {
                        context.getString(R.string.reminder_task_title)
                    } else {
                        context.getString(R.string.reminder_title)
                    }
                )
                .setContentText(entry.text)
                .setStyle(Notification.BigTextStyle().bigText(entry.text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_REMINDER)
                .build()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_BASE + (entry.id.hashCode() and 0xffff), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.reminder_channel),
                    NotificationManager.IMPORTANCE_HIGH,
                )
                .apply { description = context.getString(R.string.reminder_channel_desc) }
        )
    }
}
