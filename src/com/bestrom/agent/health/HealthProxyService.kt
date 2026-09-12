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

import android.app.Service
import android.content.Intent
import android.os.IBinder

class HealthProxyService : Service() {
    private val binder =
        object : IHealthProxy.Stub() {
            override fun getStatus() = HealthProxy.status(this@HealthProxyService)

            override fun addWaterCup(cups: Int) = HealthProxy.addWaterCup(this@HealthProxyService, cups)

            override fun scheduleEvent(
                title: String?,
                startEpochMs: Long,
                durationMin: Int,
                remindMin: Int,
            ) = HealthProxy.scheduleEvent(this@HealthProxyService, title, startEpochMs, durationMin, remindMin)
        }

    override fun onBind(intent: Intent?): IBinder = binder
}
