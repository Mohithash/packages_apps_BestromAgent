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


package com.bestrom.agent.runner

import com.bestrom.agent.brain.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {

    private val boundary = InjectionFilter.Boundary("7f3a91")

    /** One round: the model calls read_screen and is given a digest back. */
    private fun round(transcript: Transcript, n: Int, screen: Boolean = true) {
        transcript.addAssistant(
            null,
            listOf(ChatMessage.Call("call_$n", "read_screen", "{}")),
        )
        transcript.addToolResult(
            "call_$n",
            boundary.envelope("read_screen", "com.android.settings", "screen $n"),
            screen,
        )
    }

    private fun contents(messages: List<ChatMessage>): List<String> =
        messages.map { it.content.orEmpty() }

    @Test
    fun theLastThreeRoundsSurviveWhole() {
        val transcript = Transcript()
        transcript.addUser("turn on battery saver")
        for (n in 1..6) round(transcript, n)
        val sent = contents(transcript.forSend())

        for (n in 4..6) {
            assertTrue("round $n was collapsed", sent.any { it.contains("screen $n") })
        }
        for (n in 1..3) {
            assertFalse("round $n survived", sent.any { it.contains("screen $n") })
        }
    }

    @Test
    fun theNewestScreenIsNeverCollapsedHoweverOldItsRoundIs() {
        val transcript = Transcript()
        transcript.addUser("read the note")
        round(transcript, 1)
        // Four rounds of something that is not a screen. The screen from round
        // one is now well outside the window, and is still the only thing in
        // here describing what is in front of the user.
        for (n in 2..5) {
            transcript.addAssistant(null, listOf(ChatMessage.Call("c$n", "call_function", "{}")))
            transcript.addToolResult("c$n", "the function answered: ok", false)
        }
        val sent = contents(transcript.forSend())
        assertTrue(sent.any { it.contains("screen 1") })
        assertEquals(0, sent.count { it == Transcript.SCREEN_OMITTED })
    }

    @Test
    fun anOlderScreenBecomesThePlaceholder() {
        val transcript = Transcript()
        transcript.addUser("goal")
        for (n in 1..6) round(transcript, n)
        val sent = contents(transcript.forSend())
        // Rounds 1-3 collapse; round 1's screen is not the newest, so it goes.
        assertEquals(3, sent.count { it == Transcript.SCREEN_OMITTED })
    }

    @Test
    fun anOlderToolResultBecomesOneLine() {
        val transcript = Transcript()
        transcript.addUser("goal")
        transcript.addAssistant(null, listOf(ChatMessage.Call("c1", "call_function", "{}")))
        transcript.addToolResult(
            "c1",
            boundary.envelope("call_function", "com.android.settings", "a".repeat(4000)),
            false,
        )
        for (n in 2..5) round(transcript, n)
        val sent = contents(transcript.forSend())
        val summary = sent.first { it.startsWith("aaa") }
        assertTrue(summary.length <= Transcript.SUMMARY_CHARS + 4)
        assertTrue(summary.endsWith("..."))
        // The envelope's own lines are not what gets summarised.
        assertFalse(sent.contains(boundary.header))
    }

    @Test
    fun compactionIsIdempotent() {
        val transcript = Transcript()
        transcript.addUser("goal")
        for (n in 1..8) round(transcript, n)
        val once = transcript.forSend().map { it.toJson().toString() }
        val twice = transcript.forSend().map { it.toJson().toString() }
        assertEquals(once, twice)
        // And nothing was thrown away: the entries are still all there.
        assertEquals(transcript.size(), transcript.forSend().size)
    }

    @Test
    fun theAssistantToolCallsAreKeptSoTheToolRepliesStillMatch() {
        val transcript = Transcript()
        transcript.addUser("goal")
        for (n in 1..6) round(transcript, n)
        val sent = transcript.forSend()
        // Every tool message answers a tool call that is still in the list.
        val callIds =
            sent.flatMap { it.toolCalls.orEmpty() }.map { it.id }.toSet()
        for (message in sent) {
            val id = message.toolCallId ?: continue
            assertTrue("orphan tool reply $id", callIds.contains(id))
        }
    }

    @Test
    fun aUserMessageIsNeverCollapsed() {
        val transcript = Transcript()
        transcript.addUser("turn on battery saver")
        for (n in 1..6) {
            round(transcript, n)
            if (n == 2) transcript.addUser(SystemPrompt.reassertion(boundary.control, "turn on battery saver"))
        }
        val sent = contents(transcript.forSend())
        assertTrue(sent.any { it == "turn on battery saver" })
        assertTrue(sent.any { it.contains("The goal is still") })
    }

    @Test
    fun aScreenshotIsAUserMessageWithAnImagePart() {
        val transcript = Transcript()
        transcript.addImage("The screenshot.", "aGVsbG8=")
        val message = transcript.forSend()[0]
        assertEquals(ChatMessage.USER, message.role)
        val content = message.toJson().getJSONArray("content")
        assertEquals(2, content.length())
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertTrue(
            content
                .getJSONObject(1)
                .getJSONObject("image_url")
                .getString("url")
                .startsWith("data:image/png;base64,")
        )
    }
}
