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

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan

/**
 * Fenced-code background that paints a continuous rounded panel across the
 * lines the span covers, instead of a BackgroundColorSpan smear per glyph.
 */
class CodeBlockSpan(
    private val background: Int,
    private val radiusPx: Float,
    private val padH: Int,
    private val padV: Int,
) : LineBackgroundSpan, LeadingMarginSpan {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background }
    private val rect = RectF()

    override fun getLeadingMargin(first: Boolean): Int = padH

    override fun drawLeadingMargin(
        c: Canvas,
        p: Paint,
        x: Int,
        dir: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        first: Boolean,
        layout: Layout,
    ) {}

    override fun drawBackground(
        c: Canvas,
        p: Paint,
        left: Int,
        right: Int,
        top: Int,
        baseline: Int,
        bottom: Int,
        text: CharSequence,
        start: Int,
        end: Int,
        lineNumber: Int,
    ) {
        // Expand vertically a little so consecutive lines merge into one panel.
        rect.set(
            left.toFloat(),
            (top - padV / 2).toFloat(),
            right.toFloat(),
            (bottom + padV / 2).toFloat(),
        )
        c.drawRoundRect(rect, radiusPx, radiusPx, fill)
    }
}
