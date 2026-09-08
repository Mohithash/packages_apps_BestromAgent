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


package com.bestrom.agent.a11y

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.security.SecureRandom
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the active window into compact JSON.
 *
 * Node ids are indices into this snapshot and nothing else: they are valid only
 * for the tree_id they came back with. A node whose isPassword() is true is
 * serialised without its text, always.
 */
object TreeSerializer {

    const val DEFAULT_MAX_DEPTH = 25
    const val DEFAULT_MAX_NODES = 800

    private val random = SecureRandom()

    /** One node, flattened away from AccessibilityNodeInfo. */
    class NodeFacts(
        val cls: String,
        val pkg: String,
        val text: String?,
        val desc: String?,
        val hint: String?,
        val resId: String?,
        val bounds: IntArray,
        val clickable: Boolean,
        val longClickable: Boolean,
        val editable: Boolean,
        val scrollable: Boolean,
        val focused: Boolean,
        val checkable: Boolean,
        val checked: Boolean,
        val enabled: Boolean,
        val password: Boolean,
        val children: MutableList<Int> = ArrayList(),
    )

    /** What a walk produced: the flattened facts, the live nodes, the cap flag. */
    class Collected(
        val facts: List<NodeFacts>,
        val nodes: List<AccessibilityNodeInfo>,
        val truncated: Boolean,
    )

    /** The snapshot handed back to the bridge and kept for the next action. */
    class Snapshot(
        val treeId: String,
        val json: JSONObject,
        val nodes: List<AccessibilityNodeInfo>,
    )

    fun newTreeId(): String {
        val bytes = ByteArray(8)
        random.nextBytes(bytes)
        val sb = StringBuilder(16)
        for (b in bytes) sb.append(String.format("%02x", b.toInt() and 0xFF))
        return sb.toString()
    }

    /**
     * Walks [root] depth first, collecting at most [maxNodes] nodes no deeper
     * than [maxDepth].
     */
    fun collect(
        root: AccessibilityNodeInfo,
        maxDepth: Int,
        maxNodes: Int,
        includeInvisible: Boolean,
    ): Collected {
        val facts = ArrayList<NodeFacts>()
        val nodes = ArrayList<AccessibilityNodeInfo>()
        var truncated = false

        fun visit(node: AccessibilityNodeInfo, depth: Int): Int {
            if (facts.size >= maxNodes) {
                truncated = true
                return -1
            }
            val index = facts.size
            facts.add(factsOf(node))
            nodes.add(node)
            if (depth >= maxDepth) return index
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                if (!includeInvisible && !child.isVisibleToUser) continue
                val childIndex = visit(child, depth + 1)
                if (childIndex < 0) break
                facts[index].children.add(childIndex)
            }
            return index
        }

        visit(root, 0)
        return Collected(facts, nodes, truncated)
    }

    private fun factsOf(node: AccessibilityNodeInfo): NodeFacts {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        val cls = node.className?.toString().orEmpty()
        return NodeFacts(
            cls = cls.substringAfterLast('.'),
            pkg = node.packageName?.toString().orEmpty(),
            text = node.text?.toString(),
            desc = node.contentDescription?.toString(),
            hint = node.hintText?.toString(),
            resId = node.viewIdResourceName,
            bounds = intArrayOf(rect.left, rect.top, rect.right, rect.bottom),
            clickable = node.isClickable,
            longClickable = node.isLongClickable,
            editable = node.isEditable,
            scrollable = node.isScrollable,
            focused = node.isFocused,
            checkable = node.isCheckable,
            checked = node.isChecked,
            enabled = node.isEnabled,
            password = node.isPassword,
        )
    }

    /**
     * Encodes a collected tree. Pure: no Android state is read here, which is
     * what lets the password rule and the caps be checked away from a device.
     */
    fun encode(
        treeId: String,
        windowPackage: String,
        windowTitle: String?,
        windowBounds: IntArray,
        facts: List<NodeFacts>,
        truncated: Boolean,
    ): JSONObject {
        val nodes = JSONArray()
        for ((i, f) in facts.withIndex()) {
            val o = JSONObject()
            o.put("id", i)
            o.put("cls", f.cls)
            o.put("pkg", f.pkg)
            // A password field never carries its text out of the phone.
            if (!f.password && !f.text.isNullOrEmpty()) o.put("text", f.text)
            if (!f.desc.isNullOrEmpty()) o.put("desc", f.desc)
            if (!f.password && !f.hint.isNullOrEmpty()) o.put("hint", f.hint)
            if (!f.resId.isNullOrEmpty()) o.put("res_id", f.resId)
            val b = JSONArray()
            for (v in f.bounds) b.put(v)
            o.put("bounds", b)
            o.put("clickable", f.clickable)
            o.put("long_clickable", f.longClickable)
            o.put("editable", f.editable)
            o.put("scrollable", f.scrollable)
            o.put("focused", f.focused)
            o.put("checked", if (f.checkable) f.checked else JSONObject.NULL)
            o.put("enabled", f.enabled)
            o.put("password", f.password)
            if (f.children.isNotEmpty()) {
                val c = JSONArray()
                for (child in f.children) c.put(child)
                o.put("children", c)
            }
            nodes.put(o)
        }

        val window = JSONObject()
        window.put("package", windowPackage)
        window.put("title", windowTitle ?: JSONObject.NULL)
        val wb = JSONArray()
        for (v in windowBounds) wb.put(v)
        window.put("bounds", wb)

        return JSONObject()
            .put("tree_id", treeId)
            .put("window", window)
            .put("nodes", nodes)
            .put("truncated", truncated)
            .put("node_count", facts.size)
    }
}
