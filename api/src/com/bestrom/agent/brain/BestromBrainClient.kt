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

package com.bestrom.agent.brain

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tiny client for BestROM apps that need the shared brain.
 *
 * Bind, call [status] / [complete], then [unbind]. The API key stays inside
 * BestromAgent; this class never sees it.
 *
 * Callers must hold {@code com.bestrom.agent.permission.USE_BRAIN} and be
 * platform-signed (or otherwise granted that signature|privileged permission).
 */
class BestromBrainClient(private val context: Context) {

    private val proxy = AtomicReference<IBrainProxy?>(null)
    private val connected = CountDownLatch(1)

    private val connection =
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                proxy.set(IBrainProxy.Stub.asInterface(service))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                proxy.set(null)
            }
        }

    /** Binds and waits up to [timeoutMs]. Returns false if the service never answered. */
    fun bind(timeoutMs: Long = 5_000L): Boolean {
        if (proxy.get() != null) return true
        val intent =
            Intent(BrainProxyContract.ACTION).setComponent(
                ComponentName(BrainProxyContract.PACKAGE, BrainProxyContract.SERVICE)
            )
        val ok =
            context.bindService(
                intent,
                connection,
                Context.BIND_AUTO_CREATE,
            )
        if (!ok) return false
        return connected.await(timeoutMs, TimeUnit.MILLISECONDS) && proxy.get() != null
    }

    fun unbind() {
        try {
            context.unbindService(connection)
        } catch (_: Exception) {
            // Already unbound.
        }
        proxy.set(null)
    }

    fun status(): Bundle? = proxy.get()?.status()

    fun complete(systemPrompt: String?, userMessage: String): Bundle? =
        proxy.get()?.complete(systemPrompt, userMessage)

    /** True when Brain is configured, key present, and the device is unlocked. */
    fun isReady(): Boolean = status()?.getBoolean(BrainProxyContract.KEY_READY, false) == true
}
