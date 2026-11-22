package com.mojtaba.simcallrecorder

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.mojtaba.simcallrecorder.domain.model.RecordingMode
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores only consent acknowledgements and the last UI arm request.
 *
 * Values are AES-GCM encrypted with a non-exportable Android Keystore key. The arm bit is a
 * recovery hint, not an instruction to restart monitoring: a new process always requires a
 * fresh visible user action. Missing, corrupt, or undecryptable state fails closed.
 */
internal class SecureRecordingStateStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun read(): State = runCatching {
        val encoded = preferences.getString(ENCRYPTED_STATE_KEY, null) ?: return State()
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        require(payload.size > GCM_IV_BYTES)
        val iv = payload.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = payload.copyOfRange(GCM_IV_BYTES, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, loadOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val decoded = ByteBuffer.wrap(cipher.doFinal(ciphertext))
        require(decoded.remaining() == STATE_BYTES && decoded.get().toInt() == STATE_VERSION)
        State(
            speakerphoneConsentConfirmed = decoded.get().toInt() == 1,
            shizukuConsentConfirmed = decoded.get().toInt() == 1,
            armRequested = decoded.get().toInt() == 1,
        )
    }.getOrDefault(State())

    @Synchronized
    fun setConsent(mode: RecordingMode, confirmed: Boolean) {
        val current = read()
        write(
            when (mode) {
                RecordingMode.SPEAKERPHONE_MIC -> current.copy(speakerphoneConsentConfirmed = confirmed)
                RecordingMode.SHIZUKU_VOICE_CALL -> current.copy(shizukuConsentConfirmed = confirmed)
                else -> current
            },
        )
    }

    @Synchronized
    fun setArmRequested(requested: Boolean) {
        write(read().copy(armRequested = requested))
    }

    private fun write(state: State) {
        val plaintext = ByteBuffer.allocate(STATE_BYTES)
            .put(STATE_VERSION.toByte())
            .put((if (state.speakerphoneConsentConfirmed) 1 else 0).toByte())
            .put((if (state.shizukuConsentConfirmed) 1 else 0).toByte())
            .put((if (state.armRequested) 1 else 0).toByte())
            .array()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val payload = cipher.iv + cipher.doFinal(plaintext)
        check(
            preferences.edit()
                .putString(ENCRYPTED_STATE_KEY, Base64.encodeToString(payload, Base64.NO_WRAP))
                .commit(),
        ) { "Could not persist encrypted recording consent state." }
    }

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    internal data class State(
        val speakerphoneConsentConfirmed: Boolean = false,
        val shizukuConsentConfirmed: Boolean = false,
        val armRequested: Boolean = false,
    )

    private companion object {
        const val PREFERENCES_NAME = "secure_recording_state"
        const val ENCRYPTED_STATE_KEY = "ciphertext_v1"
        const val KEY_ALIAS = "sim_call_recorder_consent_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val STATE_VERSION = 1
        const val STATE_BYTES = 4
    }
}
