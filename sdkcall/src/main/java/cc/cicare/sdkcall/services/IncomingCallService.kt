package cc.cicare.sdkcall.services

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
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

    var callState: CallState? = null
    private var hasBeenConnected = false

    private val binder = LocalBinder()

    private lateinit var socketManager: SocketManager

    inner class LocalBinder : Binder() {
        fun getService(): IncomingCallService = this@IncomingCallService
    }

    override fun onBind(p0: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        socketManager = SocketManager()
        socketManager.setCallStateListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.w("SDK CALL", "IncomingCallService restarted with null intent, stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        metaData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extra = intent.getSerializableExtra("meta_data", HashMap::class.java)
                ?.mapNotNull {
                    val key = it.key as? String
                    val value = it.value as? String
                    if (key != null && value != null) key to value else null
                }?.toMap() ?: emptyMap()
            HashMap(metaData + extra)
        } else {
            val extra = (intent.getSerializableExtra("meta_data") as? HashMap<*, *>)
                ?.mapNotNull {
                    val key = it.key as? String
                    val value = it.value as? String
                    if (key != null && value != null) key to value else null
                }?.toMap() ?: emptyMap()
            HashMap(metaData + extra)
        }
        intent.let { this.intent = Intent(it) }

        // Always ensure caller info is captured if present in the current intent
        intent.getStringExtra("caller_name")?.let { callerName = it }
        intent.getStringExtra("caller_avatar")?.let { callerAvatar = it }

        // Immediately start foreground to avoid ForegroundServiceDidNotStartInTimeException.
        // Must be called within ~5 seconds of startForegroundService(), before any async work.
        ensureForeground(intent)

        when (intent.action) {
            ACTION.INCOMING -> {
                onIncomingCall(intent)
                showIncomingScreen(intent) // <-- show incoming call screen and notification without waiting network
            }
            ACTION.REJECT -> {
                Log.i("SDK CALL", "ACTION.REJECT received")
                reject()
            }
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
    fun showMissedCallNotification() {
        val action = intent?.action
        if (action != ACTION.INCOMING && action != ACTION.REJECT) {
            Log.i("SDK CALL", "Skipping missed call notification because action is $action")
            stopSelf()
            return
        }
        if (hasBeenConnected) {
            Log.i("SDK CALL", "Skipping missed call notification because call was already connected")
            stopSelf()
            return
        }
        Log.i("SDK CALL", "showMissedCallNotification called for $callerName")
        
        // Ensure we have the latest info from the intent if fields are null
        if (callerName == null) callerName = intent?.getStringExtra("caller_name")
        if (callerAvatar == null) callerAvatar = intent?.getStringExtra("caller_avatar")
        
        val description = "Missed call from ${callerName ?: "unknown"}"

        CallNotificationManager.provideNotificationManagerCompat(
            this, "CALL_MISSED_CHANNEL_CICARE",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                NotificationManager.IMPORTANCE_MAX else Notification.PRIORITY_MAX
        )
        val notification = CallNotificationManager.missedCallNotificationBuilder(
            this,
            "CALL_MISSED_CHANNEL_CICARE",
            callerName ?: "unknown",
            callerAvatar ?: "",
            description
        )

        // Important: clear foreground state first to ensure the "Incoming Call" UI is removed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }

        // Now post the "Missed Call" notification using ID 104 to replace the previous UI
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(104, notification.build())

        Log.i("SDK CALL", "Missed call notification posted, stopping service")
        stopSelf()
    }

    /**
     * Immediately promotes this service to foreground.
     * Must be called synchronously inside onStartCommand() to satisfy the
     * 5-second startForeground() deadline imposed by Android.
     */
    private fun ensureForeground(intent: Intent) {
        val name = callerName ?: "unknown"
        val avatar = callerAvatar ?: ""
        CallNotificationManager.provideNotificationManagerIncoming(
            this, "CICARE_SDK_INCOMING",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                NotificationManager.IMPORTANCE_HIGH else Notification.PRIORITY_HIGH
        )
        val notification = CallNotificationManager.incomingCallNotificationBuilder(
            this, intent, "CICARE_SDK_INCOMING", name, avatar
        )
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(104, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(104, notification.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
            } else {
                startForeground(104, notification.build())
            }
        } catch (e: Exception) {
            Log.e("SDK CALL", "startForeground failed: ${e.message}")
            stopSelf()
        }
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
            // Update notification (service is already foreground via ensureForeground)
            val name = it.getStringExtra("caller_name") ?: "unknown"
            val avatar = it.getStringExtra("caller_avatar") ?: ""
            CallNotificationManager.provideNotificationManagerIncoming(
                this, "CICARE_SDK_INCOMING",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    NotificationManager.IMPORTANCE_HIGH else Notification.PRIORITY_HIGH
            )
            val notification = CallNotificationManager.incomingCallNotificationBuilder(
                this, it, "CICARE_SDK_INCOMING", name, avatar
            )
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(104, notification.build())
        }
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (isForeground) {
            intent?.let {
                startActivity(Intent(this, ScreenCallActivity::class.java).apply {
                    action = "INCOMING"
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
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
        Log.i("SDK CALL", "REJECT called in IncomingCallService")
        // Always send REJECT to server first to stop caller ringing. 
        // Include caller_id so the server can identify which call to terminate.
        socketManager.send("REJECT", JSONObject().apply {
            put("caller_id", intent?.getStringExtra("caller_id"))
        })
        
        // Delegate to onCallStateChanged to handle notification replacement and service stopping
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        onCallStateChanged(CallState.END)
    }

    private fun initReceive(server: String, token: String, isFromPhone: Boolean?) {
        socketManager.setCallStateListener(this)
        socketManager.connect(server, token)
        if (isDeviceInCall()) {
            socketManager.send("BUSY", JSONObject().apply {
                put("caller_id", intent?.getStringExtra("caller_id"))
            })
            // If already in a call, show missed call notification immediately (if we have permission)
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                showMissedCallNotification()
            } else {
                stopSelf()
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
        Log.i("SDK Call", "onCallStateChanged: $callState")
        this.callState = callState
        if (callState == CallState.END || callState == CallState.MISSED || callState == CallState.TIMEOUT) {
            if (!isConnected && !hasBeenConnected)
                showMissedCallNotification()
            callListener?.onCallStateChanged(callState)
            isConnected = false
        } else if (callState == CallState.CONNECTED){
            isConnected = true
            hasBeenConnected = true
        }
        // if ( callState == CallState.RINGING_OK) {
            //this.showIncomingScreen(intent)
        // }
    }

}
