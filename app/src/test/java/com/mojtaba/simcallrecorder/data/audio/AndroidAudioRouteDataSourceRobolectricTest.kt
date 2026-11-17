package com.mojtaba.simcallrecorder.data.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.SparseIntArray
import com.mojtaba.simcallrecorder.domain.model.AudioRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow

@RunWith(RobolectricTestRunner::class)
class AndroidAudioRouteDataSourceRobolectricTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(sdk = [35])
    fun returnsInputAndOutputInventoryAndMarksSelectedCommunicationDevice() {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val microphone = audioDevice(
            type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
            role = ROLE_SOURCE,
            id = 1,
            name = "Built-in microphone",
        )
        val speaker = audioDevice(
            type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            role = ROLE_SINK,
            id = 2,
            name = "Built-in speaker",
        )
        val bluetoothHeadset = audioDevice(
            type = AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            role = ROLE_SINK,
            id = 3,
            name = "Bluetooth headset",
        )

        shadowOf(audioManager).setInputDevices(listOf(microphone))
        shadowOf(audioManager).setOutputDevices(listOf(speaker, bluetoothHeadset))
        shadowOf(audioManager).setCommunicationDevice(speaker)

        val routes = AndroidAudioRouteDataSource(context).getRoutes()

        assertEquals(3, routes.size)
        assertRoute(
            routes,
            direction = "Input",
            deviceType = "Built-in microphone",
            displayName = "Built-in microphone",
            selected = false,
        )
        assertRoute(
            routes,
            direction = "Output",
            deviceType = "Built-in speaker",
            displayName = "Built-in speaker",
            selected = true,
        )
        assertRoute(
            routes,
            direction = "Output",
            deviceType = "Bluetooth SCO",
            displayName = "Bluetooth headset",
            selected = false,
        )
        assertEquals(routes.sortedWith(compareBy<AudioRoute>({ it.direction }, { it.deviceType }, { it.displayName })), routes)
    }

    @Test
    @Config(sdk = [28])
    fun preAndroid12_inventoryDoesNotReportCommunicationDeviceSelection() {
        val audioManager = context.getSystemService(AudioManager::class.java)
        val microphone = audioDevice(
            type = AudioDeviceInfo.TYPE_BUILTIN_MIC,
            role = ROLE_SOURCE,
            id = 11,
            name = "Mic",
        )
        val wiredHeadphones = audioDevice(
            type = AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            role = ROLE_SINK,
            id = 12,
            name = "Wired headphones",
        )
        shadowOf(audioManager).setInputDevices(listOf(microphone))
        shadowOf(audioManager).setOutputDevices(listOf(wiredHeadphones))

        val routes = AndroidAudioRouteDataSource(context).getRoutes()

        assertEquals(2, routes.size)
        assertTrue(routes.any { it.direction == "Input" && it.deviceType == "Built-in microphone" })
        assertTrue(routes.any { it.direction == "Output" && it.deviceType == "Wired headphones" })
        assertTrue(routes.none { it.selectedForCommunication })
    }

    @Test
    @Config(sdk = [35])
    fun emptyDeviceInventory_returnsAnEmptyList() {
        val routes = AndroidAudioRouteDataSource(context).getRoutes()

        assertTrue(routes.isEmpty())
    }

    private fun assertRoute(
        routes: List<AudioRoute>,
        direction: String,
        deviceType: String,
        displayName: String,
        selected: Boolean,
    ) {
        val route = routes.single {
            it.direction == direction && it.deviceType == deviceType
        }
        assertEquals(displayName, route.displayName)
        assertEquals(selected, route.selectedForCommunication)
    }

    private fun audioDevice(
        type: Int,
        role: Int,
        id: Int,
        name: String,
    ): AudioDeviceInfo {
        val info = Shadow.newInstanceOf(AudioDeviceInfo::class.java)
        val port = Shadow.newInstanceOf("android.media.AudioDevicePort")
        setField(info, "mPort", port)

        val externalToInternal = getStaticField(
            AudioDeviceInfo::class.java,
            "EXT_TO_INT_DEVICE_MAPPING",
        ) as SparseIntArray
        setField(port, "mType", externalToInternal.get(type))
        setField(port, "mRole", role)
        setField(port, "mName", name)

        val handle = Shadow.newInstanceOf("android.media.AudioHandle")
        setField(handle, "mId", id)
        setField(port, "mHandle", handle)
        return info
    }

    private fun getStaticField(type: Class<*>, name: String): Any {
        val field = type.getDeclaredField(name)
        field.isAccessible = true
        return field.get(null)
    }

    private fun setField(target: Any, name: String, value: Any) {
        var current: Class<*>? = target.javaClass
        while (current != null) {
            try {
                val field = current.getDeclaredField(name)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                current = current.superclass
            }
        }
        throw IllegalStateException("Could not find field $name on ${target.javaClass.name}")
    }

    private companion object {
        // AudioPort.ROLE_SOURCE and AudioPort.ROLE_SINK are hidden framework constants.
        const val ROLE_SOURCE = 1
        const val ROLE_SINK = 2
    }
}
