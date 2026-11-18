package com.mojtaba.simcallrecorder.data.audio

import android.media.AudioDeviceInfo
import javax.inject.Inject
import javax.inject.Singleton
import com.mojtaba.simcallrecorder.domain.model.AudioRoute

@Singleton
class AndroidAudioRouteDataSource @Inject constructor(
    private val inventorySource: AudioDeviceInventorySource,
) {
    fun getRoutes(): List<AudioRoute> {
        val inventory = inventorySource.getInventory()
        return inventory.devices.map { device ->
            val displayType = device.type.toDisplayType()
            AudioRoute(
                direction = when {
                    device.isSource && device.isSink -> "Input / Output"
                    device.isSource -> "Input"
                    device.isSink -> "Output"
                    else -> "Unknown direction"
                },
                deviceType = displayType,
                displayName = device.productName.ifBlank { displayType },
                selectedForCommunication = device.id == inventory.selectedCommunicationDeviceId,
            )
        }.sortedWith(compareBy<AudioRoute>({ it.direction }, { it.deviceType }, { it.displayName }))
    }
}

internal fun Int.toDisplayType(): String = when (this) {
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
