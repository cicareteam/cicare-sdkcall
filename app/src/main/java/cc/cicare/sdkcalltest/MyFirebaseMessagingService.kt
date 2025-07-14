package cc.cicare.sdkcalltest

import android.content.Intent
import android.media.RingtoneManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        val session = UserSessionManager(this)
        val userId = session.getUserId()

        if (userId != null && userId > 0) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val response = ApiClient.api.saveToken(TokenSaveRequest(userId, token))
                    if (response.isSuccessful) {
                        Log.d("FCM", "Token saved")
                    } else {
                        Log.e("FCM", "Token failed: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e("FCM", "Error saving token: ${e.message}")
                }
            }
        } else {
            Log.w("FCM", "User ID not found, cannot save token.")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val callerName = data["caller_name"] ?: "Unknown"
        val tokenAnswer = data["token_receive"] ?: return
        val fromPhone: Boolean = data["from_phone"]?.toBoolean() ?: false
        val server = data["server"] ?: return

        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra("caller_name", callerName)
            putExtra("token_receive", tokenAnswer)
            putExtra("from_phone", fromPhone)
            putExtra("server", server)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        startActivity(intent) // ⬅️ Jalankan activity yang akan membuat notifikasi
    }



    companion object {
        const val ACTION_ANSWER = "ACTION_ANSWER"
        const val ACTION_DECLINE = "ACTION_DECLINE"
        const val EXTRA_TOKEN_ANSWER = "token_answer"
        //private val ringToneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    }
}
