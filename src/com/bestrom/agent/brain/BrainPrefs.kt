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

import android.content.Context

/**
 * Where the brain choice is kept: the same "agent" preferences the excluded
 * package list already uses.
 *
 * The key is not here. It is in [ApiKeyStore], sealed to the keystore, so a
 * preferences file that is read by anything is a file with no secret in it.
 */
object BrainPrefs {

    private const val PREFS = "agent"

    private const val KEY_PRESET = "brain_preset"
    private const val KEY_BASE_URL = "brain_base_url"
    private const val KEY_MODEL = "brain_model"
    private const val KEY_WORKSPACE = "brain_workspace"
    private const val KEY_SCREENSHOTS = "brain_screenshots"
    private const val KEY_AUTONOMOUS = "brain_autonomous"
    private const val KEY_STEP_CAP = "brain_step_cap"
    private const val KEY_TOKEN_CAP = "brain_token_cap"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(context: Context): BrainConfig {
        val p = prefs(context)
        val preset = BrainPreset.byName(p.getString(KEY_PRESET, null))
        return BrainConfig(
            preset = preset,
            baseUrl = p.getString(KEY_BASE_URL, preset.defaultBaseUrl) ?: preset.defaultBaseUrl,
            model = p.getString(KEY_MODEL, "") ?: "",
            workspaceId = p.getString(KEY_WORKSPACE, "") ?: "",
            screenshots = p.getBoolean(KEY_SCREENSHOTS, false),
            autonomous = p.getBoolean(KEY_AUTONOMOUS, false),
            stepCap =
                p.getInt(KEY_STEP_CAP, BrainConfig.DEFAULT_STEP_CAP)
                    .coerceIn(BrainConfig.MIN_STEP_CAP, BrainConfig.MAX_STEP_CAP),
            tokenCap =
                p.getInt(KEY_TOKEN_CAP, BrainConfig.DEFAULT_TOKEN_CAP)
                    .coerceIn(BrainConfig.MIN_TOKEN_CAP, BrainConfig.MAX_TOKEN_CAP),
        )
    }

    fun write(context: Context, config: BrainConfig) {
        prefs(context)
            .edit()
            .putString(KEY_PRESET, config.preset.name)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL, config.model)
            .putString(KEY_WORKSPACE, config.workspaceId)
            .putBoolean(KEY_SCREENSHOTS, config.screenshots)
            .putBoolean(KEY_AUTONOMOUS, config.autonomous)
            .putInt(KEY_STEP_CAP, config.stepCap)
            .putInt(KEY_TOKEN_CAP, config.tokenCap)
            .apply()
    }
}
