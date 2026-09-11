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

/**
 * Lightweight markdown → display segments. Host-testable; no Android.
 *
 * Supports fenced code, pipe tables, bold/italic, inline code, headings,
 * bullet lists. Enough for ChatGPT-style assistant bubbles without a Maven
 * markdown library.
 */
object MarkdownLite {

    enum class Kind {
        TEXT,
        CODE,
        TABLE,
        HEADING,
    }

    data class Segment(val kind: Kind, val text: String, val lang: String = "")

    fun parse(src: String): List<Segment> {
        if (src.isEmpty()) return emptyList()
        val out = ArrayList<Segment>()
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0
        val prose = StringBuilder()

        fun flushProse() {
            if (prose.isNotEmpty()) {
                out.add(Segment(Kind.TEXT, prose.toString().trimEnd()))
                prose.clear()
            }
        }

        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("```")) {
                flushProse()
                val lang = line.removePrefix("```").trim()
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                out.add(Segment(Kind.CODE, body.toString(), lang))
                if (i < lines.size) i++ // closing fence
                continue
            }
            if (isTableHeader(lines, i)) {
                flushProse()
                val table = StringBuilder()
                while (i < lines.size && lines[i].contains('|')) {
                    if (table.isNotEmpty()) table.append('\n')
                    table.append(lines[i])
                    i++
                }
                out.add(Segment(Kind.TABLE, formatTable(table.toString())))
                continue
            }
            if (line.startsWith("#")) {
                flushProse()
                out.add(Segment(Kind.HEADING, line.trimStart('#').trim()))
                i++
                continue
            }
            if (prose.isNotEmpty()) prose.append('\n')
            prose.append(line)
            i++
        }
        flushProse()
        return out
    }

    /** Plain export for .txt / share (keeps structure readable). */
    fun toPlain(src: String): String {
        val sb = StringBuilder()
        for (seg in parse(src)) {
            when (seg.kind) {
                Kind.CODE -> {
                    sb.append("```").append(seg.lang).append('\n')
                    sb.append(seg.text).append('\n')
                    sb.append("```\n")
                }
                Kind.TABLE, Kind.HEADING, Kind.TEXT -> {
                    if (seg.kind == Kind.HEADING) sb.append("## ")
                    sb.append(seg.text).append('\n')
                }
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    private fun isTableHeader(lines: List<String>, i: Int): Boolean {
        if (i + 1 >= lines.size) return false
        if (!lines[i].contains('|')) return false
        val sep = lines[i + 1].trim()
        return sep.contains('|') && sep.contains('-')
    }

    private fun formatTable(raw: String): String {
        val parsed = ArrayList<List<String>>()
        for (line in raw.lineSequence()) {
            val trimmed = line.trim().trim('|')
            if (trimmed.isEmpty()) continue
            val cells = trimmed.split('|').map { it.trim() }
            if (cells.all { it.matches(Regex("^:?-+:?$")) }) continue
            parsed.add(cells)
        }
        if (parsed.isEmpty()) return raw
        val cols = parsed.maxOf { it.size }
        val widths =
            IntArray(cols) { c ->
                parsed.maxOf { row -> row.getOrElse(c) { "" }.length }.coerceAtLeast(1)
            }
        val sb = StringBuilder()
        for ((ri, row) in parsed.withIndex()) {
            for (c in 0 until cols) {
                if (c > 0) sb.append(" │ ")
                sb.append(row.getOrElse(c) { "" }.padEnd(widths[c]))
            }
            sb.append('\n')
            if (ri == 0) {
                for (c in 0 until cols) {
                    if (c > 0) sb.append("─┼─")
                    sb.append("─".repeat(widths[c]))
                }
                sb.append('\n')
            }
        }
        return sb.toString().trimEnd()
    }

    /** Strip markdown markers for a flat spannable pass (bold/italic/code). */
    data class SpanMark(val start: Int, val end: Int, val style: Style)

    enum class Style {
        BOLD,
        ITALIC,
        CODE,
    }

    fun inlineMarks(text: String): Pair<String, List<SpanMark>> {
        val out = StringBuilder()
        val marks = ArrayList<SpanMark>()
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("**", i) || text.startsWith("__", i) -> {
                    val delim = text.substring(i, i + 2)
                    val close = text.indexOf(delim, i + 2)
                    if (close > i) {
                        val start = out.length
                        out.append(text.substring(i + 2, close))
                        marks.add(SpanMark(start, out.length, Style.BOLD))
                        i = close + 2
                    } else {
                        out.append(text[i])
                        i++
                    }
                }
                text[i] == '*' || text[i] == '_' -> {
                    val delim = text[i]
                    val close = text.indexOf(delim, i + 1)
                    if (close > i) {
                        val start = out.length
                        out.append(text.substring(i + 1, close))
                        marks.add(SpanMark(start, out.length, Style.ITALIC))
                        i = close + 1
                    } else {
                        out.append(text[i])
                        i++
                    }
                }
                text[i] == '`' -> {
                    val close = text.indexOf('`', i + 1)
                    if (close > i) {
                        val start = out.length
                        out.append(text.substring(i + 1, close))
                        marks.add(SpanMark(start, out.length, Style.CODE))
                        i = close + 1
                    } else {
                        out.append(text[i])
                        i++
                    }
                }
                else -> {
                    out.append(text[i])
                    i++
                }
            }
        }
        return out.toString() to marks
    }
}
