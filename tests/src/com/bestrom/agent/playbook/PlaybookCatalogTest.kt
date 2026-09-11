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

package com.bestrom.agent.playbook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybookCatalogTest {

    @Test
    fun catalogHasStableIdsAndFoodStopsBeforePay() {
        assertTrue(PlaybookCatalog.ALL.isNotEmpty())
        assertEquals(PlaybookCatalog.ALL.map { it.id }.toSet().size, PlaybookCatalog.ALL.size)
        val food = PlaybookCatalog.get("order_food")
        assertNotNull(food)
        assertTrue(food!!.needsDetail)
        val goal = PlaybookCatalog.expand("order_food", "biryani for 2 from Swiggy")
        assertNotNull(goal)
        assertTrue(goal!!.contains("biryani"))
        assertTrue(goal.contains("Never tap") || goal.contains("stop at checkout"))
        assertTrue(goal.lowercase().contains("pay") || goal.lowercase().contains("checkout"))
    }

    @Test
    fun detailRequiredAndBounded() {
        assertNotNull(PlaybookCatalog.rejectDetail(PlaybookCatalog.get("order_food")!!, ""))
        assertNull(
            PlaybookCatalog.rejectDetail(
                PlaybookCatalog.get("order_food")!!,
                "pizza",
            )
        )
        assertNull(PlaybookCatalog.rejectId("maps_navigate"))
        assertNotNull(PlaybookCatalog.rejectId("nope"))
        assertNull(PlaybookCatalog.expand("order_food", ""))
        assertNotNull(PlaybookCatalog.expand("share_screen_summary", ""))
        assertFalse(
            PlaybookCatalog.expand("maps_navigate", "home")!!.contains("{detail}")
        )
    }
}
