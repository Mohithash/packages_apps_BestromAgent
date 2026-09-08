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

import org.json.JSONObject

/**
 * The screen as the model reads it: one line per element.
 *
 * This is the token bill. The raw ui.tree of a Settings page is thirty to
 * sixty kilobytes of JSON; the same page here is three or four, because a node
 * with nothing to read and nothing to press contributes nothing and the rest
 * is a line rather than an object.
 *
 * The output is a pure function of the tree, byte for byte: the stuck detector
 * hashes it, so a digest that varied between two identical screens would make
 * a loop invisible.
 *
 * The idea is minitap mobile-use's Contextor (Apache-2.0); the code is written
 * here.
 */
object ScreenDigest {

    /** How deep the indent goes before it stops growing. */
    const val MAX_INDENT_DEPTH = 6

    /** What [Digest.diff] says when nothing moved. The runner tests for it. */
    const val NO_CHANGE = "the screen did not change"

    class Node(
        val id: Int,
        val depth: Int,
        val cls: String,
        val pkg: String,
        val line: String,
        /** What to call this element in a sentence a person reads. */
        val label: String,
        val password: Boolean,
        val editable: Boolean,
        val clickable: Boolean,
    )

    /**
     * What one read_screen produced.
     *
     * [text] is what the model is shown. The rest is what the runner and the
     * policy engine need and the model never sees.
     */
    class Digest(
        val treeId: String,
        val windowPackage: String,
        val windowTitle: String,
        val text: String,
        val nodes: List<Node>,
        val shown: Int,
        val total: Int,
        val truncated: Boolean,
    ) {
        private val byId: Map<Int, Node> = nodes.associateBy { it.id }

        fun node(id: Int): Node? = byId[id]

        fun isPassword(id: Int): Boolean = byId[id]?.password == true

        /** A stable fingerprint of what is on screen, for the stuck detector. */
        fun hash(): String {
            // FNV-1a: short, stable, and no dependency on a digest provider
            // that a host test would have to bring along.
            var h = -0x340d631b7bdddcdbL
            for (c in text) {
                h = h xor c.code.toLong()
                h *= 0x100000001b3L
            }
            return java.lang.Long.toHexString(h)
        }

        /** What appeared and what went, in one line, after an action. */
        fun diff(previous: Digest?): String {
            if (previous == null) return ""
            val before = previous.nodes.map { it.line }.toSet()
            val after = nodes.map { it.line }.toSet()
            val appeared = after.count { !before.contains(it) }
            val gone = before.count { !after.contains(it) }
            if (appeared == 0 && gone == 0) {
                return if (previous.windowPackage == windowPackage) NO_CHANGE
                else "now in $windowPackage"
            }
            return "$appeared new, $gone gone" +
                if (previous.windowPackage != windowPackage) ", now in $windowPackage" else ""
        }
    }

    /** Builds the digest from one ui.tree result. */
    fun of(tree: JSONObject): Digest {
        val treeId = tree.optString("tree_id")
        val window = tree.optJSONObject("window") ?: JSONObject()
        val windowPackage = window.optString("package")
        val windowTitle =
            if (window.isNull("title")) "" else InjectionFilter.sanitise(window.optString("title"))
        val bounds = window.optJSONArray("bounds")
        val width = if (bounds != null && bounds.length() == 4) bounds.optInt(2) - bounds.optInt(0) else 0
        val height = if (bounds != null && bounds.length() == 4) bounds.optInt(3) - bounds.optInt(1) else 0

        val raw = tree.optJSONArray("nodes")
        val total = raw?.length() ?: 0
        val depths = IntArray(total)
        if (raw != null) {
            for (i in 0 until total) {
                val children = raw.optJSONObject(i)?.optJSONArray("children") ?: continue
                for (c in 0 until children.length()) {
                    val child = children.optInt(c, -1)
                    if (child in 0 until total) depths[child] = depths[i] + 1
                }
            }
        }

        val kept = ArrayList<Node>()
        val out = StringBuilder()
        val header = StringBuilder()
        header.append(if (windowPackage.isEmpty()) "unknown app" else windowPackage)
        if (windowTitle.isNotEmpty()) header.append("  \"").append(windowTitle).append('"')
        if (width > 0 && height > 0) header.append("  ").append(width).append('x').append(height)
        out.append(header).append('\n')

        if (raw != null) {
            for (i in 0 until total) {
                val node = raw.optJSONObject(i) ?: continue
                if (!worthShowing(node)) continue
                val line = render(node, depths[i])
                out.append(line).append('\n')
                kept.add(
                    Node(
                        node.optInt("id", i),
                        depths[i],
                        node.optString("cls"),
                        node.optString("pkg"),
                        line.trimStart(),
                        labelOf(node),
                        node.optBoolean("password", false),
                        node.optBoolean("editable", false),
                        node.optBoolean("clickable", false),
                    )
                )
            }
        }

        val truncated = tree.optBoolean("truncated", false)
        out.append(kept.size).append(" of ").append(total).append(" elements shown")
        if (total - kept.size > 0) {
            out.append("; ").append(total - kept.size).append(" had nothing to read or press")
        }
        if (truncated) out.append("; the screen was larger than the element limit")

        return Digest(
            treeId,
            windowPackage,
            windowTitle,
            out.toString(),
            kept,
            kept.size,
            total,
            truncated,
        )
    }

