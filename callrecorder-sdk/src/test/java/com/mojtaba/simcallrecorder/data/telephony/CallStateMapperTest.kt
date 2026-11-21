package com.mojtaba.simcallrecorder.data.telephony

import android.telephony.TelephonyManager
import com.mojtaba.simcallrecorder.domain.model.CallState
import org.junit.Assert.assertEquals
import org.junit.Test

class CallStateMapperTest {
    @Test
    fun mapsIdleState() {
        assertEquals(
            CallState.IDLE,
            mapTelephonyCallState(TelephonyManager.CALL_STATE_IDLE),
        )
    }

    @Test
    fun mapsRingingState() {
        assertEquals(
            CallState.RINGING,
            mapTelephonyCallState(TelephonyManager.CALL_STATE_RINGING),
        )
    }

    @Test
    fun mapsOffhookState() {
        assertEquals(
            CallState.OFFHOOK,
            mapTelephonyCallState(TelephonyManager.CALL_STATE_OFFHOOK),
        )
    }

    @Test
    fun mapsUnknownStateSafely() {
        assertEquals(CallState.UNKNOWN, mapTelephonyCallState(-1))
    }
}
