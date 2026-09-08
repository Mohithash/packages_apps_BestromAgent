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
import com.bestrom.agent.runner.StepEvent

/**
 * One line per step, oldest at the top.
 *
 * The line names what happened and to what. It never carries the raw tree, the
 * request, or anything the agent typed - a type step says how many characters
 * went into which field, which is exactly what the audit log records.
 */
class TranscriptAdapter : RecyclerView.Adapter<TranscriptAdapter.Holder>() {

    private var events: List<StepEvent> = emptyList()

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val step: TextView = view.findViewById(R.id.step_index)
        val text: TextView = view.findViewById(R.id.step_text)
    }

    fun submit(newEvents: List<StepEvent>) {
        events = newEvents
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_step, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val event = events[position]
        holder.step.text = if (event.step > 0) event.step.toString() else "-"
        holder.text.text = event.text
        // Monochrome: weight is the only difference between a step and its
        // outcome, because a colour here would be the only one on the screen.
        val emphasise =
            event.kind == StepEvent.Kind.ACTION ||
                event.kind == StepEvent.Kind.DONE ||
                event.kind == StepEvent.Kind.CONFIRM
        holder.text.alpha = if (emphasise) 1.0f else 0.7f
        // The step number is a separate view, so a screen reader would read it
        // apart from its line. This puts them back together.
        holder.itemView.contentDescription = event.line()
    }

    override fun getItemCount(): Int = events.size
}