    /**
     * A node earns a line when it says something or does something.
     *
     * A layout container with no label and no action is structure the model
     * cannot act on and does not need to see.
     */
    private fun worthShowing(node: JSONObject): Boolean {
        if (node.optString("text").isNotEmpty()) return true
        if (node.optString("desc").isNotEmpty()) return true
        if (node.optString("hint").isNotEmpty()) return true
        if (node.optString("res_id").isNotEmpty()) return true
        if (node.optBoolean("clickable", false)) return true
        if (node.optBoolean("long_clickable", false)) return true
        if (node.optBoolean("editable", false)) return true
        if (node.optBoolean("scrollable", false)) return true
        if (!node.isNull("checked")) return true
        return false
    }

    /**
     * The name a person would use for this element.
     *
     * A password node has no text and no description in the tree, so it falls
     * through to its resource id or its class, which is what the confirm sheet
     * should say about it anyway.
     */
    private fun labelOf(node: JSONObject): String {
        val text = node.optString("text")
        if (text.isNotEmpty()) return InjectionFilter.sanitise(text, 64)
        val desc = node.optString("desc")
        if (desc.isNotEmpty()) return InjectionFilter.sanitise(desc, 64)
        val hint = node.optString("hint")
        if (hint.isNotEmpty()) return InjectionFilter.sanitise(hint, 64)
        val resId = node.optString("res_id")
        if (resId.isNotEmpty()) return resId.substringAfter("id/")
        return node.optString("cls")
    }

    private fun render(node: JSONObject, depth: Int): String {
        val sb = StringBuilder()
        repeat(minOf(depth, MAX_INDENT_DEPTH)) { sb.append("  ") }
        sb.append('n').append(node.optInt("id"))
        val cls = node.optString("cls")
        if (cls.isNotEmpty()) sb.append("  ").append(cls)

        // A password node carries no text, no description and no hint out of
        // the tree, and nothing is invented here to stand in for them.
        val text = node.optString("text")
        val desc = node.optString("desc")
        val hint = node.optString("hint")
        if (text.isNotEmpty()) sb.append(" \"").append(InjectionFilter.sanitise(text)).append('"')
        if (desc.isNotEmpty()) sb.append(" ~").append(InjectionFilter.sanitise(desc))
        if (text.isEmpty() && desc.isEmpty() && hint.isNotEmpty()) {
            sb.append(" ?").append(InjectionFilter.sanitise(hint))
        }

        val resId = node.optString("res_id")
        if (resId.isNotEmpty()) sb.append(" #").append(resId.substringAfter("id/"))

        val bounds = node.optJSONArray("bounds")
        if (bounds != null && bounds.length() == 4) {
            val x = (bounds.optInt(0) + bounds.optInt(2)) / 2
            val y = (bounds.optInt(1) + bounds.optInt(3)) / 2
            sb.append(" (").append(x).append(',').append(y).append(')')
        }

        val flags = ArrayList<String>(4)
        if (node.optBoolean("clickable", false)) flags.add("click")
        if (node.optBoolean("long_clickable", false)) flags.add("long")
        if (node.optBoolean("editable", false)) flags.add("edit")
        if (node.optBoolean("scrollable", false)) flags.add("scroll")
        if (!node.isNull("checked")) {
            flags.add(if (node.optBoolean("checked", false)) "checked" else "unchecked")
        }
        if (node.optBoolean("focused", false)) flags.add("focused")
        if (!node.optBoolean("enabled", true)) flags.add("disabled")
        // Said out loud so the model does not try, and so the transcript shows
        // a policy refusal rather than a mysterious device error when it does.
        if (node.optBoolean("password", false)) flags.add("password")
        if (flags.isNotEmpty()) sb.append(' ').append(flags.joinToString(" "))
        return sb.toString()
    }
}
