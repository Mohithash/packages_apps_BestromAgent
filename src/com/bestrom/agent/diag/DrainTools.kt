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

package com.bestrom.agent.diag

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.bestrom.agent.runner.ToolDispatch
import com.bestrom.agent.runner.ToolSchema
import org.json.JSONObject

/**
 * Battery / idle-drain snapshots from public BatteryManager APIs.
 *
 * This is not dumpsys batterystats math. Two samples with [measure_idle_drain]
 * yield a counter delta and an average µA estimate when charge_counter moves.
 * Background re-arm is a later phase; the tool itself is sync and local.
 */
object DrainTools {

    private const val PREFS = "agent_drain"
    private const val KEY_MS = "sample_ms"
    private const val KEY_COUNTER = "charge_counter_uah"
    private const val KEY_LEVEL = "level_pct"
    private const val KEY_CURRENT = "current_now_ua"

    fun run(context: Context, call: ToolSchema.ToolCall): ToolDispatch.Outcome =
        when (call.name) {
            ToolSchema.MEASURE_IDLE_DRAIN -> measure(context)
            else -> ToolDispatch.Outcome.Failed(-32602, "unknown drain tool", null)
        }

    private fun measure(context: Context): ToolDispatch.Outcome {
        val now = sample(context)
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prevMs = prefs.getLong(KEY_MS, 0L)
        val prevCounter = prefs.getLong(KEY_COUNTER, Long.MIN_VALUE)
        val prevLevel = prefs.getInt(KEY_LEVEL, -1)
        val prevCurrent = prefs.getLong(KEY_CURRENT, Long.MIN_VALUE)

        prefs
            .edit()
            .putLong(KEY_MS, now.ms)
            .putLong(KEY_COUNTER, now.chargeCounterUah)
            .putInt(KEY_LEVEL, now.levelPct)
            .putLong(KEY_CURRENT, now.currentNowUa)
            .apply()

        val out =
            JSONObject()
                .put("ok", true)
                .put("sample_ms", now.ms)
                .put("level_pct", now.levelPct)
                .put("status", now.status)
                .put("plugged", now.plugged)
                .put("charge_counter_uah", now.chargeCounterUah)
                .put("current_now_ua", now.currentNowUa)
                .put(
                    "note",
                    "Call again later (screen off, idle) for a delta. " +
                        "For dumpsys batterystats (Maintainer+), use batterystats_snippet.",
                )

        if (prevMs > 0L && prevCounter != Long.MIN_VALUE) {
            val dtMs = (now.ms - prevMs).coerceAtLeast(1L)
            val dCounter = now.chargeCounterUah - prevCounter
            val hours = dtMs / 3_600_000.0
            val avgUa =
                if (hours > 0 && now.chargeCounterUah != Long.MIN_VALUE &&
                    prevCounter != Long.MIN_VALUE
                ) {
                    // charge_counter drops as the pack discharges; negative delta → positive drain.
                    (-dCounter) / hours
                } else {
                    Double.NaN
                }
            out.put("previous_sample_ms", prevMs)
            out.put("elapsed_ms", dtMs)
            out.put("charge_counter_delta_uah", dCounter)
            out.put("level_delta_pct", now.levelPct - prevLevel)
            if (!avgUa.isNaN()) {
                out.put("estimated_avg_drain_ua", avgUa.toLong())
                out.put("estimated_avg_drain_ma", avgUa / 1000.0)
            }
            if (prevCurrent != Long.MIN_VALUE) {
                out.put("previous_current_now_ua", prevCurrent)
            }
        } else {
            out.put("first_sample", true)
        }
        return ToolDispatch.Outcome.Ok(out)
    }

    private data class Sample(
        val ms: Long,
        val levelPct: Int,
        val status: String,
        val plugged: String,
        val chargeCounterUah: Long,
        val currentNowUa: Long,
    )

    private fun sample(context: Context): Sample {
        val bm = context.getSystemService(BatteryManager::class.java)
        val sticky =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status =
            when (sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                else -> "unknown"
            }
        val plugged =
            when (sticky?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                0 -> "unplugged"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "other"
            }
        val counter =
            bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: Long.MIN_VALUE
        val current =
            bm?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: Long.MIN_VALUE
        return Sample(System.currentTimeMillis(), pct, status, plugged, counter, current)
    }
}
