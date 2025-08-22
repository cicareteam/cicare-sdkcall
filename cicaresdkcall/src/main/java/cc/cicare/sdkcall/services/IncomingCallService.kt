package cc.cicare.sdkcall.services

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import cc.cicare.sdkcall.event.CallState
import cc.cicare.sdkcall.event.CallStateListener
import cc.cicare.sdkcall.notifications.CallNotificationManager
import cc.cicare.sdkcall.notifications.ui.ScreenCallActivity
import cc.cicare.sdkcall.services.CiCareCallService.ACTION
import cc.cicare.sdkcall.signaling.SocketManager
import org.json.JSONObject
import org.webrtc.PeerConnection
import kotlin.collections.plus

@Suppress("DEPRECATION")
class IncomingCallService : Service(), CallStateListener {

    private var callerName: String? = null
    private var callerAvatar: String? = null
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
        socketManager.setCallStateListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("SDK Call", "$hasActiveCall")
        metaData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extra = intent?.getSerializableExtra("meta_data", HashMap::class.java)
                ?.mapNotNull {
                    val key = it.key as? String
                    val value = it.value as? String
                    if (key != null && value != null) key to value else null
                }?.toMap() ?: emptyMap()
            HashMap(metaData + extra)
        } else {
            val extra = (intent?.getSerializableExtra("meta_data") as? HashMap<*, *>)
                ?.mapNotNull {
                    val key = it.key as? String
                    val value = it.value as? String
                    if (key != null && value != null) key to value else null
                }?.toMap() ?: emptyMap()
            HashMap(metaData + extra)
        }
        when (intent?.action) {
            ACTION.INCOMING -> {
                callerName = intent.getStringExtra("caller_name") ?: "unknown"
                callerAvatar = intent.getStringExtra("caller_avatar") ?: ""
                if (isDeviceInCall()) {
                    if (ActivityCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        showMissedCallNotification()
                    }
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

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showMissedCallNotification() {

        val description = "Missed call from $callerName"

        val notificationManager = CallNotificationManager.provideNotificationManagerCompat(
            this, "CALL_MISSED_CHANNEL_ID",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            NotificationManager.IMPORTANCE_LOW else Notification.PRIORITY_LOW
        )
        val notification = CallNotificationManager.missedCallNotificationBuilder(
            this,
            "CALL_MISSED_CHANNEL_ID",
            callerName ?: "unknown",
            callerAvatar ?: "",
            description
        )
        notificationManager.notify(101, notification.build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            stopForeground(STOP_FOREGROUND_DETACH)
        else
            stopForeground(false)
        stopSelf()
    }

    private fun onIncomingCall(intent: Intent) {
        val callerName = intent.getStringExtra("caller_name") ?: "unknown"
        val callerAvatar = intent.getStringExtra("caller_avatar") ?: ""
        val token = intent.getStringExtra("token") ?: return
        val server = intent.getStringExtra("server") ?: return
        isFromPhone = intent.getBooleanExtra("from_phone", false)
        CallNotificationManager.provideNotificationManagerIncoming(
            this, "CALL_INCOMING_CHANNEL_ID",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                NotificationManager.IMPORTANCE_HIGH else Notification.PRIORITY_LOW)
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
        Log.i("SDK Call", "INCOMING 2")
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) {
            startActivity(Intent(this, ScreenCallActivity::class.java).apply {
                action = "INCOMING"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                putExtras(intent)
            })
        }
    }

    fun forceStop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    fun reject() {
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) {
            this.callListener?.onCallStateChanged(CallState.ENDED)
        }
        socketManager.send("REJECT", JSONObject().apply {})
        if (Build.VERSION.SDK_INT>= Build.VERSION_CODES.N)
            stopForeground(STOP_FOREGROUND_REMOVE)
        else
            stopForeground(true)
        stopSelf()
    }

    private fun initReceive(server: String, token: String, isFromPhone: Boolean?) {
        socketManager.connect(server, token)
        socketManager.send("RINGING_CALL", JSONObject().apply {})

        this.isFromPhone = isFromPhone ?: false
    }

    private fun isDeviceInCall(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            == PackageManager.PERMISSION_GRANTED
        ) {
            val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
            val telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager

            // cek call telepon biasa
            val isTelephonyCall = telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE

            // cek call dari VoIP (WA, Telegram, dll)
            val isVoipCall = (audioManager.mode == AudioManager.MODE_IN_CALL ||
                    audioManager.mode == AudioManager.MODE_IN_COMMUNICATION)

            return isTelephonyCall || isVoipCall
        }
        return false
    }

    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        Log.i("SDK Call", "$state")
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    override fun onCallStateChanged(callState: CallState) {
        if (callState == CallState.ENDED) {
            showMissedCallNotification()
        }
    }

}
