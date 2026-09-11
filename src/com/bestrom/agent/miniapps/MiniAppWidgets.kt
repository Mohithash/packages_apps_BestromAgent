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

package com.bestrom.agent.miniapps

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.bestrom.agent.AgentPrefs
import com.bestrom.agent.R

class WaterWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (!AgentPrefs.waterEnabled(context)) return
        val store = WaterStore(context)
        val text =
            context.getString(R.string.water_status_fmt, store.todayCups(), store.goal())
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, WaterTrackerActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_water)
            views.setTextViewText(R.id.widget_water_text, text)
            views.setOnClickPendingIntent(R.id.widget_water_root, open)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}

class WeightWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        if (!AgentPrefs.weightEnabled(context)) return
        val store = WeightStore(context)
        val latest = store.latest()
        val text =
            if (latest == null) context.getString(R.string.weight_empty)
            else
                context.getString(
                    R.string.weight_widget_fmt,
                    latest.kg,
                    latest.day,
                )
        val open =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, WeightTrackerActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_weight)
            views.setTextViewText(R.id.widget_weight_text, text)
            views.setOnClickPendingIntent(R.id.widget_weight_root, open)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
