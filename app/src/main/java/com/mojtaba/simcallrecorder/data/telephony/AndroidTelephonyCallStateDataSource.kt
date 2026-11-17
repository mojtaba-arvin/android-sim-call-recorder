package com.mojtaba.simcallrecorder.data.telephony

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.mojtaba.simcallrecorder.domain.model.CallState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AndroidTelephonyCallStateDataSource @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext

    @Suppress("DEPRECATION")
    fun observe(): Flow<CallState> = callbackFlow {
        val permissionGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

        if (!permissionGranted) {
            close(SecurityException("READ_PHONE_STATE permission is required."))
            return@callbackFlow
        }

        val telephonyManager =
            appContext.getSystemService(TelephonyManager::class.java)

        if (telephonyManager == null) {
            close(IllegalStateException("Telephony service is not available on this device."))
            return@callbackFlow
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    trySend(mapTelephonyCallState(state))
                }
            }

            try {
                telephonyManager.registerTelephonyCallback(
                    appContext.mainExecutor,
                    callback,
                )
            } catch (exception: SecurityException) {
                close(exception)
                return@callbackFlow
            }

            awaitClose {
                telephonyManager.unregisterTelephonyCallback(callback)
            }
        } else {
            // The no-argument constructor binds to Looper.myLooper(). Construct it on
            // the main dispatcher to keep this compatible with API 28.
            val listener = withContext(Dispatchers.Main.immediate) {
                object : PhoneStateListener() {
                    override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                        // Phone numbers are deliberately ignored; call-log access is not requested.
                        trySend(mapTelephonyCallState(state))
                    }
                }
            }

            try {
                withContext(Dispatchers.Main.immediate) {
                    telephonyManager.listen(
                        listener,
                        PhoneStateListener.LISTEN_CALL_STATE,
                    )
                }
            } catch (exception: SecurityException) {
                close(exception)
                return@callbackFlow
            }

            awaitClose {
                appContext.mainExecutor.execute {
                    telephonyManager.listen(
                        listener,
                        PhoneStateListener.LISTEN_NONE,
                    )
                }
            }
        }
    }.distinctUntilChanged()
}

internal fun mapTelephonyCallState(state: Int): CallState =
    when (state) {
        TelephonyManager.CALL_STATE_IDLE -> CallState.IDLE
        TelephonyManager.CALL_STATE_RINGING -> CallState.RINGING
        TelephonyManager.CALL_STATE_OFFHOOK -> CallState.OFFHOOK
        else -> CallState.UNKNOWN
    }
