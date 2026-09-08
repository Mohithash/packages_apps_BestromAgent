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


package com.bestrom.agent

import android.content.Context

/**
 * Packages the bridge refuses to read or touch.
 *
 * The platform gives an accessibility service the screen tree of every app,
 * including the ones that block screenshots, so there is no system control to
 * lean on here. This list is the maintainer's own: any package named in it is
 * refused by ui.tree, ui.screenshot and ui.tap. It is empty by default and is
 * edited on the Agent mode screen, never over the bridge.
 */
object Denylist {

    private const val PREFS = "agent"
    private const val KEY = "denied_packages"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The packages currently excluded, in the order they are shown. */
    fun read(context: Context): List<String> =
        try {
            prefs(context).getStringSet(KEY, emptySet())!!.sorted()
        } catch (e: Exception) {
            emptyList()
        }

    /** Replaces the list. Blank lines and duplicates are dropped. */
    fun write(context: Context, packages: Collection<String>) {
        val cleaned =
            packages.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        prefs(context).edit().putStringSet(KEY, cleaned).apply()
    }

    /** True when [packageName] is excluded. */
    fun blocks(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return try {
            prefs(context).getStringSet(KEY, emptySet())!!.contains(packageName)
        } catch (e: Exception) {
            false
        }
    }
}
