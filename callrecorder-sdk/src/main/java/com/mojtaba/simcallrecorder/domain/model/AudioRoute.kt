package com.mojtaba.simcallrecorder.domain.model

data class AudioRoute(
    val direction: String,
    val deviceType: String,
    val displayName: String,
    val selectedForCommunication: Boolean,
)
