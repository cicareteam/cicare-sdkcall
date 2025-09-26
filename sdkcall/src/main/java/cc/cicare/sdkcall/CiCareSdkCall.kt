package cc.cicare.sdkcall

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cc.cicare.sdkcall.event.MessageActionListener
import cc.cicare.sdkcall.event.MessageListenerHolder
import cc.cicare.sdkcall.libs.ApiClient
import cc.cicare.sdkcall.notifications.CallNotificationManager
import cc.cicare.sdkcall.services.CiCareCallService
import cc.cicare.sdkcall.services.IncomingCallService
import org.json.JSONObject
import java.lang.ref.WeakReference

object CiCareSdkCall {

    private var contextRef: WeakReference<Context>? = null

    fun init(context: Context): CiCareSdkCall {
        contextRef = WeakReference(context.applicationContext)
        return this
    }

    fun setAPI(baseUrl: String, token: String) {
        ApiClient.BASE_URL = baseUrl
        ApiClient.AUTH_TOKEN = token
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
                     callerName: String? = "Green SM Driver",
                     callerAvatar: String? = "",
                     calleeId: String,
                     calleeName: String? = "Green SM Customer",
                     calleeAvatar: String? = "",
                     checkSum: String,
                     metaData: Map<String, String> = emptyMap(),
                     messageActionListener: MessageActionListener
                     ) {

        var base64String = metaData["alert_data"] as? String ?: return

        // Hapus prefix "base64," kalau ada
        val prefix = "base64,"
        if (base64String.contains(prefix)) {
            base64String = base64String.substringAfter(prefix)
        }

        base64String = base64String.trim()

        val remainder = base64String.length % 4
        if (remainder > 0) {
            base64String += "=".repeat(4 - remainder)
        }

        try {
            val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            val decodedString = String(decodedBytes, Charsets.UTF_8)
            val jsonObject = JSONObject(decodedString)

            val server = jsonObject.getString("server") ?: return
            val token = jsonObject.getString("token") ?: return
            val isFromPhone = jsonObject.getBoolean("isFromPhone")

            val ctx = contextRef?.get()
            if (ctx == null) return

            MessageListenerHolder.listener = messageActionListener


            val meta: HashMap<String, String> = HashMap(metaData)
            var caller = if(callerName == "" || callerName == null) { "Green SM Driver" } else { callerName }
            var callee = if(calleeName == "" || calleeName == null) { "Green SM Customer" } else { calleeName }
            val intent = Intent(ctx, IncomingCallService::class.java).apply {
                action = CiCareCallService.ACTION.INCOMING
                putExtra("call_type", "incoming")
                putExtra("caller_id", callerId)
                putExtra("caller_name", caller)
                putExtra("callee_id", calleeId)
                putExtra("callee_name", callee)
                putExtra("callee_avatar", calleeAvatar)
                putExtra("caller_avatar", callerAvatar)
                putExtra("meta_data", meta)
                putExtra("checksum", checkSum)
                putExtra("token", token)
                putExtra("server", server)
                putExtra("from_phone", isFromPhone)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                ctx.startForegroundService(intent)
            else
                ctx.startService(intent)
        } catch (e: Exception) {
            Log.e("SDK CALL", "❌ Failed to decode or parse JSON: ${e.message}")
        }
    }

    fun makeCall(callerId: String,
                 callerName: String? = "Green SM Driver",
                 callerAvatar: String? = "",
                 calleeId: String,
                 calleeName: String? = "Green SM Customer",
                 calleeAvatar: String = "",
                 checkSum: String,
                 metaData: Map<String, String> = emptyMap()) {
        val ctx = contextRef?.get()
        if (ctx == null) return

        var caller = if(callerName == "" || callerName == null) { "Green SM Driver" } else { callerName }
        var callee = if(calleeName == "" || calleeName == null) { "Green SM Customer" } else { calleeName }
        val intent = Intent(ctx, CiCareCallService::class.java).apply {
            action = CiCareCallService.ACTION.OUTGOING
            putExtra("call_type", "outgoing")
            putExtra("callee_id", calleeId)
            putExtra("callee_name", callee)
            putExtra("callee_avatar", calleeAvatar)
            putExtra("caller_id", callerId)
            putExtra("caller_name", caller)
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