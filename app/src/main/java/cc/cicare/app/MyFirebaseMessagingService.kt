package cc.cicare.app

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import cc.cicare.sdkcall.CiCareSdkCall
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    //@Inject lateinit var callServiceRepository: CallServiceRepository

    override fun onNewToken(token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            Log.i("user", "new token")
            prefs.edit {
                putString("fcm", token)
            }
        }
//
//        if (userId != null && userId > 0) {
//            CoroutineScope(Dispatchers.IO).launch {
//                try {
//                    val response = ApiClient.api.saveToken(TokenSaveRequest(userId, token))
//                    if (response.isSuccessful) {
//                        Log.d("SDK Call", "Token saved")
//                    } else {
//                        Log.e("SDK Call", "Token failed: ${response.code()}")
//                    }
//                } catch (e: Exception) {
//                    Log.e("SDK Call", "Error saving token: ${e.message}")
//                }
//            }
//        } else {
//            Log.w("SDK Call", "User ID not found, cannot save token.")
//        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val callerName = data["caller_name"] ?: "Unknown"
        val callerId = data["caller_id"] ?: ""
        val callerAvatar = data["caller_avatar"] ?: ""
        val metadata = mutableMapOf<String, String>().apply {
            this["alert_data"] = data["alert_data"] ?: ""
        }

        CiCareSdkCall.init(this).showIncoming(
            callerId = callerId,
            callerName = callerName,
            callerAvatar = callerAvatar,
            calleeId = "",
            calleeName = "",
            calleeAvatar = "",
            checkSum = "",
            metaData = metadata,
            messageActionListener = {
                Toast.makeText(this, "Hello Message", Toast.LENGTH_LONG).show()
            }
        )
    }
}
