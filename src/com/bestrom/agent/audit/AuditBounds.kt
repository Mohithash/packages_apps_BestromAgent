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


package com.bestrom.agent.audit

/**
 * How big the audit log gets and what happens when it overflows.
 *
 * Kept apart from the file handling so the arithmetic can be checked without a
 * device.
 */
object AuditBounds {

    const val CAPACITY = 500

    /** How many entries go at once when the ring overflows. */
    const val TRIM_BLOCK = 50

    /**
     * The size the ring is cut back to after an append pushed it over.
     *
     * A whole block goes at a time, so the file is rewritten once every
     * [TRIM_BLOCK] appends rather than on every append past the cap - the
     * difference between one full rewrite per request and one per fifty.
     */
    @JvmStatic
    fun sizeAfterOverflow(size: Int): Int =
        if (size <= CAPACITY) size else maxOf(0, CAPACITY - TRIM_BLOCK + 1)
}
