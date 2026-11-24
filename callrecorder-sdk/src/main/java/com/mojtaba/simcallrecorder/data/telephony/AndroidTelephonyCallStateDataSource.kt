package com.mojtaba.simcallrecorder.data.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.mojtaba.simcallrecorder.domain.model.CallState
import com.mojtaba.simcallrecorder.domain.model.CallStateEvent
import com.mojtaba.simcallrecorder.domain.model.SimSlot
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
internal class AndroidTelephonyCallStateDataSource @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    suspend fun getActiveSimSlots(): List<SimSlot> = withContext(Dispatchers.IO) {
        requireCallStatePermission()

        val subscriptionManager =
            appContext.getSystemService(SubscriptionManager::class.java)
                ?: return@withContext emptyList()

        activeSubscriptions(subscriptionManager).mapIndexed { index, info ->
            val slotIndex = info.simSlotIndex
            val label = if (slotIndex >= 0) {
                "SIM " + (slotIndex + 1)
            } else {
                "Subscription " + (index + 1)
            }
            SimSlot(slotIndex = slotIndex, label = label)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Suppress("DEPRECATION")
    fun observeCallStateEvents(): Flow<CallStateEvent> = channelFlow {
        requireCallStatePermission()

        val subscriptionManager =
            appContext.getSystemService(SubscriptionManager::class.java)
                ?: throw IllegalStateException("Subscription service is unavailable.")

        val refreshSignals = Channel<Unit>(Channel.CONFLATED)
        val observerJobs = mutableMapOf<Int, Job>()
        val observedSlotBySubscriptionId = mutableMapOf<Int, Int>()
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                refreshSignals.trySend(Unit)
            }
        }

        subscriptionManager.addOnSubscriptionsChangedListener(listener)
        val reconciliationJob = launch {
            for (ignored in refreshSignals) {
                val subscriptions = activeSubscriptions(subscriptionManager)
                val plan = planSubscriptionObserverChanges(
                    currentlyObserved = observedSlotBySubscriptionId,
                    currentSubscriptions = subscriptions,
                    subscriptionId = SubscriptionInfo::getSubscriptionId,
                    slotIndex = SubscriptionInfo::getSimSlotIndex,
                )

                plan.toStop.forEach { subscriptionId ->
                    observerJobs.remove(subscriptionId)?.cancel()
                    observedSlotBySubscriptionId.remove(subscriptionId)
                }
                plan.toStart.forEach { info ->
                    val subscriptionId = info.subscriptionId
                    observedSlotBySubscriptionId[subscriptionId] = info.simSlotIndex
                    observerJobs[subscriptionId] = launch {
                        observeSubscription(info).collect { event -> send(event) }
                    }
                }
            }
        }
        // A SIM can be inserted/removed before this flow starts; always reconcile once immediately.
        refreshSignals.trySend(Unit)

        awaitClose {
            subscriptionManager.removeOnSubscriptionsChangedListener(listener)
            refreshSignals.close()
            reconciliationJob.cancel()
            observerJobs.values.forEach { it.cancel() }
            observerJobs.clear()
            observedSlotBySubscriptionId.clear()
        }
    }

    @Suppress("DEPRECATION")
    private fun activeSubscriptions(
        subscriptionManager: SubscriptionManager,
    ): List<SubscriptionInfo> = try {
        subscriptionManager.activeSubscriptionInfoList.orEmpty()
    } catch (exception: SecurityException) {
        throw SecurityException("READ_PHONE_STATE is required to inspect active SIM slots.", exception)
    } catch (exception: UnsupportedOperationException) {
        emptyList()
    }

    @Suppress("DEPRECATION")
    private fun observeSubscription(info: SubscriptionInfo): Flow<CallStateEvent> =
        callbackFlow {
            val baseManager = appContext.getSystemService(TelephonyManager::class.java)
                ?: run {
                    close(IllegalStateException("Telephony service is unavailable."))
                    return@callbackFlow
                }

            val subscriptionManager = baseManager.createForSubscriptionId(info.subscriptionId)
            val slotIndex = info.simSlotIndex

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                    override fun onCallStateChanged(state: Int) {
                        trySend(
                            CallStateEvent(
                                simSlotIndex = slotIndex,
                                state = mapTelephonyCallState(state),
                                observedAtEpochMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                }

                try {
                    subscriptionManager.registerTelephonyCallback(
                        ContextCompat.getMainExecutor(appContext),
                        callback,
                    )
                } catch (exception: SecurityException) {
                    close(exception)
                    return@callbackFlow
                }

                awaitClose {
                    subscriptionManager.unregisterTelephonyCallback(callback)
                }
            } else {
                val listener = withContext(Dispatchers.Main.immediate) {
                    object : PhoneStateListener() {
                        @Suppress("OVERRIDE_DEPRECATION")
                        override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                            // The number is deliberately ignored and never persisted.
                            trySend(
                                CallStateEvent(
                                    simSlotIndex = slotIndex,
                                    state = mapTelephonyCallState(state),
                                    observedAtEpochMillis = System.currentTimeMillis(),
                                ),
                            )
                        }
                    }
                }

                try {
                    withContext(Dispatchers.Main.immediate) {
                        subscriptionManager.listen(
                            listener,
                            PhoneStateListener.LISTEN_CALL_STATE,
                        )
                    }
                } catch (exception: SecurityException) {
                    close(exception)
                    return@callbackFlow
                }

                awaitClose {
                    ContextCompat.getMainExecutor(appContext).execute {
                        subscriptionManager.listen(
                            listener,
                            PhoneStateListener.LISTEN_NONE,
                        )
                    }
                }
            }
        }.distinctUntilChangedBy { it.state }

    private fun requireCallStatePermission() {
        val granted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            throw SecurityException("READ_PHONE_STATE permission is required.")
        }
    }
}

internal fun mapTelephonyCallState(state: Int): CallState =
    when (state) {
        TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
        TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
        else -> CallState.UNKNOWN
    }
