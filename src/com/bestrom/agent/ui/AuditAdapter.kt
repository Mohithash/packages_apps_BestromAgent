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

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bestrom.agent.R
import com.bestrom.agent.audit.AuditLog

/** Renders the audit log newest first, one line per request. */
class AuditAdapter : RecyclerView.Adapter<AuditAdapter.Holder>() {

    private var entries: List<AuditLog.Entry> = emptyList()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val time: TextView = view.findViewById(R.id.entry_time)
        val text: TextView = view.findViewById(R.id.entry_text)
        val result: TextView = view.findViewById(R.id.entry_result)
    }

    fun submit(newEntries: List<AuditLog.Entry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view =
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_audit_entry, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val entry = entries[position]
        // Times are stored in UTC; the list shows the clock part only.
        holder.time.text = entry.tsUtc.substringAfter('T').removeSuffix("Z")
        holder.text.text =
            if (entry.target.isEmpty()) entry.method else entry.method + "  " + entry.target
        holder.result.text =
            if (entry.errorCode == null) entry.result else entry.result + " " + entry.errorCode
    }

    override fun getItemCount(): Int = entries.size
}
