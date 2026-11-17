package com.mojtaba.simcallrecorder.data.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidAudioRouteDataSource @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val audioManager =
        context.applicationContext.getSystemService(AudioManager::class.java)

    fun getRoutes(): List<AudioRoute> {
        val manager = audioManager ?: return emptyList()
        val communicationDeviceId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.communicationDevice?.id
        } else {
            null
        }

        return manager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
            .map { device ->
                AudioRoute(
                    direction = when {
                        device.isSource && device.isSink -> "Input / Output"
                        device.isSource -> "Input"
                        device.isSink -> "Output"
                        else -> "Unknown direction"
                    },
                    deviceType = device.type.toDisplayType(),
                    displayName = device.productName.toString()
                        .ifBlank { device.type.toDisplayType() },
                    selectedForCommunication = device.id == communicationDeviceId,
                )
            }
            .sortedWith(compareBy<AudioRoute>({ it.direction }, { it.deviceType }, { it.displayName }))
    }
}

private fun Int.toDisplayType(): String = when (this) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Built-in earpiece"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
    AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "Bluetooth LE speaker"
    AudioDeviceInfo.TYPE_HEARING_AID -> "Hearing aid"
    AudioDeviceInfo.TYPE_HDMI -> "HDMI"
    AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line"
    AudioDeviceInfo.TYPE_LINE_DIGITAL -> "Digital line"
    AudioDeviceInfo.TYPE_DOCK -> "Dock"
    else -> "Audio device ($this)"
}
