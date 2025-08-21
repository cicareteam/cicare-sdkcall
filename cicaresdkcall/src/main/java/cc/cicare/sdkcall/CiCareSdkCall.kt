package cc.cicare.sdkcall

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cc.cicare.sdkcall.event.MessageActionListener
import cc.cicare.sdkcall.event.MessageListenerHolder
import cc.cicare.sdkcall.notifications.CallNotificationManager
import cc.cicare.sdkcall.services.CiCareCallService
import cc.cicare.sdkcall.services.IncomingCallService
import java.lang.ref.WeakReference

object CiCareSdkCall {

    private var contextRef: WeakReference<Context>? = null

    fun init(context: Context): CiCareSdkCall {
        contextRef = WeakReference(context.applicationContext)
        return this
    }

    fun setRingTone(ringTone: Uri) {
        CallNotificationManager.ringtoneUrl = ringTone
    }

    private val requiredPermissions = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.READ_PHONE_STATE,
    )

    @RequiresApi(Build.VERSION_CODES.P)
    private val requiredPermissions28 = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.READ_PHONE_STATE,
    )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requiredPermissionsTirmaisu = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.READ_PHONE_STATE,
    )

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private val requiredPermissionsUpsideDownCake = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        android.Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL
    )

    fun checkAndRequestPermissions(activity: Activity) {
        val ctx = contextRef?.get()
        if (ctx == null) return
        val permissions = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P)
            requiredPermissions else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
            requiredPermissions28 else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            requiredPermissionsTirmaisu
        else
            requiredPermissionsUpsideDownCake
        if (permissions.any {
                ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
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
        val ctx = contextRef?.get()
        if (ctx == null) return

        MessageListenerHolder.listener = messageActionListener

        val meta: HashMap<String, String> = HashMap(metaData)
        val intent = Intent(ctx, IncomingCallService::class.java).apply {
            action = CiCareCallService.ACTION.INCOMING
            putExtra("call_type", "incoming")
            putExtra("caller_id", callerId)
            putExtra("caller_name", callerName)
            putExtra("callee_id", calleeId)
            putExtra("callee_name", calleeName)
            putExtra("callee_avatar", calleeAvatar)
            putExtra("caller_avatar", callerAvatar)
            putExtra("meta_data", meta)
            putExtra("checksum", checkSum)
            putExtra("token", tokenCall)
            putExtra("server", server)
            putExtra("from_phone", isFromPhone)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(intent)
        else
            ctx.startService(intent)
    }

    fun makeCall(callerId: String,
                 callerName: String,
                 callerAvatar: String,
                 calleeId: String,
                 calleeName: String,
                 calleeAvatar: String,
                 checkSum: String,
                 metaData: Map<String, String> = emptyMap()) {
        val ctx = contextRef?.get()
        if (ctx == null) return
                    val intent = Intent(ctx, CiCareCallService::class.java).apply {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(intent)
        else
            ctx.startService(intent)

    }
}