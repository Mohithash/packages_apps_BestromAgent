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

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import androidx.core.content.ContextCompat
import com.bestrom.agent.R

/** Applies [MarkdownLite] segments to a Spannable for assistant bubbles. */
object MarkdownRender {

    fun toSpannable(context: Context, markdown: String): CharSequence {
        val segments = MarkdownLite.parse(markdown)
        if (segments.isEmpty()) return markdown
        val codeBg = ContextCompat.getColor(context, R.color.agent_code_bg)
        val codeFg = ContextCompat.getColor(context, R.color.agent_code_fg)
        val density = context.resources.displayMetrics.density
        val radius = 8f * density
        val padH = (10 * density).toInt()
        val padV = (4 * density).toInt()
        val sb = SpannableStringBuilder()
        for ((index, seg) in segments.withIndex()) {
            if (index > 0) sb.append('\n')
            when (seg.kind) {
                MarkdownLite.Kind.CODE -> {
                    val start = sb.length
                    if (seg.lang.isNotEmpty()) {
                        sb.append(seg.lang)
                        sb.append('\n')
                        sb.setSpan(
                            RelativeSizeSpan(0.78f),
                            start,
                            sb.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        sb.setSpan(
                            ForegroundColorSpan(codeFg),
                            start,
                            sb.length,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                    val bodyStart = sb.length
                    sb.append(seg.text)
                    if (sb.length == bodyStart) sb.append(' ')
                    val end = sb.length
                    sb.setSpan(
                        TypefaceSpan("monospace"),
                        bodyStart,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    sb.setSpan(
                        RelativeSizeSpan(0.90f),
                        bodyStart,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    sb.setSpan(
                        ForegroundColorSpan(codeFg),
                        bodyStart,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    sb.setSpan(
                        CodeBlockSpan(codeBg, radius, padH, padV),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                MarkdownLite.Kind.TABLE -> {
                    val start = sb.length
                    sb.append(seg.text)
                    sb.setSpan(
                        TypefaceSpan("monospace"),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    sb.setSpan(
                        RelativeSizeSpan(0.88f),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                MarkdownLite.Kind.HEADING -> {
                    val start = sb.length
                    sb.append(seg.text)
                    sb.setSpan(
                        StyleSpan(Typeface.BOLD),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    sb.setSpan(
                        RelativeSizeSpan(1.12f),
                        start,
                        sb.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                MarkdownLite.Kind.TEXT -> appendInline(sb, seg.text, codeBg, codeFg)
            }
        }
        return sb
    }

    private fun appendInline(
        sb: SpannableStringBuilder,
        text: String,
        codeBg: Int,
        codeFg: Int,
    ) {
        val (plain, marks) = MarkdownLite.inlineMarks(text)
        val start = sb.length
        sb.append(plain)
        for (m in marks) {
            val a = start + m.start
            val b = start + m.end
            if (a >= b || b > sb.length) continue
            when (m.style) {
                MarkdownLite.Style.BOLD ->
                    sb.setSpan(StyleSpan(Typeface.BOLD), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                MarkdownLite.Style.ITALIC ->
                    sb.setSpan(StyleSpan(Typeface.ITALIC), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                MarkdownLite.Style.CODE -> {
                    sb.setSpan(TypefaceSpan("monospace"), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(RelativeSizeSpan(0.92f), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(BackgroundColorSpan(codeBg), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(ForegroundColorSpan(codeFg), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }
}
