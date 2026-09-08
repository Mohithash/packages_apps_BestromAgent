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


package com.bestrom.agent.bridge

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Newline-delimited JSON framing.
 *
 * Exactly one JSON object per line, terminated by a single 0x0A. JSON escapes
 * every raw newline, so splitting on 0x0A is safe. The reader is bounded: it
 * fails at the byte cap instead of growing a buffer for whatever the peer sends.
 */
object Framing {

    const val MAX_REQUEST_LINE_BYTES = 1048576
    const val MAX_RESPONSE_LINE_BYTES = 8388608

    /** Thrown when a line runs past its cap. The connection is not recoverable. */
    class LineTooLongException(val limit: Int) :
        IOException("line exceeds $limit bytes")

    /**
     * Reads one line, without its terminator, decoded as UTF-8.
     *
     * Returns null at a clean end of stream. A trailing fragment with no 0x0A is
     * returned as the last line so a peer that closes without a newline is not
     * silently dropped.
     */
    @JvmStatic
    fun readLine(input: InputStream, limit: Int = MAX_REQUEST_LINE_BYTES): String? {
        var size = 0
        var buf = ByteArray(256)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (size == 0) return null
                return String(buf, 0, size, Charsets.UTF_8)
            }
            if (b == 0x0A) {
                return String(buf, 0, size, Charsets.UTF_8)
            }
            if (size == limit) throw LineTooLongException(limit)
            if (size == buf.size) {
                val want = minOf(buf.size * 2, limit)
                buf = buf.copyOf(want)
            }
            buf[size++] = b.toByte()
        }
    }

    /** Writes one line plus its single 0x0A terminator. */
    @JvmStatic
    fun writeLine(output: OutputStream, line: String, limit: Int = MAX_RESPONSE_LINE_BYTES) {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size > limit) throw LineTooLongException(limit)
        synchronized(output) {
            output.write(bytes)
            output.write(0x0A)
            output.flush()
        }
    }

    /** Reads a line and refuses a clean end of stream. */
    @JvmStatic
    fun readLineOrThrow(input: InputStream, limit: Int = MAX_REQUEST_LINE_BYTES): String =
        readLine(input, limit) ?: throw EOFException()
}
