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

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Client for water/weight status, +cup, and calendar insert. */
class BestromHealthClient(private val context: Context) {
    private val proxy = AtomicReference<IHealthProxy?>(null)
    private val connected = CountDownLatch(1)

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                proxy.set(IHealthProxy.Stub.asInterface(service))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                proxy.set(null)
            }
        }

    @JvmOverloads
    fun bind(timeoutMs: Long = 5_000L): Boolean {
        if (proxy.get() != null) return true
        val intent =
            Intent(HealthProxyContract.ACTION).setComponent(
                ComponentName(HealthProxyContract.PACKAGE, HealthProxyContract.SERVICE)
            )
        if (!context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) return false
        return connected.await(timeoutMs, TimeUnit.MILLISECONDS) && proxy.get() != null
    }

    fun unbind() {
        try {
            context.unbindService(connection)
        } catch (_: Exception) {
        }
        proxy.set(null)
    }

    fun status(): Bundle? = proxy.get()?.getStatus()

    @JvmOverloads
    fun addWaterCup(cups: Int = 1): Bundle? = proxy.get()?.addWaterCup(cups)

    @JvmOverloads
    fun scheduleEvent(
        title: String,
        startEpochMs: Long,
        durationMin: Int = 30,
        remindMin: Int = 10,
    ): Bundle? = proxy.get()?.scheduleEvent(title, startEpochMs, durationMin, remindMin)
}
