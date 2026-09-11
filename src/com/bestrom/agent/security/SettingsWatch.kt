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

package com.bestrom.agent.security

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.bestrom.agent.AgentPrefs
import com.bestrom.agent.alert.AlertPoster

/**
 * Watches a curated list of important Settings URIs and notifies when they
 * change while security watch is on. Does not undo changes.
 */
object SettingsWatch {

    private val KEYS: List<Pair<Uri, String>> =
        listOf(
            Settings.Secure.getUriFor(Settings.Secure.ADB_ENABLED) to "USB debugging (ADB)",
            Settings.Global.getUriFor(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) to
                "Developer options",
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) to
                "Accessibility services",
        )

    @Volatile private var observer: ContentObserver? = null

    fun sync(context: Context) {
        val app = context.applicationContext
        if (AgentPrefs.securityWatch(app)) start(app) else stop(app)
    }

    fun start(context: Context) {
        if (observer != null) return
        val app = context.applicationContext
        val obs =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    val label =
                        KEYS.firstOrNull { it.first == uri }?.second
                            ?: uri?.lastPathSegment
                            ?: "a secure setting"
                    AlertPoster.post(
                        app,
                        app.getString(com.bestrom.agent.R.string.security_watch_title),
                        app.getString(
                            com.bestrom.agent.R.string.security_watch_body,
                            label,
                        ),
                        delivery = com.bestrom.agent.alert.AlertDelivery.NOTIFICATION,
                        salt = (uri?.hashCode() ?: 0),
                    )
                }
            }
        try {
            for ((uri, _) in KEYS) {
                app.contentResolver.registerContentObserver(uri, false, obs)
            }
            observer = obs
        } catch (_: Exception) {
            observer = null
        }
    }

    fun stop(context: Context) {
        val obs = observer ?: return
        try {
            context.applicationContext.contentResolver.unregisterContentObserver(obs)
        } catch (_: Exception) {
        }
        observer = null
    }
}
