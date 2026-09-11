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
import com.bestrom.agent.runner.PendingChoice

/** Lets the user pick one option the agent offered (delivery mode, etc.). */
object ChoiceSheet {

    fun show(activity: Activity, pending: PendingChoice): AlertDialog {
        val options = pending.options.toTypedArray()
        val dialog =
            AlertDialog.Builder(activity)
                .setTitle(R.string.choice_title)
                .setMessage(pending.prompt)
                .setCancelable(false)
                .setItems(options) { _, which ->
                    pending.answer(options.getOrNull(which))
                }
                .setNegativeButton(R.string.choice_cancel) { _, _ ->
                    pending.answer(null)
                }
                .create()
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.decorView?.filterTouchesWhenObscured = true
        return dialog
    }
}
