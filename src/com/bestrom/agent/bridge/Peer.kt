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


package com.bestrom.agent.bridge

/**
 * Who is allowed to open a connection.
 *
 * The abstract socket is not adbd-only. `allow domain self:unix_stream_socket
 * connectto` in system/sepolicy/private/domain.te expands to a rule that lets
 * any process in the same domain reach it, and this app runs in platform_app,
 * so every other platform-signed app can connect. sepolicy is therefore not
 * the control; the peer's uid is. An adb-forwarded connection is made by adbd
 * and presents AID_SHELL, so those two uids are the whole allowlist.
 */
object Peer {

    const val UID_ROOT = 0
    const val UID_SHELL = 2000

    /** No peer credentials at all reads as -1 and is refused with the rest. */
    const val UID_UNKNOWN = -1

    fun isAllowed(uid: Int): Boolean = uid == UID_SHELL || uid == UID_ROOT
}
