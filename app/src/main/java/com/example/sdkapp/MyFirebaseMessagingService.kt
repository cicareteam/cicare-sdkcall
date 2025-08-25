package com.example.sdkapp

import android.util.Log
import android.widget.Toast
import cc.cicare.sdkcall.CiCareSdkCall
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MyFirebaseMessagingService : FirebaseMessagingService() {

    //@Inject lateinit var callServiceRepository: CallServiceRepository

    override fun onNewToken(token: String) {
        Log.i("SDK Call", token)
        CoroutineScope(Dispatchers.IO).launch {
            val response = ApiClient.api.saveToken(TokenSaveRequest(3, token))
            if (response.isSuccessful) {
                Log.d("SDK Call", "Token saved")
            } else {
                Log.e("SDK Call", "Token failed: ${response.code()}")
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
        val tokenAnswer = data["token"] ?: return
        val fromPhone = data["from_phone"] ?: "false"
        val server = data["server"] ?: return

        CiCareSdkCall.init(this).showIncoming(
            callerId = callerId,
            callerName = callerName,
            callerAvatar = callerAvatar,
            calleeId = "",
            calleeName = "",
            calleeAvatar = "",
            tokenCall = tokenAnswer,
            server = server,
            isFromPhone = fromPhone.toBoolean(),
            checkSum = "",
            messageActionListener = {
                Toast.makeText(this, "Hello Message", Toast.LENGTH_LONG).show()
            }
        )
    }
}
