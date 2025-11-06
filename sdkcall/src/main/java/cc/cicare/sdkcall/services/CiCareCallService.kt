package cc.cicare.sdkcall.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import cc.cicare.sdkcall.R
import cc.cicare.sdkcall.event.CallStateListener
import cc.cicare.sdkcall.event.CallState
import cc.cicare.sdkcall.event.ConnectionStateListener
import cc.cicare.sdkcall.notifications.CallNotificationManager
import cc.cicare.sdkcall.notifications.ui.ScreenCallActivity
import cc.cicare.sdkcall.rtc.WebRTCEventCallback
import cc.cicare.sdkcall.rtc.WebRTCManager
import cc.cicare.sdkcall.signaling.SocketManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

interface TimeTickerListener {
    fun onTimeTicketUpdate(seconds: Long)
}

@Suppress("DEPRECATION")
class CiCareCallService:
    Service(),
    CallStateListener,
    WebRTCEventCallback {

    private lateinit var webRTCManager: WebRTCManager
    private lateinit var socketManager: SocketManager
    private lateinit var eventListener: CallStateListener
    private lateinit var tickerListener: TimeTickerListener
    private var connectionListener: ConnectionStateListener? = null


    private var outgoingIntent: Intent? = null

    private var isFromPhone = false
    private var hasActiveCall = false
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    private var timerJob: Job? = null

    private var intent: Intent? = null

    var callState = MutableStateFlow("connecting")

    private var metaData: Map<String, String> = hashMapOf(
        "call_busy" to "The customer is busy and cannot be reached",
        "call_calling" to "Calling...",
        "call_connecting" to "Connecting...",
        "call_ringing" to "Ringing...",
        "call_refused" to "Decline",
        "call_end" to "End Call",
        "call_incoming" to "Incoming",
        "call_temporarily_unavailable" to "Currently unreachable",
        "call_lost_connection" to "Connection lost",
        "call_weak_signal" to "Weak Signal",
        "call_name_title" to "Xanh SM Customer",
        "call_btn_message" to "Send Message",
        "call_btn_mute" to "Mute",
        "call_btn_speaker" to "Speaker",
        "call_failed_api" to "Call failed due to system error",
        "call_failed_no_connection" to "No internet connection",
    )


    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): CiCareCallService = this@CiCareCallService
    }

    object ACTION {
        const val HANGUP = "HANGUP"
        const val ACCEPT = "ACCEPT"
        const val REJECT = "REJECT"
        const val INCOMING = "INCOMING"
        const val ONGOING = "ONGOING"
        const val OUTGOING = "OUTGOING"
//        const val MISSED_CALL = "MISSED_CALL"
//        const val BUSY = "BUSY"
    }

    companion object {
        //val CALL_CHANNEL_ID = "call_channel_id"
        const val INCOMING_CALL_ICON = android.R.drawable.sym_call_incoming
        const val ONGOING_CALL_ICON = android.R.drawable.sym_action_call
        const val MISSED_CALL_ICON = android.R.drawable.sym_call_missed
        const val OUTGOING_CALL_ICON = android.R.drawable.sym_call_outgoing
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        requestAudioFocus()
        Log.i("SDK Call", "onCreate")
        webRTCManager = WebRTCManager(this, this)
        socketManager = SocketManager()
        socketManager.setCallStateListener(this)
        socketManager.setWebrtc(webRTCManager)
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(audioAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener { /* handle focus change */ }
                .build()

            audioManager.requestAudioFocus(focusRequest)
        } else {
            audioManager.requestAudioFocus(
                { focusChange ->

                },
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private var ringbackJob: Job? = null
    private var ringbackPlayer: MediaPlayer? = null

    fun playRingback(context: Context) {
        stopRingback() // pastikan tidak double
        ringbackJob = CoroutineScope(Dispatchers.Main).launch {
            while (isActive) {
                ringbackPlayer = MediaPlayer.create(context, R.raw.tuut)
                ringbackPlayer?.start()

                // Tunggu sampai audio selesai main
                delay(ringbackPlayer?.duration?.toLong() ?: 1000L)

                // Hentikan player dan beri delay 3 detik sebelum ulang
                ringbackPlayer?.release()
                ringbackPlayer = null
                delay(3000L) // delay antar "tuut"
            }
        }
    }

    fun stopRingback() {
        ringbackJob?.cancel()
        ringbackJob = null
        ringbackPlayer?.stop()
        ringbackPlayer?.release()
        ringbackPlayer = null
    }

    fun startCallTimer() {
        timerJob?.cancel()
        timerJob = CoroutineScope(Dispatchers.Default).launch {
            var seconds = 0L
            while (isActive) {
                delay(1000)
                seconds++
                tickerListener.onTimeTicketUpdate(seconds)
            }
        }
    }

    fun stopTimer() {
        timerJob?.cancel()
    }

    fun getCallStateFlow(): StateFlow<String> = callState

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        metaData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extra = intent?.getSerializableExtra("meta_data", HashMap::class.java)?.mapNotNull {
                val key = it.key as? String
                val value = it.value as? String
                if (key != null && value != null) key to value else null
            }?.toMap() ?: emptyMap()
            HashMap(metaData + extra)
        } else {
            val extra = (intent?.getSerializableExtra("meta_data") as? HashMap<*, *>)?.mapNotNull {
                val key = it.key as? String
                val value = it.value as? String
                if (key != null && value != null) key to value else null
            }?.toMap() ?: emptyMap()
            HashMap(metaData + extra)
        }

        intent?.let { this.intent = Intent(it) }

        when(intent?.action) {
            ACTION.INCOMING -> {
                metaData["call_${callState.value}"] ?: callState.value

               }
//                if (callState.value == "CONNECTED") {
//                    // Sudah ada panggilan, langsung missed
//                    Log.i("SDK Call", "ongoing call from: ${intent.getStringExtra("callee_name")}")
//
//                    showMissedCallNotification(
//                        callerName = intent.getStringExtra("caller_name") ?: "Unknown",
//                        callerAvatar = intent.getStringExtra("caller_avatar") ?: ""

            ACTION.ONGOING -> onOngoingCall(intent)
            ACTION.ACCEPT -> answerCall(intent)
            ACTION.OUTGOING -> serviceScope.launch { onOutgoingCall(intent) }
            // ACTION.REJECT -> reject()
            ACTION.HANGUP -> hangup()
            "SCREEN" -> onScreen(intent)
        }
        return START_STICKY
    }


    private fun onScreen(intent: Intent) {
        startActivity(Intent(this, ScreenCallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtras(intent)
        })
    }

//    fun reject() {
//        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
//
//        if (isForeground) {
//            startActivity(Intent(this, ScreenCallActivity::class.java).apply {
//                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
//                action = "HANGUP"
//            })
//        } else {
//            socketManager.send("HANGUP", JSONObject().apply {})
//            if (::eventListener.isInitialized)
//                eventListener.onCallStateChanged(CallState.ENDED)
//            onCallStateChanged(CallState.ENDED)
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//                stopForeground(STOP_FOREGROUND_REMOVE)
//            } else {
//                stopForeground(true)
//            }
//            stopSelf()
//        }
//    }

//    fun hangup() {
//        socketManager.send("REQUEST_HANGUP", JSONObject().apply {})
//        if (::eventListener.isInitialized)
//            eventListener.onCallStateChanged(CallState.END)
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
//            stopForeground(STOP_FOREGROUND_REMOVE)
//        } else {
//            stopForeground(true)
//        }
//        print("SDK HANGUP");
//        stopSelf()
//        webRTCManager.close()
//        socketManager.disconnect()
//        stopRingback()
//        stopTimer()
//        stopForeground(true)
//        stopSelf()
//    }
fun hangup() {
    try {
        // 1️⃣ Kirim sinyal ke server
        socketManager.send("REQUEST_HANGUP", JSONObject())

        // 2️⃣ Update UI / listener call state
        if (::eventListener.isInitialized) {
            eventListener.onCallStateChanged(CallState.END)
        }

        // 3️⃣ Hentikan semua audio & timer
        stopRingback()
        stopTimer()

        // 4️⃣ Tutup WebRTC & signaling
        webRTCManager.close()
        socketManager.disconnect()

        // 5️⃣ Hentikan notifikasi foreground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }

        // 6️⃣ Log dan hentikan service
        Log.i("SDK CALL", "Hangup pressed, service stopping")
        stopSelf()

    } catch (e: Exception) {
        Log.e("SDK CALL", "Error while hanging up: ${e.message}", e)
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

    fun setMute(isMuted: Boolean) {
        webRTCManager.setMicEnabled(isMuted)
    }

    fun setSpeaker(isSpeakerOn: Boolean) {
        webRTCManager.setAudioOutputToSpeaker(isSpeakerOn)
    }

    suspend fun initCall(server: String, token: String) {
        Log.i("SDK CALL", "init call service")
        webRTCManager.init()
        webRTCManager.initMic()
        socketManager.connect(server, token)
        socketManager.send("INIT_CALL", JSONObject().apply {})
    }

    fun answerCall(intent: Intent, fromScreen: Boolean? = false) {
        val isForeground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (fromScreen != true && !isForeground) {
            startActivity(Intent(this, ScreenCallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                action = "ANSWER"
                putExtras(intent)
            })
        }
        this.intent = Intent(intent)
        val server = intent.getStringExtra("server") ?: ""
        val token = intent.getStringExtra("token") ?: ""
        socketManager.connect(server, token)
        socketManager.send("ANSWER_CALL", JSONObject())
        Log.i("SDK CALL", "answer call service " + server + " " + token)
    }

    suspend fun ackAnswer() {
        val sdp: SessionDescription?
        var event = "SDP_OFFER"
        if (this.isFromPhone) {
            sdp = webRTCManager.createAnswer()
            event = "SDP_ANSWER"
        } else {
            webRTCManager.init()
            webRTCManager.initMic()
            sdp = webRTCManager.createOffer()
        }
        Log.i("SDK CALL", "ack answer")
        socketManager.send(event, JSONObject().apply {
            put("sdp", JSONObject().apply {
                put("type", sdp.type.toString())
                put("sdp", sdp.description)
            })
        })
    }


    @SuppressLint("MissingPermission")
    private fun onOutgoingCall(intent: Intent) {
        outgoingIntent = intent
        //val callType = intent.getStringExtra("call_type") ?: "outgoing"
        val calleeName = intent.getStringExtra("callee_name") ?: "unknown"
        val calleeAvatar = intent.getStringExtra("callee_avatar") ?: ""
        CallNotificationManager.provideNotificationManagerCompat(this,
            "CALL_OUTGOING_CHANNEL_ID",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationManager.IMPORTANCE_LOW
            } else Notification.PRIORITY_LOW)
        val notification = CallNotificationManager.outgoingCallNotificationBuilder(
            this,
            intent,
            "CALL_OUTGOING_CHANNEL_ID",
            metaData["call_${callState.value}"] ?: callState.value,
            calleeName,
            calleeAvatar
        )

        startForeground(101, notification.build())
        /*startActivity(Intent(this, ScreenCallActivity::class.java).apply {
            action = ACTION.OUTGOING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtras(intent)
        })*/
    }

    fun cancelCall() {
        Log.i("SDK CALL", "CANCEL")
        socketManager.send("CANCEL", JSONObject().apply {})
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            stopForeground(STOP_FOREGROUND_REMOVE)
        else
            stopForeground(true)
        stopSelf()
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun outgoingCallStateUpdate(callState: String) {

        val notificationManager = CallNotificationManager.provideNotificationManagerCompat(this,
            "CALL_OUTGOING_CHANNEL_ID",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationManager.IMPORTANCE_LOW
            } else Notification.PRIORITY_LOW)
        this.callState.value = callState
        val calleeName = outgoingIntent?.getStringExtra("callee_name") ?: "unknown"
        val calleeAvatar = outgoingIntent?.getStringExtra("callee_avatar") ?: ""
        val notification = CallNotificationManager.outgoingCallNotificationBuilder(
            this,
            outgoingIntent!!,
            "CALL_OUTGOING_CHANNEL_ID",
            metaData[callState] ?: callState,
            calleeName,
            calleeAvatar
        )

        notificationManager.notify(101, notification.build())
    }

    private fun onOngoingCall(intent: Intent) {
        val callType = intent.getStringExtra("call_type") ?: "outgoing"
        val callerName =
            if (callType == "incoming") intent.getStringExtra("caller_name") else intent.getStringExtra("callee_name")
        val callerAvatar =
            if (callType == "incoming") intent.getStringExtra("caller_avatar") else intent.getStringExtra("callee_avatar")
        CallNotificationManager.provideNotificationManagerCompat(this,
            "CALL_ONGOING_CHANNEL_ID",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                NotificationManager.IMPORTANCE_LOW
            } else Notification.PRIORITY_LOW)
        val notification = CallNotificationManager.ongoingCallNotificationBuilder(
            this,
            intent,
            "CALL_ONGOING_CHANNEL_ID",
            callerName ?: "unknown",
            callerAvatar ?: ""
        )
        eventListener.onCallStateChanged(CallState.CONNECTED)
        startForeground(101, notification.build())
        startCallTimer()
    }

    fun setCallEventListener(eventListener: CallStateListener) {
        this.eventListener = eventListener
    }

    fun setTickerListener(eventListener: TimeTickerListener) {
        this.tickerListener = eventListener
    }

    fun setConnectionStateListener(connectionListener: ConnectionStateListener) {
        this.connectionListener = connectionListener
        this.socketManager.setConnectionStateListener(connectionListener)
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
            stopForeground(STOP_FOREGROUND_REMOVE)
        else
            stopForeground(true)
        //signaling.close()
        webRTCManager.close()
        socketManager.disconnect()
        super.onDestroy()
    }

    /**
     * Callback when local SDP offer/answer has been created.
     *
     * @param sdp The session description created.
     */
    override fun onLocalSdpCreated(sdp: SessionDescription) {
        socketManager.send("SDP_OFFER", JSONObject().apply {
            put("type", sdp.type)
            put("sdp", sdp.description)
        })
    }

    /**
     * Callback when an ICE candidate is generated.
     *
     * @param candidate The ICE candidate to be sent to remote peer.
     */
    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        socketManager.send("ICE_CANDIDATE", JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        })
    }

    /**
     * Callback when a remote media stream is received.
     *
     * @param stream The received remote media stream.
     */
    override fun onRemoteStreamReceived(stream: MediaStream) {
        if (stream.audioTracks.isNotEmpty()) {
            val remoteAudioTrack = stream.audioTracks[0]
            remoteAudioTrack.setEnabled(true)
        }
    }

    /**
     * Callback when the connection state changes.
     *
     * @param state The new connection state.
     */
    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        if (::eventListener.isInitialized)
            eventListener.onConnectionStateChanged(state)
    }

    @SuppressLint("MissingPermission")
    override fun onCallStateChanged(callState: CallState) {
        if (::eventListener.isInitialized)
            eventListener.onCallStateChanged(callState)

        this@CiCareCallService.callState.value = callState.toString().lowercase()

        hasActiveCall = when(callState) {
            CallState.CALLING, CallState.RINGING, CallState.ANSWERING,
            CallState.CONNECTING, CallState.CONNECTED -> true
            else -> false
        }

        when(callState) {
            CallState.CALLING -> {
                playRingback(this)
                outgoingCallStateUpdate(this@CiCareCallService.callState.value)
            }
            CallState.ANSWERING -> serviceScope.launch {
                ackAnswer()
            }
            CallState.RINGING ->  {
                outgoingCallStateUpdate(this@CiCareCallService.callState.value)
                playRingback(this)
                Log.i("CALL", "RINGING")
            }
            CallState.CONNECTING -> outgoingCallStateUpdate(this@CiCareCallService.callState.value)
            CallState.BUSY -> {
                stopRingback()
                outgoingCallStateUpdate(this@CiCareCallService.callState.value)
                this.eventListener.onCallStateChanged(callState)
                stopSelf()
            }
            CallState.REFUSED -> {
                stopRingback()
                outgoingCallStateUpdate(this@CiCareCallService.callState.value)
                this.eventListener.onCallStateChanged(callState)
                stopSelf()
            }
            CallState.CONNECTED -> {
                stopRingback()
                intent?.let { onOngoingCall(it) }
            }
            CallState.TIMEOUT -> {
                stopRingback()
                stopTimer()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                else
                    stopForeground(true)
                stopSelf()
            }
            CallState.END -> {
                stopRingback()
                stopTimer()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                else
                    stopForeground(true)
                stopSelf()
            }

            CallState.RINGING_OK -> {}
            CallState.MISSED -> {}
        }
    }

    override fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED -> {
                connectionListener?.onSignalStateChanged("connected")
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                connectionListener?.onSignalStateChanged("weak_signal")
            }
            PeerConnection.IceConnectionState.FAILED -> {
                connectionListener?.onSignalStateChanged("lost")
            }
            PeerConnection.IceConnectionState.CLOSED -> {
                connectionListener?.onSignalStateChanged("")
            }
            else -> {
                Log.d("WebRTC", "ICE State: $state")
            }
        }
    }


}