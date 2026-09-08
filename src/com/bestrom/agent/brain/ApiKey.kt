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
 * What looking the key up found.
 *
 * Three answers rather than a nullable string, because they need three
 * different sentences: no key was ever set, a key is stored and could not be
 * unsealed, and here is the key. Collapsing the middle one into "no key" sends
 * a placeholder to a real provider and reports the 401 as a bad key.
 *
 * Kept apart from [ApiKeyStore] so the client can act on it without the
 * keystore, which is also what makes it testable on the host.
 */
sealed class ApiKey {

    class Present(val value: String) : ApiKey()

    /** Nothing is stored. */
    object Absent : ApiKey()

    /** Something is stored and this device cannot open it right now. */
    object Unavailable : ApiKey()
}
