package com.mojtaba.simcallrecorder.data.audio

import android.media.AudioDeviceInfo
import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidAudioRouteDataSourceRobolectricTest {

    @Test
    fun returnsInputAndOutputInventoryAndMarksSelectedCommunicationDevice() {
        val source = FakeInventorySource(
            AudioDeviceInventory(
                devices = listOf(
                    AudioDeviceSnapshot(
                        id = 1,
                        type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
                        isSource = true,
                        isSink = false,
                        productName = "Built-in microphone",
                    ),
                    AudioDeviceSnapshot(
                        id = 2,
                        type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                        isSource = false,
                        isSink = true,
                        productName = "Built-in speaker",
                    ),
                    AudioDeviceSnapshot(
                        id = 3,
                        type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        isSource = false,
                        isSink = true,
                        productName = "Bluetooth headset",
                    ),
                ),
                selectedCommunicationDeviceId = 2,
            ),
        )

        val routes = AndroidAudioRouteDataSource(source).getRoutes()

        assertEquals(
            listOf(
                AudioRoute("Input", "Built-in microphone", "Built-in microphone", false),
                AudioRoute("Output", "Bluetooth SCO", "Bluetooth headset", false),
                AudioRoute("Output", "Built-in speaker", "Built-in speaker", true),
            ),
            routes,
        )
    }

    @Test
    fun emptyInventoryReturnsAnEmptyList() {
        val routes = AndroidAudioRouteDataSource(
            FakeInventorySource(AudioDeviceInventory(emptyList(), null)),
        ).getRoutes()

        assertTrue(routes.isEmpty())
    }

    @Test
    fun devicesWithBothRolesAreReportedAsInputAndOutput() {
        val routes = AndroidAudioRouteDataSource(
            FakeInventorySource(
                AudioDeviceInventory(
                    devices = listOf(
                        AudioDeviceSnapshot(
                            id = 9,
                            type = AudioDeviceInfo.TYPE_WIRED_HEADSET,
                            isSource = true,
                            isSink = true,
                            productName = "",
                        ),
                    ),
                    selectedCommunicationDeviceId = null,
                ),
            ),
        ).getRoutes()

        assertEquals(
            listOf(AudioRoute("Input / Output", "Wired headset", "Wired headset", false)),
            routes,
        )
    }

    private class FakeInventorySource(
        private val inventory: AudioDeviceInventory,
    ) : AudioDeviceInventorySource {
        override fun getInventory(): AudioDeviceInventory = inventory
    }
}
