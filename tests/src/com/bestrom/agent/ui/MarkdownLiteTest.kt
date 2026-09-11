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

package com.bestrom.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownLiteTest {

    @Test
    fun parsesCodeTableAndInline() {
        val md =
            """
            ## Title
            Hello **world** and `code`.

            | A | B |
            |---|---|
            | 1 | 2 |

            ```kotlin
            val x = 1
            ```
            """.trimIndent()
        val segs = MarkdownLite.parse(md)
        assertTrue(segs.any { it.kind == MarkdownLite.Kind.HEADING })
        assertTrue(segs.any { it.kind == MarkdownLite.Kind.CODE && it.text.contains("val x") })
        assertTrue(segs.any { it.kind == MarkdownLite.Kind.TABLE && it.text.contains("│") })
        val (plain, marks) = MarkdownLite.inlineMarks("**bold** and *i*")
        assertEquals("bold and i", plain)
        assertTrue(marks.any { it.style == MarkdownLite.Style.BOLD })
        assertTrue(MarkdownLite.toPlain(md).contains("```"))
    }
}
