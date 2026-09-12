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

/**
 * Bundle keys and limits for [IBrainProxy]. Shared with BestromBrainClient so
 * callers and the service agree without reading each other's code.
 */
object BrainProxyContract {
    const val ACTION = "com.bestrom.agent.action.BRAIN_PROXY"
    const val PACKAGE = "com.bestrom.agent"
    const val SERVICE = "com.bestrom.agent.brain.BrainProxyService"
    const val PERMISSION = "com.bestrom.agent.permission.USE_BRAIN"

    const val KEY_OK = "ok"
    const val KEY_TEXT = "text"
    const val KEY_ERROR = "error"
    const val KEY_CONFIGURED = "configured"
    const val KEY_READY = "ready"
    const val KEY_LOCKED = "locked"
    const val KEY_KEY_SET = "key_set"
    const val KEY_MODEL = "model"
    const val KEY_PRESET = "preset"
    const val KEY_CALLER = "caller"

    const val MAX_SYSTEM_CHARS = 8_192
    const val MAX_USER_CHARS = 8_192
    const val MAX_TOKENS = 512

    /** Per-UID completions allowed in a rolling minute. */
    const val MAX_CALLS_PER_MINUTE = 20
}
