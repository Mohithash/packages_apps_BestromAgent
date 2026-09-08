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

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The API key at rest.
 *
 * An AndroidKeyStore AES-256/GCM key, created with setUnlockedDeviceRequired,
 * seals the key into the app's own files directory. The runner already refuses
 * to act while the keyguard is showing, so binding the key to an unlocked
 * device costs the design nothing and means a data partition taken without the
 * user's credential yields a ciphertext.
 *
 * Every failure returns null or false rather than throwing: a wiped keystore
 * reads as "not set" instead of crashing the screen that would let the user fix
 * it. Nothing here logs, and nothing here ever hands the value back to anything
 * but the one client that makes the request.
 */
object ApiKeyStore {

    const val ALIAS = "bestrom_agent_brain_key"
    const val FILE = "brain.key"

    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    /**
     * The on-disk shape: a 12 byte GCM nonce followed by the GCM output.
     *
     * Kept apart from the keystore calls so the framing can be reasoned about
     * without a device.
     */
    object Envelope {
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        /** The smallest file that could hold anything: nonce plus a bare tag. */
        const val MIN_BYTES = IV_BYTES + TAG_BITS / 8

        fun wrap(iv: ByteArray, ciphertext: ByteArray): ByteArray {
            val out = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, out, 0, iv.size)
            System.arraycopy(ciphertext, 0, out, iv.size, ciphertext.size)
            return out
        }

        fun iv(blob: ByteArray): ByteArray? {
            if (blob.size < MIN_BYTES) return null
            return blob.copyOfRange(0, IV_BYTES)
        }

        fun ciphertext(blob: ByteArray): ByteArray? {
            if (blob.size < MIN_BYTES) return null
            return blob.copyOfRange(IV_BYTES, blob.size)
        }
    }

    fun isSet(context: Context): Boolean = file(context).length() >= Envelope.MIN_BYTES

    /** Replaces whatever was stored. An empty key clears instead. */
    fun put(context: Context, key: String): Boolean {
        if (key.isEmpty()) {
            clear(context)
            return true
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val ciphertext = cipher.doFinal(key.toByteArray(Charsets.UTF_8))
            val blob = Envelope.wrap(cipher.iv, ciphertext)
            val target = file(context)
            target.writeBytes(blob)
            target.setReadable(false, false)
            target.setReadable(true, true)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** The key, or null when there is none and when anything at all went wrong. */
    fun get(context: Context): String? =
        try {
            val target = file(context)
            if (!target.exists()) {
                null
            } else {
                val blob = target.readBytes()
                val iv = Envelope.iv(blob)
                val ciphertext = Envelope.ciphertext(blob)
                if (iv == null || ciphertext == null) {
                    null
                } else {
                    val cipher = Cipher.getInstance(TRANSFORM)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        existingKey(),
                        GCMParameterSpec(Envelope.TAG_BITS, iv),
                    )
                    String(cipher.doFinal(ciphertext), Charsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            null
        }

    /** Removes the ciphertext and the keystore entry it was sealed to. */
    fun clear(context: Context) {
        try {
            file(context).delete()
        } catch (e: Exception) {
            // Nothing further to do; the entry goes next.
        }
        try {
            keystore()?.deleteEntry(ALIAS)
        } catch (e: Exception) {
            // A keystore that will not delete leaves a key with no ciphertext.
        }
    }

    private fun file(context: Context): File = File(context.filesDir, FILE)

    private fun keystore(): KeyStore? =
        try {
            KeyStore.getInstance(PROVIDER).apply { load(null) }
        } catch (e: Exception) {
            null
        }

    private fun existingKey(): SecretKey? =
        try {
            keystore()?.getKey(ALIAS, null) as? SecretKey
        } catch (e: Exception) {
            null
        }

    private fun secretKey(): SecretKey {
        val existing = existingKey()
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // A nonce the caller supplies would let two messages share one,
                // which is the way to lose a GCM key.
                .setRandomizedEncryptionRequired(true)
                // There is no legitimate locked-screen path: the runner refuses
                // to act while the keyguard is up, so the key need not work
                // there either.
                .setUnlockedDeviceRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}
