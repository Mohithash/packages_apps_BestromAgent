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

/** ChatGPT-style bubbles: user right, assistant left (markdown), tool lines muted. */
class ChatAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_USER = 0
        private const val TYPE_ASSISTANT = 1
        private const val TYPE_TOOL = 2
    }

    private var items: List<ChatStore.Message> = emptyList()

    fun submit(newItems: List<ChatStore.Message>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int =
        when (items[position].role) {
            ChatStore.Role.USER -> TYPE_USER
            ChatStore.Role.ASSISTANT -> TYPE_ASSISTANT
            ChatStore.Role.TOOL -> TYPE_TOOL
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_USER ->
                BubbleHolder(inflater.inflate(R.layout.item_chat_user, parent, false))
            TYPE_ASSISTANT ->
                BubbleHolder(inflater.inflate(R.layout.item_chat_assistant, parent, false))
            else -> ToolHolder(inflater.inflate(R.layout.item_chat_tool, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = items[position]
        when (holder) {
            is BubbleHolder -> {
                if (msg.role == ChatStore.Role.ASSISTANT) {
                    holder.text.text = MarkdownRender.toSpannable(holder.text.context, msg.text)
                } else {
                    holder.text.text = msg.text
                }
            }
            is ToolHolder -> {
                holder.label.text = msg.label.ifEmpty { "·" }
                holder.body.text = msg.text
            }
        }
    }

    override fun getItemCount(): Int = items.size

    private class BubbleHolder(view: View) : RecyclerView.ViewHolder(view) {
        val text: TextView = view.findViewById(R.id.bubble_text)
    }

    private class ToolHolder(view: View) : RecyclerView.ViewHolder(view) {
        val label: TextView = view.findViewById(R.id.tool_label)
        val body: TextView = view.findViewById(R.id.tool_text)
    }
}
