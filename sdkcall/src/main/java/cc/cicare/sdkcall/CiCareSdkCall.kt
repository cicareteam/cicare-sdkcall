package cc.cicare.sdkcall

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cc.cicare.sdkcall.event.CallEventListener
import cc.cicare.sdkcall.event.MessageActionListener
import cc.cicare.sdkcall.event.MessageListenerHolder
import cc.cicare.sdkcall.libs.AES256Decryptor
import cc.cicare.sdkcall.libs.ApiClient
import cc.cicare.sdkcall.notifications.CallNotificationManager
import cc.cicare.sdkcall.notifications.ui.ScreenCallActivity
import cc.cicare.sdkcall.services.CiCareCallService
import cc.cicare.sdkcall.services.IncomingCallService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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

    fun setEventListener(eventListener: CallEventListener) {
        MessageListenerHolder.callEventListener = eventListener
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

    fun checkAndRequestPermissions(activity: Activity): Boolean {
        val ctx = contextRef?.get() ?: return false

        val permissions = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> requiredPermissions
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> requiredPermissions28
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> requiredPermissionsTirmaisu
            else -> requiredPermissionsUpsideDownCake
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (notGranted.isEmpty()) {
            // Semua permission sudah diberikan
            true
        } else {
            // Masih ada yang belum diberikan → request ke user
            ActivityCompat.requestPermissions(activity, notGranted.toTypedArray(), 1001)
            false
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun isForegroundMicPermissionGranted(activity: ComponentActivity): Boolean {
        val ctx = contextRef?.get() ?: return false

        val permissions = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> requiredPermissions
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> requiredPermissions28
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> requiredPermissionsTirmaisu
            else -> requiredPermissionsUpsideDownCake
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) return true

        return suspendCancellableCoroutine { continuation ->
            // val requestCode = 1001

            val callback = object : ActivityResultCallback<Map<String, Boolean>> {
                override fun onActivityResult(result: Map<String, Boolean>) {
                    val allGranted = result.values.all { it }
                    continuation.resume(allGranted) {}
                }
            }

            val launcher = activity.activityResultRegistry.register(
                "permission_request_${System.currentTimeMillis()}",
                ActivityResultContracts.RequestMultiplePermissions(),
                callback
            )

            launcher.launch(notGranted.toTypedArray())
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

        val cipherText = metaData["alert_data"] ?: return

        // Hapus prefix "base64," kalau ada
        //val prefix = "base64,"
        //if (base64String.contains(prefix)) {
        //    base64String = base64String.substringAfter(prefix)
        //}

        //base64String = base64String.trim()

        //val remainder = base64String.length % 4
        //if (remainder > 0) {
        //    base64String += "=".repeat(4 - remainder)
        //}

        try {
            //val decodedBytes = Base64.decode(base64String, Base64.DEFAULT)
            //val decodedString = String(decodedBytes, Charsets.UTF_8)

//            keynya adalah
            val encryptionKey = "0123456789abcdef0123456789abcdef"
            val decryptedJson = AES256Decryptor.decrypt(cipherText, encryptionKey)

            val jsonObject = JSONObject(decryptedJson)
            Log.d("SDK CALL C", "jsonObject: $jsonObject")

            val server = jsonObject.getString("server") ?: return
            val token = jsonObject.getString("token") ?: return
            val isFromPhone = jsonObject.getBoolean("isFromPhone")

            val ctx = contextRef?.get() ?: return

            MessageListenerHolder.listener = messageActionListener


            val meta: HashMap<String, String> = HashMap(metaData)
            val caller = if(callerName == "" || callerName == null) { "Caller" } else { callerName }
            val callee = if(calleeName == "" || calleeName == null) { "Callee" } else { calleeName }
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

    fun makeCall(
                 activity: ComponentActivity,
                 callerId: String,
                 callerName: String? = "Caller",
                 callerAvatar: String? = "",
                 calleeId: String,
                 calleeName: String? = "Callee",
                 calleeAvatar: String = "",
                 checkSum: String,
                 metaData: Map<String, String> = emptyMap()) {
        val ctx = contextRef?.get() ?: return
        CoroutineScope(Dispatchers.Main).launch {
            if (isForegroundMicPermissionGranted(activity)) {
                val caller = if (callerName == "" || callerName == null) {
                    "Caller"
                } else {
                    callerName
                }
                val callee = if (calleeName == "" || calleeName == null) {
                    "Callee"
                } else {
                    calleeName
                }
                val intent = Intent(ctx, ScreenCallActivity::class.java).apply {
                    action = CiCareCallService.ACTION.OUTGOING
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
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
                ctx.startActivity(intent)
            } else {
                MessageListenerHolder.callEventListener?.onError(101, "Permisssion not granted")
            }
        }
    }

    /*fun makeCallSip(
        activity: ComponentActivity,
        callerId: String,
        callerName: String? = "Caller",
        callerAvatar: String? = "",
        destination: String,
        destinationName: String,
        destinationAvatar: String,
        checkSum: String,
        metaData: Map<String, String> = emptyMap()) {
        val ctx = contextRef?.get() ?: return
        CoroutineScope(Dispatchers.Main).launch {
            if (isForegroundMicPermissionGranted(activity)) {
                val caller = if (callerName == "" || callerName == null) {
                    "Caller"
                } else {
                    callerName
                }
                val intent = Intent(ctx, ScreenCallActivity::class.java).apply {
                    action = CiCareCallService.ACTION.OUTGOING
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtra("call_type", "outgoing_sip")
                    putExtra("destination", destination)
                    putExtra("caller_id", callerId)
                    putExtra("caller_name", caller)
                    putExtra("caller_avatar", callerAvatar)
                    putExtra("callee_id", destination)
                    putExtra("callee_name", destinationName)
                    putExtra("callee_avatar", destinationAvatar)
                    putExtra("checksum", checkSum)
                    putExtra("meta_data", HashMap(metaData))
                }
                ctx.startActivity(intent)
            } else {
                MessageListenerHolder.callEventListener?.onError(101, "Permisssion not granted")
            }
        }
    }*/
}