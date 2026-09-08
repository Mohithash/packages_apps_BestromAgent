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


package com.bestrom.agent.toggle

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.bestrom.agent.AgentState
import com.bestrom.agent.a11y.AgentAccessibilityService
import com.bestrom.agent.bridge.AgentBridgeService

/**
 * The on and off sequences.
 *
 * The order of the on sequence is load bearing. The foreground service is
 * started first, because it is what sets AgentState.bridgeLive; only then is the
 * accessibility component enabled, so onServiceConnected sees a live bridge and
 * stays. Reversing the two makes the service disable itself the moment it binds.
 */
class AgentToggle(private val context: Context) {

    companion object {
        private const val TAG = "BestromAgent"
        private const val BRIDGE_WAIT_MS = 3000L
        private const val POLL_MS = 25L

        fun bridgeComponent(context: Context): ComponentName =
            ComponentName(context, AgentBridgeService::class.java)

        fun accessibilityComponent(context: Context): ComponentName =
            ComponentName(context, AgentAccessibilityService::class.java)
    }

    /** Why a turn-on attempt did not finish. */
    enum class Result {
        ON,
        NEEDS_NOTIFICATION_PERMISSION,
        BRIDGE_DID_NOT_START,
    }

    /**
     * The switch state is derived, never persisted: after a reboot bridgeLive is
     * false, so the switch reads off however the components were left.
     */
    fun isOn(): Boolean =
        AgentState.bridgeLive.get() &&
            AgentState.a11y != null &&
            componentEnabled(bridgeComponent(context))

    /** Runs the on sequence. Call off the main thread: it waits for the socket. */
    fun turnOn(): Result {
        if (!hasNotificationPermission()) return Result.NEEDS_NOTIFICATION_PERMISSION

        setComponent(bridgeComponent(context), true)

        val start = Intent(context, AgentBridgeService::class.java)
        start.action = AgentBridgeService.ACTION_START
        context.startForegroundService(start)

        val deadline = SystemClock.uptimeMillis() + BRIDGE_WAIT_MS
        while (!AgentState.bridgeLive.get() && SystemClock.uptimeMillis() < deadline) {
            try {
                Thread.sleep(POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!AgentState.bridgeLive.get()) {
            setComponent(bridgeComponent(context), false)
            return Result.BRIDGE_DID_NOT_START
        }

        setComponent(accessibilityComponent(context), true)
        enableAccessibilityService()
        return Result.ON
    }

    /** Runs the off sequence. Reachable from the switch, the notification and agent.stop. */
    fun turnOff() {
        val stop = Intent(context, AgentBridgeService::class.java)
        stop.action = AgentBridgeService.ACTION_STOP
        try {
            context.startService(stop)
        } catch (e: Exception) {
            // The service may already be gone; the reconcile below still runs.
            Log.i(TAG, "bridge already stopped")
        }
        AgentState.reset()
        reconcileOff()
    }

    /**
     * Takes the accessibility service back out of the secure setting and
     * disables both components. Safe to call when Agent mode is already off,
     * which is what makes it the reboot cleanup.
     */
    fun reconcileOff() {
        disableAccessibilityService()
        setComponent(accessibilityComponent(context), false)
        setComponent(bridgeComponent(context), false)
    }

    fun hasNotificationPermission(): Boolean =
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun accessibilityConnected(): Boolean = AgentState.a11y != null

    private fun componentEnabled(component: ComponentName): Boolean =
        context.packageManager.getComponentEnabledSetting(component) ==
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setComponent(component: ComponentName, enabled: Boolean) {
        val state =
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        try {
            context.packageManager.setComponentEnabledSetting(
                component,
                state,
                PackageManager.DONT_KILL_APP,
            )
        } catch (e: Exception) {
            Log.w(TAG, "cannot set component state for ${component.shortClassName}")
        }
    }

    /**
     * Appends our component to ENABLED_ACCESSIBILITY_SERVICES, preserving every
     * other entry. WRITE_SECURE_SETTINGS is what makes this possible without
     * sending the user to the accessibility screen.
     */
    private fun enableAccessibilityService() {
        val component = accessibilityComponent(context).flattenToString()
        val current = readEnabledServices()
        if (!current.contains(component)) current.add(component)
        writeEnabledServices(current)
        putSecureInt(Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    private fun disableAccessibilityService() {
        val component = accessibilityComponent(context).flattenToString()
        val shortForm = accessibilityComponent(context).flattenToShortString()
        val current = readEnabledServices()
        val kept = current.filter { it != component && it != shortForm }
        writeEnabledServices(ArrayList(kept))
        if (kept.isEmpty()) putSecureInt(Settings.Secure.ACCESSIBILITY_ENABLED, 0)
    }

    private fun readEnabledServices(): MutableList<String> {
        val raw =
            try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                )
            } catch (e: Exception) {
                null
            }
        if (raw.isNullOrEmpty()) return ArrayList()
        return ArrayList(raw.split(':').filter { it.isNotEmpty() }.distinct())
    }

    private fun writeEnabledServices(services: List<String>) {
        try {
            Settings.Secure.putString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                services.joinToString(":"),
            )
        } catch (e: Exception) {
            Log.w(TAG, "cannot write the accessibility service list")
        }
    }

    private fun putSecureInt(key: String, value: Int) {
        try {
            Settings.Secure.putInt(context.contentResolver, key, value)
        } catch (e: Exception) {
            Log.w(TAG, "cannot write $key")
        }
    }
}
