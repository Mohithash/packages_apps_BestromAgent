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


package com.bestrom.agent.brain

/**
 * Why a model call did not produce an answer.
 *
 * Each carries the one sentence the transcript shows. None of them carries the
 * request, the response body or the key: providers put request identifiers and
 * sometimes echoed parameters in an error body, so the body is never rendered.
 */
sealed class BrainError(val sentence: String) {

    /** 401 or 403. Never retried; a bad key does not get better. */
    object Unauthorized : BrainError("The brain rejected the key.")

    /** 429, after the attempts allowed by the backoff are spent. */
    class RateLimited(val retryAfterMs: Long) :
        BrainError("The brain is rate limiting; try again in a minute.")

    /** 500, 502, 503 or Anthropic's 529, after three attempts. */
    object Overloaded : BrainError("The brain is busy; try again in a minute.")

    /**
     * Nothing was reached: DNS, connect, TLS, a read timeout, or a base URL
     * this client refuses to speak plain http to.
     */
    class Network(val why: String, sentence: String = "The brain could not be reached.") :
        BrainError(sentence)

    /** A 200 whose body is not a chat completion - an HTML error page, usually. */
    class BadResponse(val why: String) :
        BrainError("The brain sent something that is not a chat completion.")

    /**
     * A 4xx that is neither auth nor rate limiting: a wrong model id, or a
     * field this endpoint rejects. The provider's own message is shown,
     * truncated, because it is the only useful thing in the body.
     */
    class Rejected(val status: Int, val detail: String) :
        BrainError(
            if (detail.isEmpty()) "The brain refused the request ($status)."
            else "The brain refused the request ($status): $detail"
        )

    /** Stop was pressed while the call was in flight. */
    object Cancelled : BrainError("Stopped.")

    companion object {
        /** How much of a provider's error message is ever shown. */
        const val MAX_DETAIL = 200
    }
}
