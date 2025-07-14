package cc.cicare.sdkcalltest

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val callerName = data["caller_name"] ?: "Unknown"
        val tokenAnswer = data["token_receive"] ?: return
        val server = data["server"] ?: return

        val intent = Intent(this, IncomingCallActivity::class.java).apply {
            putExtra("caller_name", callerName)
            putExtra("token_receive", tokenAnswer)
            putExtra("server", server)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        startActivity(intent) // ⬅️ Jalankan activity yang akan membuat notifikasi
    }



    companion object {
        const val ACTION_ANSWER = "ACTION_ANSWER"
        const val ACTION_DECLINE = "ACTION_DECLINE"
        const val EXTRA_TOKEN_ANSWER = "token_answer"
        private val ringToneUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

    }
}
