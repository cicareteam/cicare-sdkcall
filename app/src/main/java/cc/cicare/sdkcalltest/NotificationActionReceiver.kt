package cc.cicare.sdkcalltest

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import cc.cicare.sdkcalltest.MyFirebaseMessagingService.Companion.ACTION_ANSWER
import cc.cicare.sdkcalltest.MyFirebaseMessagingService.Companion.ACTION_DECLINE
import cc.cicare.sdkcalltest.MyFirebaseMessagingService.Companion.EXTRA_TOKEN_ANSWER

/*class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val tokenAnswer = intent.getStringExtra("token_answer")

        AppState.navController?.navigate(Screen.Call.route) {
            popUpTo(AppState.navController!!.graph.startDestinationId) { inclusive = false }
        }

        if (action == "ACTION_ACCEPT" && tokenAnswer != null) {
            // Simpan tokenAnswer di shared state
            MainActivity.AppState.tokenAnswer = tokenAnswer

            // Buka activity jika belum terbuka
            val activityIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("navigate_to_call", true)
            }
            context.startActivity(activityIntent)
        }

        if (action == "ACTION_REJECT") {
            // Do reject logic here
        }
    }
}*/
class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val token = intent.getStringExtra(EXTRA_TOKEN_ANSWER) ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(token.hashCode())

        when(intent.action) {
            ACTION_ANSWER -> {
                val tokenAnswer = intent.getStringExtra(EXTRA_TOKEN_ANSWER) ?: return
                //AppState.callAnswerToken = tokenAnswer

                val i = Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("navigate_to_call", true)
                }
                ctx.startActivity(i)
            }
            ACTION_DECLINE -> {
                // Logika decline/ hangup bisa ditambahkan di sini
            }
        }
    }
}
