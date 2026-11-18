package com.mojtaba.simcallrecorder.data.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class AudioDeviceSnapshot(
    val id: Int,
    val type: Int,
    val isSource: Boolean,
    val isSink: Boolean,
    val productName: String,
)

data class AudioDeviceInventory(
    val devices: List<AudioDeviceSnapshot>,
    val selectedCommunicationDeviceId: Int?,
)

fun interface AudioDeviceInventorySource {
    fun getInventory(): AudioDeviceInventory
}

@Singleton
class AndroidAudioDeviceInventorySource @Inject constructor(
    @ApplicationContext context: Context,
) : AudioDeviceInventorySource {
    private val audioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    override fun getInventory(): AudioDeviceInventory {
        val manager = audioManager ?: return AudioDeviceInventory(emptyList(), null)
        val selectedId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { manager.communicationDevice?.id }.getOrNull()
        } else {
            null
        }
        val devices = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
                .map { device ->
                    AudioDeviceSnapshot(
                        id = device.id,
                        type = device.type,
                        isSource = device.isSource,
                        isSink = device.isSink,
                        productName = device.productName.toString(),
                    )
                }
        }.getOrDefault(emptyList())
        return AudioDeviceInventory(devices, selectedId)
    }
}
