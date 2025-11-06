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
import cc.cicare.sdkcall.event.ConnectionStateListener
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
        "call_calling" to "Calling...",
        "call_incoming" to "Incoming Call",
        "call_ringing" to "Ringing",
        "call_connected" to "Connected",
        "call_end" to "Ended",
        "call_answer" to "Answer",
        "call_decline" to "Decline",
        "call_btn_mute" to "Mute",
        "call_btn_speaker" to "Speaker",
    )
    private var isFromPhone = false

    private var intent: Intent? = null

    private var callListener: CallStateListener? = null

    private var isConnected: Boolean = false

    public var callState: CallState? = null

    private val binder = LocalBinder()

    private lateinit var socketManager: SocketManager

    inner class LocalBinder : Binder() {
        fun getService(): IncomingCallService = this@IncomingCallService
    }

    override fun onBind(p0: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        socketManager = SocketManager()
        socketManager.setCallStateListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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

                onIncomingCall(intent)
            }
            ACTION.REJECT -> reject()
        }
        return START_STICKY
    }


    fun setCallListener(listener: CallStateListener) {
        this.callListener = listener
    }

    fun setConnectionStateListener(listener: ConnectionStateListener) {
        this.socketManager.setConnectionStateListener(listener)
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    private fun showMissedCallNotification() {

        val description = "Missed call from $callerName"

        CallNotificationManager.provideNotificationManagerCompat(
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
        startForeground(101, notification.build())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            stopForeground(STOP_FOREGROUND_DETACH)
        else
            stopForeground(false)
        stopSelf()
    }

    private fun onIncomingCall(intent: Intent) {

        val token = intent.getStringExtra("token") ?: return
        val server = intent.getStringExtra("server") ?: return
        isFromPhone = intent.getBooleanExtra("from_phone", false)
        this.intent = Intent(intent)

        initReceive(server, token, isFromPhone)
    }

    private fun showIncomingScreen(intent: Intent?) {
        intent?.let {
            val callerName = intent.getStringExtra("caller_name") ?: "unknown"
            val callerAvatar = intent.getStringExtra("caller_avatar") ?: ""
            CallNotificationManager.provideNotificationManagerIncoming(
                this, "CICARE_SDK_INCOMING",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    NotificationManager.IMPORTANCE_HIGH else Notification.PRIORITY_HIGH)
            val notification = CallNotificationManager.incomingCallNotificationBuilder(
                this,
                intent,
                "CICARE_SDK_INCOMING",
                callerName,
                callerAvatar
            )
            startForeground(1, notification.build())
        }
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) {
            intent?.let {
                startActivity(Intent(this, ScreenCallActivity::class.java).apply {
                    action = "INCOMING"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    putExtras(it)
                })
            }
        }
    }

    fun forceStop() {
        Log.i("SDK CALL", "INCOMING REMOVED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    fun reject() {
        //val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        //if (isForeground) {
            this.callListener?.onCallStateChanged(CallState.END)
        //}
        socketManager.send("REJECT", JSONObject().apply {})
        if (Build.VERSION.SDK_INT>= Build.VERSION_CODES.N)
            stopForeground(STOP_FOREGROUND_REMOVE)
        else
            stopForeground(true)
        stopSelf()
    }

    private fun initReceive(server: String, token: String, isFromPhone: Boolean?) {
        socketManager.setCallStateListener(this)
        socketManager.connect(server, token)
        if (isDeviceInCall()) {
            socketManager.send("BUSY", JSONObject().apply {
                put("caller_id", intent?.getStringExtra("caller_id"))
            })
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                showMissedCallNotification()
            }
        } else {
            socketManager.send("RINGING_CALL", JSONObject().apply {})
            this.isFromPhone = isFromPhone ?: false
        }

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
        Log.i("SDK Call", "$callState")
        this.callState = callState
        if (callState == CallState.END || callState == CallState.MISSED) {
            if (!isConnected)
                showMissedCallNotification()
            callListener?.onCallStateChanged(callState)
            isConnected = false
        } else if (callState == CallState.CONNECTED){
            isConnected = true
        }
        if ( callState == CallState.RINGING_OK) {
            this.showIncomingScreen(intent)
        }
    }

}
