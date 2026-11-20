package com.mojtaba.simcallrecorder

import com.mojtaba.simcallrecorder.domain.model.RecordingMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecureRecordingStateStoreInstrumentedTest {
    @Test
    fun consentAndArmMarkerPersistEncryptedAcrossStoreInstances() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("secure_recording_state", 0)
        preferences.edit().clear().commit()

        try {
            SecureRecordingStateStore(context).apply {
                setConsent(RecordingMode.SPEAKERPHONE_MIC, true)
                setConsent(RecordingMode.SHIZUKU_VOICE_CALL, false)
                setArmRequested(true)
            }

            val ciphertext = preferences.getString("ciphertext_v1", null)
            assertTrue(ciphertext.isNullOrBlank().not())
            assertFalse("Stored state must not be readable plaintext.", ciphertext!!.contains("true"))

            val restored = SecureRecordingStateStore(context).read()
            assertTrue(restored.speakerphoneConsentConfirmed)
            assertFalse(restored.shizukuConsentConfirmed)
            assertTrue(restored.armRequested)

            // Process startup consumes the stale arm marker without restoring active monitoring.
            SecureRecordingStateStore(context).setArmRequested(false)
            assertFalse(SecureRecordingStateStore(context).read().armRequested)
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
