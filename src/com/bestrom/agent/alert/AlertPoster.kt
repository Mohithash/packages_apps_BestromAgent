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

package com.bestrom.agent.alert

import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.bestrom.agent.AgentPrefs
import com.bestrom.agent.R
import com.bestrom.agent.ui.AgentTaskActivity

/** Posts alerts via notification, toast, or dialog (with safe fallbacks). */
object AlertPoster {

    const val CHANNEL_ID = "agent_alerts"
    private const val NOTIFICATION_BASE = 9100

    fun post(
        context: Context,
        title: String,
        text: String,
        delivery: String? = null,
        salt: Int = text.hashCode(),
    ) {
        val mode =
            AlertDelivery.normalize(
                delivery?.takeIf { it.isNotEmpty() } ?: AgentPrefs.alertDelivery(context)
            )
        when (mode) {
            AlertDelivery.TOAST -> {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context.applicationContext, "$title\n$text", Toast.LENGTH_LONG)
                        .show()
                }
                // Also notify so it is not lost if the toast is missed.
                notify(context, title, text, salt)
            }
            AlertDelivery.DIALOG -> {
                val activity = findResumedActivity()
                if (activity != null) {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            AlertDialog.Builder(activity)
                                .setTitle(title)
                                .setMessage(text)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                        } catch (_: Exception) {
                            notify(context, title, text, salt)
                        }
                    }
                } else {
                    notify(context, title, text, salt)
                }
            }
            else -> notify(context, title, text, salt)
        }
    }

    fun notify(context: Context, title: String, text: String, salt: Int) {
        ensureChannel(context)
        val open =
            PendingIntent.getActivity(
                context,
                salt,
                Intent(context, AgentTaskActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_agent_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(Notification.CATEGORY_STATUS)
                .build()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIFICATION_BASE + (salt and 0xffff), notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.alerts_channel),
                    NotificationManager.IMPORTANCE_DEFAULT,
                )
                .apply { description = context.getString(R.string.alerts_channel_desc) }
        )
    }

    /** Best-effort: ActivityTracker registers the foreground chat activity. */
    private fun findResumedActivity(): Activity? = ActivityTracker.resumed
}
