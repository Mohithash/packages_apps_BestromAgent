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
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The acting half: node actions first, gestures as the fallback.
 *
 * Every entry point here is called from a bridge worker thread and blocks until
 * the platform has answered, so the JSON-RPC reply reports what actually
 * happened rather than what was requested.
 */
object Actions {

    private const val GESTURE_TIMEOUT_MS = 5000L
    private const val SCREENSHOT_TIMEOUT_MS = 10000L

    /** A named global action, or null when the caller invented a name. */
    fun globalActionFor(name: String): Int? =
        when (name) {
            "back" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "lock_screen" -> AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
            "power_dialog" -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            "dismiss_notification_shade" ->
                AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE
            else -> null
        }

    fun performGlobal(service: AccessibilityService, action: Int): Boolean =
        service.performGlobalAction(action)

    /** ACTION_CLICK on the node; false when the node refused it. */
    fun clickNode(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)

    fun longClickNode(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)

    /**
     * ACTION_SET_TEXT. The caller has already refused password fields; this is
     * the second place that rule is enforced.
     */
    fun setText(node: AccessibilityNodeInfo, text: String, replace: Boolean): Boolean {
        if (node.isPassword) return false
        val value =
            if (replace) text else (node.text?.toString().orEmpty() + text)
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            value,
        )
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** A single-point stroke: a tap. */
    fun tapGesture(service: AccessibilityService, x: Int, y: Int): Boolean =
        stroke(service, floatArrayOf(x.toFloat(), y.toFloat()), null, 50)

    fun longPressGesture(
        service: AccessibilityService,
        x: Int,
        y: Int,
        durationMs: Int,
    ): Boolean = stroke(service, floatArrayOf(x.toFloat(), y.toFloat()), null, durationMs)

    fun swipeGesture(
        service: AccessibilityService,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
        durationMs: Int,
    ): Boolean =
        stroke(
            service,
            floatArrayOf(fromX.toFloat(), fromY.toFloat()),
            floatArrayOf(toX.toFloat(), toY.toFloat()),
            durationMs,
        )

    private fun stroke(
        service: AccessibilityService,
        from: FloatArray,
        to: FloatArray?,
        durationMs: Int,
    ): Boolean {
        val path = Path()
        path.moveTo(from[0], from[1])
        if (to != null) {
            path.lineTo(to[0], to[1])
        } else {
            // A zero-length path is rejected; one pixel reads as a point press.
            path.lineTo(from[0], from[1] + 1f)
        }
        val gesture =
            GestureDescription.Builder()
                .addStroke(
                    GestureDescription.StrokeDescription(path, 0, durationMs.toLong())
                )
                .build()

        val latch = CountDownLatch(1)
        val completed = AtomicReference(false)
        val dispatched =
            service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(description: GestureDescription?) {
                        completed.set(true)
                        latch.countDown()
                    }

                    override fun onCancelled(description: GestureDescription?) {
                        completed.set(false)
                        latch.countDown()
                    }
                },
                null,
            )
        if (!dispatched) return false
        val finished = latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        return finished && completed.get()
    }

    /** What a screenshot attempt produced. */
    class ScreenshotOutcome(
        val png: ByteArray?,
        val width: Int,
        val height: Int,
        val errorCode: Int,
    )

    /**
     * Takes a screenshot of the default display and compresses it to PNG.
     *
     * The platform enforces its own minimum interval; its failure is reported
     * rather than retried.
     */
    fun screenshot(service: AccessibilityService, executor: Executor): ScreenshotOutcome {
        val latch = CountDownLatch(1)
        val error = AtomicInteger(0)
        val holder = AtomicReference<Bitmap?>(null)

        service.takeScreenshot(
            Display.DEFAULT_DISPLAY,
            executor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    try {
                        val bitmap = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        holder.set(bitmap?.copy(Bitmap.Config.ARGB_8888, false))
                        if (bitmap == null) {
                            error.set(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
                        }
                    } catch (e: Exception) {
                        error.set(AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR)
                    } finally {
                        buffer.close()
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    error.set(errorCode)
                    latch.countDown()
                }
            },
        )

        if (!latch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            return ScreenshotOutcome(
                null,
                0,
                0,
                AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
            )
        }
        val bitmap =
            holder.get()
                ?: return ScreenshotOutcome(
                    null,
                    0,
                    0,
                    if (error.get() != 0) error.get()
                    else AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR,
                )
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        val outcome = ScreenshotOutcome(out.toByteArray(), bitmap.width, bitmap.height, 0)
        bitmap.recycle()
        return outcome
    }
}
