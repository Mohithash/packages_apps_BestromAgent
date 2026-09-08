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

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bestrom.agent.AgentState
import com.bestrom.agent.toggle.AgentToggle

/**
 * The accessibility half of Agent mode.
 *
 * It is bound only while the switch is on. Because setComponentEnabledSetting and
 * the secure setting are both persistent, a phone that reboots with the switch
 * left on would otherwise bind this service with no bridge behind it. So the
 * first thing onServiceConnected does is look at AgentState.bridgeLive: on a
 * fresh boot it is false, and the service takes itself back out of the secure
 * setting, disables both components and unbinds.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "BestromAgent"
        const val INTERACTION_WINDOW_MS = 1500L
    }

    private val lock = Any()
    private var treeId: String? = null
    private var treePackage: String? = null
    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() {
        super.onServiceConnected()
        if (!AgentState.bridgeLive.get()) {
            // No bridge: this is the first boot after the switch was left on.
            Log.i(TAG, "Accessibility bound with no bridge; undoing Agent mode")
            AgentToggle(applicationContext).reconcileOff()
            disableSelf()
            return
        }
        AgentState.a11y = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_START,
            AccessibilityEvent.TYPE_TOUCH_INTERACTION_END,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_GESTURE_DETECTION_START ->
                AgentState.lastTouchMs.set(SystemClock.uptimeMillis())

            // A node id addresses a node in the tree that was walked. Once the
            // window has changed under it the ids mean nothing, so the tree is
            // dropped here and the next node-addressed action gets STALE_TREE
            // instead of landing on whatever now occupies that slot.
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> forgetTree()

            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val pkg = event.packageName?.toString()
                if (pkg != null && pkg != snapshotPackage()) forgetTree()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        forgetTree()
        if (AgentState.a11y === this) AgentState.a11y = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        forgetTree()
        if (AgentState.a11y === this) AgentState.a11y = null
        super.onDestroy()
    }

    /** True when the user touched the screen inside the guard window. */
    fun userInteracting(nowMs: Long = SystemClock.uptimeMillis()): Boolean {
        val last = AgentState.lastTouchMs.get()
        return last != 0L && nowMs - last < INTERACTION_WINDOW_MS
    }

    /**
     * The root of the active window, or null.
     *
     * Null means there is no active window right now - between two activities,
     * or while the window is being torn down. It does not mean the window was
     * withheld: FLAG_SECURE governs screen capture only, and the tree of an app
     * that blocks screenshots is handed over like any other.
     */
    fun activeRoot(): AccessibilityNodeInfo? = rootInActiveWindow

    /** The package that owns the active window, or null when there is none. */
    fun activeWindowPackage(): String? = rootInActiveWindow?.packageName?.toString()

    /** The display bounds, used as the coordinate space for gestures. */
    fun displayBounds(): Rect {
        val wm = getSystemService(WindowManager::class.java)
        return Rect(wm.currentWindowMetrics.bounds)
    }

    /** Walks the active window and remembers the result under a fresh tree_id. */
    fun snapshot(maxDepth: Int, maxNodes: Int): TreeSerializer.Snapshot? {
        val root = activeRoot() ?: return null
        val collected = TreeSerializer.collect(root, maxDepth, maxNodes)
        val id = TreeSerializer.newTreeId()

        val window = root.window
        val bounds = Rect()
        if (window != null) {
            window.getBoundsInScreen(bounds)
        } else {
            bounds.set(displayBounds())
        }
        val json =
            TreeSerializer.encode(
                id,
                root.packageName?.toString().orEmpty(),
                window?.title?.toString(),
                intArrayOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
                collected.facts,
                collected.truncated,
            )

        synchronized(lock) {
            treeId = id
            treePackage = root.packageName?.toString()
            nodes = collected.nodes
        }
        return TreeSerializer.Snapshot(id, json, collected.nodes)
    }

    /** The id of the most recent tree, or null when none has been taken. */
    fun currentTreeId(): String? = synchronized(lock) { treeId }

    /** The package the most recent tree was walked from, or null. */
    fun snapshotPackage(): String? = synchronized(lock) { treePackage }

    /** A node from the most recent tree; null when the id is stale or unknown. */
    fun nodeAt(requestedTreeId: String, nodeId: Int): AccessibilityNodeInfo? =
        synchronized(lock) {
            if (treeId != requestedTreeId) return null
            nodes.getOrNull(nodeId)
        }

    /** The editable node that currently has input focus, if any. */
    fun focusedEditable(): AccessibilityNodeInfo? {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (focused.isEditable) focused else null
    }

    private fun forgetTree() {
        synchronized(lock) {
            treeId = null
            treePackage = null
            nodes = emptyList()
        }
    }
}
