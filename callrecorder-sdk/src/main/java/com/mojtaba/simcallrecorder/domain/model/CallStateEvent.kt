package com.mojtaba.simcallrecorder.domain.model

data class CallStateEvent(
    val simSlotIndex: Int,
    val state: CallState,
    val observedAtEpochMillis: Long,
)
