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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who may open a connection.
 *
 * The socket itself needs a device; the decision does not, and the decision is
 * the part that matters - sepolicy lets any platform_app connect, so a uid that
 * is not shell or root has to be refused here.
 */
class PeerTest {

    @Test
    fun shellAndRootAreTheWholeAllowlist() {
        assertTrue(Peer.isAllowed(Peer.UID_SHELL))
        assertTrue(Peer.isAllowed(Peer.UID_ROOT))
    }

    @Test
    fun everyOtherUidIsRefused() {
        // system_server, a platform_app in the same domain as this app, an
        // ordinary app, and the no-credentials case.
        for (uid in intArrayOf(1000, 1001, 10123, 10240, 99999, Peer.UID_UNKNOWN)) {
            assertFalse("uid $uid must not be allowed", Peer.isAllowed(uid))
        }
    }

    @Test
    fun theShellUidIsTheAndroidOne() {
        // AID_SHELL. adb forwards from adbd, which runs as this uid.
        assertFalse(Peer.isAllowed(2001))
        assertTrue(Peer.isAllowed(2000))
    }
}
