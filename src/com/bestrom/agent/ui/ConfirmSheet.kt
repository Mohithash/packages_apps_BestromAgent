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

import android.app.Activity
import android.app.AlertDialog
import com.bestrom.agent.R
import com.bestrom.agent.runner.PendingConfirm

/**
 * "Allow this action?"
 *
 * A dialog in the agent's own activity, never an overlay over another app: an
 * agent that can draw over everything is the shape of the attack, not the
 * defence. It cannot be dismissed by tapping outside or by Back, because a
 * dismissal that is not an answer would leave the runner waiting on a sheet
 * nobody can see.
 *
 * Allow all for this task is missing on a payment or credential app. That tier
 * asks every time, and a blanket button on that sheet would be a tap the user
 * aimed at something else.
 */
object ConfirmSheet {

    fun show(activity: Activity, pending: PendingConfirm): AlertDialog {
        val message = StringBuilder(pending.what)
        if (pending.target.isNotEmpty()) message.append('\n').append(pending.target)
        if (pending.sensitive) {
            message.append("\n\n").append(activity.getString(R.string.confirm_sensitive))
        }

        val builder =
            AlertDialog.Builder(activity)
                .setTitle(R.string.confirm_title)
                .setMessage(message.toString())
                .setCancelable(false)
                .setNegativeButton(R.string.confirm_deny) { _, _ ->
                    pending.answer(PendingConfirm.Answer.DENY)
                }
                .setPositiveButton(R.string.confirm_allow) { _, _ ->
                    pending.answer(PendingConfirm.Answer.ALLOW)
                }
        if (!pending.sensitive) {
            builder.setNeutralButton(R.string.confirm_allow_all) { _, _ ->
                pending.answer(PendingConfirm.Answer.ALLOW_ALL)
            }
        }
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        // The switch this sheet stands in front of is exactly the tap an
        // overlay would want to steal.
        dialog.window?.decorView?.filterTouchesWhenObscured = true
        return dialog
    }
}
