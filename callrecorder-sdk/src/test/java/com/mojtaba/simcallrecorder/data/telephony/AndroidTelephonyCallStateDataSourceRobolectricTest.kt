package com.mojtaba.simcallrecorder.data.telephony

import android.Manifest
import android.content.Context
import android.os.Looper
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import com.mojtaba.simcallrecorder.domain.model.CallState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowApplication
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSubscriptionManager
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class AndroidTelephonyCallStateDataSourceRobolectricTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    @Config(sdk = [31, 32, 33, 34, 35])
    fun api31To35_emitsRingingOffhookAndIdleForTheCorrectSubscription() = runBlocking {
        grantPhoneStatePermission()
        val subscriptionId = 101
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val subscription = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(subscriptionId)
            .setSimSlotIndex(1)
            .buildSubscriptionInfo()
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(subscription))

        val perSubscriptionManager = newTelephonyManager()
        val defaultTelephonyManager = context.getSystemService(TelephonyManager::class.java)
        shadowOf(defaultTelephonyManager)
            .setTelephonyManagerForSubscriptionId(subscriptionId, perSubscriptionManager)

        val events = mutableListOf<com.mojtaba.simcallrecorder.domain.model.CallStateEvent>()
        val dataSource = AndroidTelephonyCallStateDataSource(context)
        val collector = launch(Dispatchers.IO) {
            dataSource.observeCallStateEvents().take(4).toList(events)
        }

        awaitModernRegistration(perSubscriptionManager)
        val telephonyShadow = shadowOf(perSubscriptionManager)
        telephonyShadow.setCallState(TelephonyManager.CALL_STATE_RINGING)
        shadowOf(Looper.getMainLooper()).idle()
        telephonyShadow.setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        shadowOf(Looper.getMainLooper()).idle()
        telephonyShadow.setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(10_000) { collector.join() }

        assertEquals(
            listOf(CallState.IDLE, CallState.RINGING, CallState.OFFHOOK, CallState.IDLE),
            events.map { it.state },
        )
        assertTrue(events.all { it.simSlotIndex == 1 })
        assertTrue(events.all { it.observedAtEpochMillis > 0L })
    }

    @Test
    @Config(sdk = [36, 37])
    fun api36Plus_emitsInitialSnapshotForCallAlreadyInProgress() = runBlocking {
        grantPhoneStatePermission()
        val subscriptionId = 111
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val subscription = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(subscriptionId)
            .setSimSlotIndex(0)
            .buildSubscriptionInfo()
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(subscription))

        val perSubscriptionManager = newTelephonyManager()
        shadowOf(context.getSystemService(TelephonyManager::class.java))
            .setTelephonyManagerForSubscriptionId(subscriptionId, perSubscriptionManager)
        shadowOf(perSubscriptionManager).setCallState(TelephonyManager.CALL_STATE_OFFHOOK)

        val events = mutableListOf<com.mojtaba.simcallrecorder.domain.model.CallStateEvent>()
        val collector = launch(Dispatchers.IO) {
            AndroidTelephonyCallStateDataSource(context).observeCallStateEvents().take(1).toList(events)
        }

        awaitModernRegistration(perSubscriptionManager)
        withTimeout(10_000) { collector.join() }

        assertEquals(listOf(CallState.OFFHOOK), events.map { it.state })
        assertEquals(listOf(0), events.map { it.simSlotIndex })
    }

    @Test
    @Config(sdk = [26, 27, 28, 29, 30])
    fun api26To30_legacyPhoneStateListener_emitsAllCallTransitions() = runBlocking {
        grantPhoneStatePermission()
        val subscriptionId = 202
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val subscription = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(subscriptionId)
            .setSimSlotIndex(0)
            .buildSubscriptionInfo()
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(subscription))

        val perSubscriptionManager = newTelephonyManager()
        val defaultTelephonyManager = context.getSystemService(TelephonyManager::class.java)
        shadowOf(defaultTelephonyManager)
            .setTelephonyManagerForSubscriptionId(subscriptionId, perSubscriptionManager)

        val events = mutableListOf<com.mojtaba.simcallrecorder.domain.model.CallStateEvent>()
        val dataSource = AndroidTelephonyCallStateDataSource(context)
        val collector = launch(Dispatchers.IO) {
            dataSource.observeCallStateEvents().take(4).toList(events)
        }

        awaitLegacyRegistration(perSubscriptionManager)
        val telephonyShadow = shadowOf(perSubscriptionManager)
        telephonyShadow.setCallState(TelephonyManager.CALL_STATE_RINGING)
        shadowOf(Looper.getMainLooper()).idle()
        telephonyShadow.setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        shadowOf(Looper.getMainLooper()).idle()
        telephonyShadow.setCallState(TelephonyManager.CALL_STATE_IDLE)
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(10_000) { collector.join() }

        assertEquals(
            listOf(CallState.IDLE, CallState.RINGING, CallState.OFFHOOK, CallState.IDLE),
            events.map { it.state },
        )
        assertTrue(events.all { it.simSlotIndex == 0 })
    }

    @Test
    @Config(sdk = [31, 32, 33, 34, 35, 36, 37])
    fun multipleSubscriptions_keepTheirOwnSlotAssociation() = runBlocking {
        grantPhoneStatePermission()
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val firstSubscriptionId = 301
        val secondSubscriptionId = 302
        val firstInfo = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(firstSubscriptionId)
            .setSimSlotIndex(0)
            .buildSubscriptionInfo()
        val secondInfo = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(secondSubscriptionId)
            .setSimSlotIndex(1)
            .buildSubscriptionInfo()
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(firstInfo, secondInfo))

        val firstManager = newTelephonyManager()
        val secondManager = newTelephonyManager()
        val defaultManager = context.getSystemService(TelephonyManager::class.java)
        shadowOf(defaultManager).setTelephonyManagerForSubscriptionId(firstSubscriptionId, firstManager)
        shadowOf(defaultManager).setTelephonyManagerForSubscriptionId(secondSubscriptionId, secondManager)

        val events = mutableListOf<com.mojtaba.simcallrecorder.domain.model.CallStateEvent>()
        val collector = launch(Dispatchers.IO) {
            AndroidTelephonyCallStateDataSource(context)
                .observeCallStateEvents()
                .filter { it.state != CallState.IDLE }
                .take(2)
                .toList(events)
        }

        awaitModernRegistration(firstManager)
        awaitModernRegistration(secondManager)
        shadowOf(firstManager).setCallState(TelephonyManager.CALL_STATE_RINGING)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(secondManager).setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        shadowOf(Looper.getMainLooper()).idle()
        withTimeout(10_000) { collector.join() }

        assertEquals(2, events.size)
        assertEquals(
            mapOf(CallState.RINGING to 0, CallState.OFFHOOK to 1),
            events.associate { it.state to it.simSlotIndex },
        )
    }

    @Test
    @Config(sdk = [31, 32, 33, 34, 35, 36, 37])
    fun activeSimSlots_exposeSlotLabelsButNotSubscriptionIdentifiers() = runBlocking {
        grantPhoneStatePermission()
        val subscriptionManager = context.getSystemService(SubscriptionManager::class.java)
        val first = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(411)
            .setSimSlotIndex(0)
            .buildSubscriptionInfo()
        val second = ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(412)
            .setSimSlotIndex(1)
            .buildSubscriptionInfo()
        shadowOf(subscriptionManager).setActiveSubscriptionInfoList(listOf(first, second))

        val slots = AndroidTelephonyCallStateDataSource(context).getActiveSimSlots()

        assertEquals(listOf("SIM 1", "SIM 2"), slots.map { it.label })
        assertEquals(listOf(0, 1), slots.map { it.slotIndex })
    }

    @Test
    @Config(sdk = [31, 32, 33, 34, 35, 36, 37])
    fun observer_withoutReadPhoneStatePermission_failsClosed() = runBlocking {
        (Shadow.extract(context) as ShadowApplication).denyPermissions(Manifest.permission.READ_PHONE_STATE)

        val exception = try {
            AndroidTelephonyCallStateDataSource(context).observeCallStateEvents().collect { }
            null
        } catch (caught: SecurityException) {
            caught
        }

        assertTrue(exception != null)
    }

    private suspend fun awaitModernRegistration(manager: TelephonyManager) {
        val managerShadow = shadowOf(manager)
        withTimeout(5_000) {
            while (managerShadow.getLastTelephonyCallback() == null) {
                shadowOf(Looper.getMainLooper()).idle()
                yield()
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Suppress("DEPRECATION")
    private suspend fun awaitLegacyRegistration(manager: TelephonyManager) {
        val managerShadow = shadowOf(manager)
        withTimeout(5_000) {
            while (managerShadow.getListener() == null) {
                shadowOf(Looper.getMainLooper()).idle()
                yield()
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun grantPhoneStatePermission() {
        (Shadow.extract(context) as ShadowApplication).grantPermissions(Manifest.permission.READ_PHONE_STATE)
    }

    private fun newTelephonyManager(): TelephonyManager =
        ReflectionHelpers.callConstructor(
            TelephonyManager::class.java,
            ReflectionHelpers.ClassParameter.from(Context::class.java, context),
        )
}
