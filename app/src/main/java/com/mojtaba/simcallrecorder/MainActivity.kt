package com.mojtaba.simcallrecorder

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mojtaba.simcallrecorder.core.permissions.CallPermissionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var permissionManager: CallPermissionManager

    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private var missingPermissions by mutableStateOf(emptyList<String>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            refreshPermissionState()
        }

        refreshPermissionState()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PermissionHomeScreen(
                        missingPermissions = missingPermissions,
                        onRequestPermissions = {
                            permissionLauncher.launch(
                                permissionManager.requestablePermissions().toTypedArray(),
                            )
                        },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::permissionLauncher.isInitialized) {
            refreshPermissionState()
        }
    }

    private fun refreshPermissionState() {
        missingPermissions = permissionManager.missingRuntimePermissions()
    }
}

@Composable
private fun PermissionHomeScreen(
    missingPermissions: List<String>,
    onRequestPermissions: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "SIM Call Recorder",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Native Android foundation",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = if (missingPermissions.isEmpty()) {
                "The requested runtime permissions are granted."
            } else {
                "Runtime permissions are still missing."
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        if (missingPermissions.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = missingPermissions.joinToString(separator = "\n"),
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermissions) {
                Text("Request permissions")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "This foundation does not record audio yet. Microphone and call-state " +
                "permissions do not grant unrestricted access to both sides of a SIM call. " +
                "Audio capture will only be enabled after device-level feasibility and consent " +
                "behavior have been verified.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
