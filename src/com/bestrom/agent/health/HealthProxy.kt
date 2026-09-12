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

package com.bestrom.agent.health

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.CalendarContract
import com.bestrom.agent.miniapps.WaterStore
import com.bestrom.agent.miniapps.WeightStore

object HealthProxy {

    fun status(context: Context): Bundle {
        val water = WaterStore(context)
        val weight = WeightStore(context)
        val latest = weight.latest()
        val recent = weight.recent(5)
        val trend =
            when {
                recent.size < 2 -> "flat"
                recent[0].kg > recent.last().kg + 0.2f -> "up"
                recent[0].kg < recent.last().kg - 0.2f -> "down"
                else -> "flat"
            }
        return Bundle().apply {
            putBoolean(HealthProxyContract.KEY_OK, true)
            putInt(HealthProxyContract.KEY_WATER_CUPS, water.todayCups())
            putInt(HealthProxyContract.KEY_WATER_GOAL, water.goal())
            putInt(HealthProxyContract.KEY_WATER_STREAK, water.streakDays())
            if (latest != null) {
                putFloat(HealthProxyContract.KEY_WEIGHT_KG, latest.kg)
                putString(HealthProxyContract.KEY_WEIGHT_DAY, latest.day)
            }
            putString(HealthProxyContract.KEY_WEIGHT_TREND, trend)
            putString(HealthProxyContract.KEY_DISCLAIMER, HealthProxyContract.DISCLAIMER)
        }
    }

    fun addWaterCup(context: Context, cups: Int): Bundle {
        val n = cups.coerceIn(1, 5)
        val water = WaterStore(context)
        val next = water.addCup(n)
        return Bundle().apply {
            putBoolean(HealthProxyContract.KEY_OK, true)
            putInt(HealthProxyContract.KEY_WATER_CUPS, next)
            putInt(HealthProxyContract.KEY_WATER_GOAL, water.goal())
            putInt(HealthProxyContract.KEY_WATER_STREAK, water.streakDays())
        }
    }

    fun scheduleEvent(
        context: Context,
        title: String?,
        startEpochMs: Long,
        durationMin: Int,
        remindMin: Int,
    ): Bundle {
        val cleanTitle = title?.trim().orEmpty()
        if (cleanTitle.isEmpty() || cleanTitle.length > 200) {
            return fail("title missing or too long")
        }
        if (startEpochMs < System.currentTimeMillis() - 60_000L) {
            return fail("start time is in the past")
        }
        val duration = durationMin.coerceIn(5, 24 * 60)
        val remind = remindMin.coerceIn(0, 24 * 60)
        val calId = primaryCalendarId(context) ?: return fail("no calendar account on this phone")
        val end = startEpochMs + duration * 60_000L
        val values =
            ContentValues().apply {
                put(CalendarContract.Events.DTSTART, startEpochMs)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.TITLE, cleanTitle)
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            }
        val uri =
            try {
                context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            } catch (e: SecurityException) {
                return fail("calendar permission denied")
            } catch (e: Exception) {
                return fail("could not create event")
            } ?: return fail("could not create event")
        val eventId = ContentUris.parseId(uri)
        if (remind > 0 && eventId > 0) {
            val rem =
                ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, eventId)
                    put(CalendarContract.Reminders.MINUTES, remind)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                }
            try {
                context.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, rem)
            } catch (_: Exception) {
                // Event still created.
            }
        }
        return Bundle().apply {
            putBoolean(HealthProxyContract.KEY_OK, true)
            putLong(HealthProxyContract.KEY_EVENT_ID, eventId)
            putString(HealthProxyContract.KEY_EVENT_URI, uri.toString())
        }
    }

    private fun primaryCalendarId(context: Context): Long? {
        val projection =
            arrayOf(
                CalendarContract.Calendars._ID,
                CalendarContract.Calendars.VISIBLE,
                CalendarContract.Calendars.IS_PRIMARY,
            )
        try {
            context.contentResolver
                .query(CalendarContract.Calendars.CONTENT_URI, projection, null, null, null)
                .use { c ->
                    if (c == null) return null
                    var fallback: Long? = null
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        val visible = c.getInt(1) == 1
                        if (!visible) continue
                        val primary =
                            try {
                                c.getInt(2) == 1
                            } catch (_: Exception) {
                                false
                            }
                        if (primary) return id
                        if (fallback == null) fallback = id
                    }
                    return fallback
                }
        } catch (_: SecurityException) {
            return null
        }
    }

    private fun fail(msg: String): Bundle =
        Bundle().apply {
            putBoolean(HealthProxyContract.KEY_OK, false)
            putString(HealthProxyContract.KEY_ERROR, msg)
        }
}
