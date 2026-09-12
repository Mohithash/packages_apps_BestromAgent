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

object HealthProxyContract {
    const val ACTION = "com.bestrom.agent.action.HEALTH_PROXY"
    const val PACKAGE = "com.bestrom.agent"
    const val SERVICE = "com.bestrom.agent.health.HealthProxyService"
    const val PERMISSION = "com.bestrom.agent.permission.USE_BRAIN"

    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_WATER_CUPS = "water_cups"
    const val KEY_WATER_GOAL = "water_goal"
    const val KEY_WATER_STREAK = "water_streak"
    const val KEY_WEIGHT_KG = "weight_kg"
    const val KEY_WEIGHT_DAY = "weight_day"
    const val KEY_WEIGHT_TREND = "weight_trend"
    const val KEY_DISCLAIMER = "disclaimer"
    const val KEY_EVENT_ID = "event_id"
    const val KEY_EVENT_URI = "event_uri"

    const val DISCLAIMER =
        "Not medical advice. Water and weight are logs you entered on this phone."
}
