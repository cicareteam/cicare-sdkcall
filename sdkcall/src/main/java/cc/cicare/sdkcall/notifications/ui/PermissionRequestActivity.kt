package cc.cicare.sdkcall.notifications.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import cc.cicare.sdkcall.event.MessageListenerHolder

class PermissionRequestActivity : ComponentActivity() {

    private var showPermissionDialog by mutableStateOf(false)

    private val requiredPermissions =
            arrayOf(
                    android.Manifest.permission.RECORD_AUDIO,
                    // android.Manifest.permission.READ_PHONE_STATE,
                    )

    private val requiredPermissions28 =
            arrayOf(
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.FOREGROUND_SERVICE,
                    // android.Manifest.permission.READ_PHONE_STATE,
                    )

    private val requiredPermissionsTirmaisu =
            arrayOf(
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.FOREGROUND_SERVICE,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    // android.Manifest.permission.READ_PHONE_STATE,
                    )

    private val requiredPermissionsUpsideDownCake =
            arrayOf(
                    android.Manifest.permission.RECORD_AUDIO,
                    android.Manifest.permission.FOREGROUND_SERVICE,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    android.Manifest.permission.BLUETOOTH_CONNECT,
                    // android.Manifest.permission.READ_PHONE_STATE,
                    android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
            )

    private val settingsLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                val permissions = getRequiredPermissions()
                val allGranted =
                        permissions.all {
                            ContextCompat.checkSelfPermission(this, it) ==
                                    PackageManager.PERMISSION_GRANTED
                        }
                if (allGranted) {
                    proceedToCall()
                } else {
                    showPermissionDialog = true
                }
            }

    private val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result
                ->
                val allGranted = result.values.all { it }
                if (!allGranted) {
                    showPermissionDialog = true
                } else {
                    proceedToCall()
                }
            }

    private fun getRequiredPermissions(): Array<String> {
        return when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> requiredPermissions
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> requiredPermissions28
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    requiredPermissionsTirmaisu
            else -> requiredPermissionsUpsideDownCake
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Cek izin awal
        val permissions = getRequiredPermissions()
        val notGranted =
                permissions.filter {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                }

        if (notGranted.isEmpty()) {
            proceedToCall()
            return
        } else {
            permissionLauncher.launch(notGranted.toTypedArray())
        }

        // MetaData for custom text mapping just like ScreenCallActivity
        val metaData =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val extra =
                            intent.getSerializableExtra("meta_data", HashMap::class.java)
                                    ?.mapNotNull {
                                        val key = it.key as? String
                                        val value = it.value as? String
                                        if (key != null && value != null) key to value else null
                                    }
                                    ?.toMap()
                                    ?: emptyMap()
                    HashMap(extra)
                } else {
                    val extra =
                            (intent.getSerializableExtra("meta_data") as? HashMap<*, *>)
                                    ?.mapNotNull {
                                        val key = it.key as? String
                                        val value = it.value as? String
                                        if (key != null && value != null) key to value else null
                                    }
                                    ?.toMap()
                                    ?: emptyMap()
                    HashMap(extra)
                }

        setContent {
            Box(
                    modifier =
                            Modifier.fillMaxSize()
                                    .background(Color.Transparent) // Transparent background
            ) {
                if (showPermissionDialog) {
                    androidx.compose.material3.AlertDialog(
                            onDismissRequest = {
                                showPermissionDialog = false
                                cancelCall()
                            },
                            title = {
                                Text(
                                        text =
                                                metaData["call_permission_microphone_demied_title"]
                                                        ?.toString()
                                                        ?: "Microphone Permission Required"
                                )
                            },
                            text = {
                                Text(
                                        text =
                                                metaData[
                                                                "call_permission_microphone_demied_content"]
                                                        ?.toString()
                                                        ?: "Please enable microphone permission in system settings to make a call."
                                )
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(
                                        onClick = {
                                            showPermissionDialog = false
                                            val settingsIntent =
                                                    Intent(
                                                            android.provider.Settings
                                                                    .ACTION_APPLICATION_DETAILS_SETTINGS
                                                    )
                                            val uri =
                                                    android.net.Uri.fromParts(
                                                            "package",
                                                            packageName,
                                                            null
                                                    )
                                            settingsIntent.data = uri
                                            settingsLauncher.launch(settingsIntent)
                                        }
                                ) {
                                    Text(
                                            text =
                                                    metaData["call_permission_btn_setting"]
                                                            ?.toString()
                                                            ?: "Go to Settings",
                                            color = Color(0xFF00BABD)
                                    )
                                }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(
                                        onClick = {
                                            showPermissionDialog = false
                                            cancelCall()
                                        }
                                ) {
                                    Text(
                                            text = metaData["call_permission_btn_deny"]?.toString()
                                                            ?: "Cancel",
                                            color = Color.Gray
                                    )
                                }
                            }
                    )
                }
            }
        }
    }

    private fun proceedToCall() {
        // Izin berhasil. Lanjutkan dengan launch ScreenCallActivity seolah-olah makeCall dipanggil normal.
        val nextIntent =
                Intent(this, ScreenCallActivity::class.java).apply {
                    action = intent.action // PENTING: copy action (OUTGOING) ke intent baru
                    putExtras(
                            intent
                    ) // copy semua parameters dari makeCall awal (callerId, callerName, etc)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
        startActivity(nextIntent)
        finish()
    }

    private fun cancelCall() {
        MessageListenerHolder.callEventListener?.onError(101, "Permission denied")
        
        val isIncoming = intent.getStringExtra("call_type") == "incoming"
        
        Log.i("SDK CALL", "cancelCall. isIncoming: $isIncoming")

        if (isIncoming) {
            // Rejection for incoming calls
            val rejectIntent = Intent(this, cc.cicare.sdkcall.services.IncomingCallService::class.java).apply {
                action = cc.cicare.sdkcall.services.CiCareCallService.ACTION.REJECT
                putExtras(intent.extras ?: Bundle())
            }
            startService(rejectIntent)
        } else {
            // Rejection/Cancellation for outgoing calls
            val rejectIntent = Intent(this, cc.cicare.sdkcall.services.CiCareCallService::class.java).apply {
                action = cc.cicare.sdkcall.services.CiCareCallService.ACTION.REJECT
            }
            startService(rejectIntent)
        }
        
        finish()
    }

    // override back pressed to throw permission error if cancelled without allowing
    override fun onBackPressed() {
        super.onBackPressed()
        cancelCall()
    }
}
