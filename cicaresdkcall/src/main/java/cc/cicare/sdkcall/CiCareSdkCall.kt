package cc.cicare.sdkcall

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cc.cicare.sdkcall.event.MessageActionListener
import cc.cicare.sdkcall.event.MessageListenerHolder
import cc.cicare.sdkcall.notifications.CallNotificationManager
import cc.cicare.sdkcall.notifications.ui.ScreenCallActivity
import cc.cicare.sdkcall.services.CiCareCallService
import cc.cicare.sdkcall.services.IncomingCallService

class CiCareSdkCall private constructor(private val context: Context) {

    companion object {
        fun init(context: Context): CiCareSdkCall {
            return CiCareSdkCall(context.applicationContext)
        }
    }

    private val requiredPermissions = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
    )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requiredPermissionsTirmaisu = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.POST_NOTIFICATIONS,
    )

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private val requiredPermissionsUpsideDownCake = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        android.Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL
    )

    fun checkAndRequestPermissions(activity: Activity) {
        val permissions = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            requiredPermissions else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            requiredPermissionsTirmaisu
        else
            requiredPermissionsUpsideDownCake
        if (permissions.any {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }) {
                ActivityCompat.requestPermissions(activity, permissions, 1001)
        }
    }

    fun showIncoming(callerId: String,
                     callerName: String,
                     callerAvatar: String,
                     calleeId: String,
                     calleeName: String,
                     calleeAvatar: String,
                     checkSum: String,
                     metaData: Map<String, String> = emptyMap(),
                     tokenCall: String,
                     server: String,
                     isFromPhone: Boolean,
                     messageActionListener: MessageActionListener
                     ) {
        MessageListenerHolder.listener = messageActionListener

        val meta: HashMap<String, String> = HashMap(metaData)
        val intent = Intent(context, IncomingCallService::class.java).apply {
            action = CiCareCallService.ACTION.INCOMING
            putExtra("call_type", "incoming")
            putExtra("caller_name", callerName)
            putExtra("caller_avatar", callerAvatar)
            putExtra("meta_data", meta)
            putExtra("token", tokenCall)
            putExtra("server", server)
            putExtra("from_phone", isFromPhone)
        }
        context.startForegroundService(intent)
    }

    fun makeCall(callerId: String,
                         callerName: String,
                         callerAvatar: String,
                         calleeId: String,
                         calleeName: String,
                         calleeAvatar: String,
                         checkSum: String,
                         metaData: Map<String, String> = emptyMap()) {

                    val intent = Intent(context, CiCareCallService::class.java).apply {
                        action = CiCareCallService.ACTION.OUTGOING
                        putExtra("call_type", "outgoing")
                        putExtra("callee_id", calleeId)
                        putExtra("callee_name", calleeName)
                        putExtra("callee_avatar", calleeAvatar)
                        putExtra("caller_id", callerId)
                        putExtra("caller_name", callerName)
                        putExtra("caller_avatar", callerAvatar)
                        putExtra("checksum", checkSum)
                        putExtra("meta_data", HashMap(metaData))
                    }
                    context.startForegroundService(intent)

    }
}