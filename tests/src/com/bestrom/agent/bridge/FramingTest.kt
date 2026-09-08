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

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Newline-delimited framing: the round trip and the byte cap. */
class FramingTest {

    private fun read(text: String, limit: Int = Framing.MAX_REQUEST_LINE_BYTES): String? =
        Framing.readLine(ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)), limit)

    @Test
    fun escapedControlCharactersRoundTrip() {
        // What a JSON encoder puts on the wire for a value holding a newline, a
        // carriage return and an unpaired surrogate: escapes, never raw bytes.
        val line = """{"jsonrpc":"2.0","id":1,"method":"ui.type","params":{"text":"a\nb\rc\ud800"}}"""
        val out = ByteArrayOutputStream()
        Framing.writeLine(out, line)
        val bytes = out.toByteArray()
        assertEquals(0x0A.toByte(), bytes[bytes.size - 1])
        val back = Framing.readLine(ByteArrayInputStream(bytes))
        assertEquals(line, back)
    }

    @Test
    fun twoLinesAreReadSeparately() {
        val input = ByteArrayInputStream("{\"a\":1}\n{\"b\":2}\n".toByteArray(Charsets.UTF_8))
        assertEquals("{\"a\":1}", Framing.readLine(input))
        assertEquals("{\"b\":2}", Framing.readLine(input))
        assertNull(Framing.readLine(input))
    }

    @Test
    fun aTrailingFragmentIsNotDropped() {
        assertEquals("{\"a\":1}", read("{\"a\":1}"))
    }

    @Test
    fun aLineAtTheCapIsAccepted() {
        val line = "x".repeat(64)
        assertEquals(line, read(line + "\n", 64))
    }

    @Test
    fun aLineOneByteOverTheCapIsRejected() {
        var thrown = false
        try {
            read("x".repeat(65) + "\n", 64)
        } catch (e: Framing.LineTooLongException) {
            thrown = true
            assertEquals(64, e.limit)
        }
        assertTrue("a line over the cap must be refused", thrown)
    }

    @Test
    fun aResponseOverTheCapIsRejected() {
        var thrown = false
        try {
            Framing.writeLine(ByteArrayOutputStream(), "y".repeat(100), 99)
        } catch (e: Framing.LineTooLongException) {
            thrown = true
        }
        assertTrue("an oversize response must be refused", thrown)
    }
}
