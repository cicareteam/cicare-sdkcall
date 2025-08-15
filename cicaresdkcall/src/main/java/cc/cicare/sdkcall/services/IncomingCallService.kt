package cc.cicare.sdkcall.services

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import cc.cicare.sdkcall.event.CallState
import cc.cicare.sdkcall.event.CallStateListener
import cc.cicare.sdkcall.notifications.CallNotificationManager
import cc.cicare.sdkcall.notifications.ui.ScreenCallActivity
import cc.cicare.sdkcall.services.CiCareCallService.ACTION
import cc.cicare.sdkcall.signaling.SocketManager
import org.json.JSONObject
import kotlin.collections.plus

class IncomingCallService : Service() {

    private var metaData: Map<String, String> = hashMapOf(
        "initializing" to "Initializing...",
        "calling" to "Calling...",
        "incoming" to "Incoming Call",
        "ringing" to "Ringing",
        "connected" to "Connected",
        "ended" to "Ended",
        "answer" to "Answer",
        "decline" to "Decline",
        "mute" to "Mute",
        "unmute" to "Unmute",
        "speaker" to "Speaker",
    )
    private var isFromPhone = false
    private var hasActiveCall = false


    private var callListener: CallStateListener? = null

    private val binder = LocalBinder()

    private lateinit var socketManager: SocketManager

    inner class LocalBinder : Binder() {
        fun getService(): IncomingCallService = this@IncomingCallService
    }

    override fun onBind(p0: Intent?): IBinder? {
        return binder
    }

    override fun onCreate() {
        socketManager = SocketManager()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("FCM", "$hasActiveCall")
        metaData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extra = intent?.getSerializableExtra("meta_data", HashMap::class.java) as? HashMap<String, String>
            if (extra != null) HashMap(metaData + extra) else metaData
        } else {
            val extra = intent?.getSerializableExtra("meta_data") as? HashMap<String, String>
            if (extra != null) HashMap(metaData + extra) else metaData
        }
        when (intent?.action) {
            ACTION.INCOMING -> {
                if (hasActiveCall) {
                    // Sedang ada panggilan, langsung missed call
                    showMissedCallNotification(intent)
                    socketManager.send("BUSY", JSONObject().apply {
                        put("caller_id", intent.getStringExtra("caller_id"))
                    })
                } else {
                    onIncomingCall(intent)
                }
            }
            ACTION.REJECT -> reject()
        }
        return START_STICKY
    }


    fun setCallListener(listener: CallStateListener) {
        this.callListener = listener
    }

    private fun showMissedCallNotification(intent: Intent) {

        val callerName = intent.getStringExtra("caller_name") ?: "unknown"
        val callerAvatar = intent.getStringExtra("caller_avatar") ?: ""
        val description = "Missed call from $callerName"

        val notificationManager = CallNotificationManager.provideNotificationmanagerCompat(
            this, "CALL_MISSED_CHANNEL_ID", NotificationManager.IMPORTANCE_LOW
        )
        val notification = CallNotificationManager.missedCallNotificationBuilder(
            this,
            Intent(), // intent kosong karena tidak perlu action
            "CALL_MISSED_CHANNEL_ID",
            callerName,
            callerAvatar,
            description
        )
        notificationManager.notify(101, notification.build())

        socketManager.send("BUSY", JSONObject().apply {
            put("caller_name", callerName)
            put("description", description)
        })
    }

    private fun onIncomingCall(intent: Intent) {
        hasActiveCall = true;

        Log.i("FCM", "INCOMING 1 $hasActiveCall");
        val callerName = intent.getStringExtra("caller_name") ?: "unknown"
        val callerAvatar = intent.getStringExtra("caller_avatar") ?: ""
        val token = intent.getStringExtra("token") ?: return
        val server = intent.getStringExtra("server") ?: return
        isFromPhone = intent.getBooleanExtra("from_phone", false)
        CallNotificationManager.provideNotificationmanagerCompat(
            this, "CALL_INCOMING_CHANNEL_ID", NotificationManager.IMPORTANCE_HIGH)
        val notification = CallNotificationManager.incomingCallNotificationBuilder(
            this,
            intent,
            "CALL_INCOMING_CHANNEL_ID",
            callerName,
            callerAvatar
        )
//        if (isFromPhone) {
//            webRTCManager.init()
//            webRTCManager.initMic()
//        }
        startForeground(101, notification.build())
        initReceive(server, token, isFromPhone)
        Log.i("FCM", "INCOMING 2")
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) {
            startActivity(Intent(this, ScreenCallActivity::class.java).apply {
                action = "INCOMING"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtras(intent)
            })
        }
    }

    fun reject() {
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) {
            this.callListener?.onCallStateChanged(CallState.ENDED)
        }
        socketManager.send("HANGUP_REQUEST", JSONObject().apply {})
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun initReceive(server: String, token: String, isFromPhone: Boolean?) {
        socketManager.connect(server, token)
        socketManager.send("RINGING_CALL", JSONObject().apply {})

        this.isFromPhone = isFromPhone ?: false
    }

}
