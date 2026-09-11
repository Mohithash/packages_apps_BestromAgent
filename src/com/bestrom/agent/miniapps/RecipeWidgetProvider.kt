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
import com.bestrom.agent.R

/**
 * Homescreen tile for vibecode recipes. Shows the newest recipe summary;
 * tap opens that recipe (or the recipe list via settings if none).
 */
class RecipeWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val recipes = MiniAppRecipeStore(context).list()
        val runtime = MiniAppRuntimeStore(context)
        val newest = recipes.firstOrNull()
        val title = newest?.name ?: context.getString(R.string.miniapp_widget_empty_title)
        val summary =
            if (newest == null) context.getString(R.string.miniapp_widget_empty_body)
            else runtime.widgetSummary(newest) + " · " + recipes.size + " apps"
        val open =
            if (newest == null) {
                PendingIntent.getActivity(
                    context,
                    2,
                    Intent(context, com.bestrom.agent.ui.AgentSettingsActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            } else {
                PendingIntent.getActivity(
                    context,
                    3,
                    Intent(context, RecipeMiniAppActivity::class.java)
                        .putExtra(RecipeMiniAppActivity.EXTRA_ID, newest.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }
        for (id in appWidgetIds) {
            val views = RemoteViews(context.packageName, R.layout.widget_recipe)
            views.setTextViewText(R.id.widget_recipe_title, title)
            views.setTextViewText(R.id.widget_recipe_text, summary)
            views.setOnClickPendingIntent(R.id.widget_recipe_root, open)
            appWidgetManager.updateAppWidget(id, views)
        }
    }
}
