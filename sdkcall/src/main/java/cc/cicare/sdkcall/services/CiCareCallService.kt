package cc.cicare.sdkcall.services

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import cc.cicare.sdkcall.R
import cc.cicare.sdkcall.event.CallState
import cc.cicare.sdkcall.event.CallStateListener
import cc.cicare.sdkcall.event.ConnectionStateListener
import cc.cicare.sdkcall.notifications.CallNotificationManager
import cc.cicare.sdkcall.notifications.ui.ScreenCallActivity
import cc.cicare.sdkcall.rtc.WebRTCEventCallback
import cc.cicare.sdkcall.rtc.WebRTCManager
import cc.cicare.sdkcall.signaling.SocketManager
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

interface TimeTickerListener {
    fun onTimeTicketUpdate(seconds: Long)
}

@Suppress("DEPRECATION")
class CiCareCallService : Service(), CallStateListener, WebRTCEventCallback {

    private lateinit var webRTCManager: WebRTCManager
    private lateinit var socketManager: SocketManager
    private var eventListener: CallStateListener? = null
    private var tickerListener: TimeTickerListener? = null
    private var connectionListener: ConnectionStateListener? = null

    private var callName: String? = null

    private var outgoingIntent: Intent? = null

    private var isFromPhone = false
    private var hasActiveCall = false
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var timerJob: Job? = null

    private var reconnectAttempt = 0

    private var intent: Intent? = null

    var callState = MutableStateFlow("connecting")

    private var isClosed = false
    private var keepNotificationOnStop = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private var metaData: Map<String, String> =
            hashMapOf(
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
        // val CALL_CHANNEL_ID = "call_channel_id"
        const val INCOMING_CALL_ICON = android.R.drawable.sym_call_incoming
        const val ONGOING_CALL_ICON = android.R.drawable.sym_action_call
        const val MISSED_CALL_ICON = android.R.drawable.sym_call_missed
        const val OUTGOING_CALL_ICON = android.R.drawable.sym_call_outgoing
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        requestAudioFocus()
        Log.i("SDK Call", "onCreate")
        webRTCManager = WebRTCManager(this, this)
        socketManager = SocketManager()
        socketManager.setCallStateListener(this)
        socketManager.setWebrtc(webRTCManager)
        acquireWakeLock()
        keepWifiOn()
    }

    private fun requestAudioFocus() {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes =
                    AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()

            val focusRequest =
                    AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(audioAttributes)
                            .setAcceptsDelayedFocusGain(false)
                            .setOnAudioFocusChangeListener { /* handle focus change */}
                            .build()

            audioManager.requestAudioFocus(focusRequest)
        } else {
            audioManager.requestAudioFocus(
                    { focusChange -> },
                    AudioManager.STREAM_VOICE_CALL,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
            )
        }
    }

    private var ringbackJob: Job? = null
    private var ringbackPlayer: MediaPlayer? = null
    private var ringingTimeoutJob: Job? = null // Timer 30 detik untuk auto-hangup saat ringing

    fun playRingback(context: Context) {
        stopRingback() // pastikan tidak double
        ringbackJob =
                serviceScope.launch {
                    while (isActive) {
                        var player: MediaPlayer? = null
                        try {
                            val attr =
                                    AudioAttributes.Builder()
                                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                            .build()
                            player =
                                    MediaPlayer().apply {
                                        setAudioAttributes(attr)
                                        val afd = context.resources.openRawResourceFd(R.raw.tuut)
                                        setDataSource(
                                                afd.fileDescriptor,
                                                afd.startOffset,
                                                afd.length
                                        )
                                        afd.close()
                                        prepare()
                                        start()
                                    }
                            ringbackPlayer = player
                            delay(player.duration.toLong())
                        } catch (e: Exception) {
                            Log.e("SDK CALL", "Failed to play ringback: ${e.message}")
                        } finally {
                            try { player?.release() } catch (_: Exception) {}
                            ringbackPlayer = null
                        }
                        delay(3000L)
                    }
                }
    }

    fun stopRingback() {
        ringbackJob?.cancel()
        ringbackJob = null
        val player = ringbackPlayer
        ringbackPlayer = null
        try { player?.reset() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
    }

    fun startCallTimer() {
        timerJob?.cancel()
        timerJob =
                serviceScope.launch {
                    var seconds = 0L
                    while (isActive) {
                        delay(1000)
                        seconds++
                        withContext(Dispatchers.Main) {
                            tickerListener?.onTimeTicketUpdate(seconds)
                        }
                    }
                }
    }

    fun stopTimer() {
        timerJob?.cancel()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock =
                powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "CiCareCallService::CallWakeLock"
                )
        wakeLock?.acquire(60 * 60 * 1000L) // 1 jam, bisa diperpanjang
    }
    @Synchronized
    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        wakeLock = null  // ← null dulu, baru release
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.w("SDK CALL", "WakeLock release failed: ${e.message}")
        }
    }

    private fun keepWifiOn() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock =
                wifiManager.createWifiLock(
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "CiCareCallService::WifiLock"
                )
        wifiLock?.acquire()
    }

    @Synchronized
    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        wifiLock = null  // ← sama
        try {
            if (lock.isHeld) lock.release()
        } catch (e: Exception) {
            Log.w("SDK CALL", "WifiLock release failed: ${e.message}")
        }
    }

    fun getCallStateFlow(): StateFlow<String> = callState

    fun sendDTMF(digits: String) {
        webRTCManager.setDTMF(digits)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            Log.w("SDK CALL", "Service restarted with null intent, stopping self")
            stopSelf()
            return START_NOT_STICKY
        }
        isClosed = false
        metaData =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val extra =
                            intent?.getSerializableExtra("meta_data", HashMap::class.java)
                                    ?.mapNotNull {
                                        val key = it.key as? String
                                        val value = it.value as? String
                                        if (key != null && value != null) key to value else null
                                    }
                                    ?.toMap()
                                    ?: emptyMap()
                    HashMap(metaData + extra)
                } else {
                    val extra =
                            (intent?.getSerializableExtra("meta_data") as? HashMap<*, *>)
                                    ?.mapNotNull {
                                        val key = it.key as? String
                                        val value = it.value as? String
                                        if (key != null && value != null) key to value else null
                                    }
                                    ?.toMap()
                                    ?: emptyMap()
                    HashMap(metaData + extra)
                }

        intent.let { this.intent = Intent(it) }

        when (intent.action) {
            ACTION.INCOMING -> {
                // No-op or handle incoming action specifically if needed
            }
            //                if (callState.value == "CONNECTED") {
            //                    // Sudah ada panggilan, langsung missed
            //                    Log.i("SDK Call", "ongoing call from:
            // ${intent.getStringExtra("callee_name")}")
            //
            //                    showMissedCallNotification(
            //                        callerName = intent.getStringExtra("caller_name") ?:
            // "Unknown",
            //                        callerAvatar = intent.getStringExtra("caller_avatar") ?: ""

            ACTION.ONGOING -> onOngoingCall(intent)
            ACTION.ACCEPT -> answerCall(intent)
            ACTION.OUTGOING -> serviceScope.launch { onOutgoingCall(intent) }
            ACTION.REJECT -> reject()
            ACTION.HANGUP -> hangup()
            "SCREEN" -> onScreen(intent)
        }
        return START_STICKY
    }

    private fun onScreen(intent: Intent) {
        startActivity(
                Intent(this, ScreenCallActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtras(intent)
                }
        )
    }

    fun hangup() {
        isClosed = true
        if (callState.value.toLowerCase(Locale.ROOT) == "end") return
        callState.value = "end"
        try {
            // 1️⃣ Kirim sinyal ke server
            socketManager.send("REQUEST_HANGUP", JSONObject())

            // 2️⃣ Update UI / listener call state
            eventListener?.onCallStateChanged(CallState.END)

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
            forceStop()
        } catch (e: Exception) {
            Log.e("SDK CALL", "Error while hanging up: ${e.message}", e)
        }
    }

    fun forceStop() {
        isClosed = true
        ringingTimeoutJob?.cancel()
        ringingTimeoutJob = null
        releaseWakeLock()
        releaseWifiLock()
        stopRingback()
        stopTimer()
        serviceScope.cancel()
        webRTCManager.close()
        socketManager.disconnect()
        if (keepNotificationOnStop && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        forceStop()
        super.onDestroy()
    }

    fun setMute(isMuted: Boolean) {
        webRTCManager.setMicEnabled(isMuted)
        /*socketManager.send("MUTE", JSONObject().apply {
            put("mute", isMuted)
        })*/
    }

    fun setSpeaker(isSpeakerOn: Boolean) {
        webRTCManager.setAudioOutputToSpeaker(isSpeakerOn)
    }

    suspend fun initCall(server: String, token: String) {
        webRTCManager.init()
        webRTCManager.initMic()
        socketManager.connect(server, token)
        socketManager.send("INIT_CALL", JSONObject().apply {})
    }

    fun reject() {
        isClosed = true
        if (callState.value.lowercase(Locale.ROOT) == "end") return
        callState.value = "end"
        try {
            // Send REJECT signal instead of REQUEST_HANGUP
            socketManager.send(
                    "REJECT",
                    JSONObject().apply { put("caller_id", intent?.getStringExtra("caller_id")) }
            )

            eventListener?.onCallStateChanged(CallState.END)

            stopRingback()
            stopTimer()

            webRTCManager.close()
            socketManager.disconnect()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                stopForeground(true)
            }

            Log.i("SDK CALL", "Reject called, service stopping")
            forceStop()
        } catch (e: Exception) {
            Log.e("SDK CALL", "Error while rejecting: ${e.message}", e)
        }
    }
    fun answerCall(intent: Intent, fromScreen: Boolean? = false) {
        val isForeground =
                ProcessLifecycleOwner.get()
                        .lifecycle
                        .currentState
                        .isAtLeast(Lifecycle.State.STARTED)
        if (fromScreen != true && !isForeground) {
            startActivity(
                    Intent(this, ScreenCallActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        action = "ANSWER"
                        putExtras(intent)
                    }
            )
        }
        this.intent = Intent(intent)

        val server = intent.getStringExtra("server") ?: ""
        val token = intent.getStringExtra("token") ?: ""
        socketManager.connect(server, token)
        socketManager.send("ANSWER_CALL", JSONObject())
        Log.i("SDK CALL", "answer call service " + server + " " + token)
    }

    suspend fun ackAnswer() {
        if (!hasMicrophonePermission()) {
            Log.w("SDK CALL", "Microphone permission missing in ackAnswer. " +
                    "ScreenCallActivity should have ensured permission before calling answerCall().")
            // Don't reject - ScreenCallActivity handles permission flow.
            // If we reach here without mic, it's unexpected, but we should not crash the call.
        }
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
        socketManager.send(
                event,
                JSONObject().apply {
                    put(
                            "sdp",
                            JSONObject().apply {
                                put("type", sdp.type.toString())
                                put("sdp", sdp.description)
                            }
                    )
                }
        )
    }

    private fun hasMicrophonePermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    private fun onOutgoingCall(intent: Intent) {
        outgoingIntent = intent
        this.intent = intent
        // val callType = intent.getStringExtra("call_type") ?: "outgoing"
        val calleeName = intent.getStringExtra("callee_name") ?: "unknown"
        callName = calleeName
        val calleeAvatar = intent.getStringExtra("callee_avatar") ?: ""
        CallNotificationManager.provideNotificationManagerCompat(
                this,
                "CALL_OUTGOING_CICARE",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationManager.IMPORTANCE_HIGH
                } else Notification.PRIORITY_HIGH
        )
        val notification =
                CallNotificationManager.outgoingCallNotificationBuilder(
                        this,
                        intent,
                        "CALL_OUTGOING_CICARE",
                        metaData["call_${callState.value}"] ?: callState.value,
                        calleeName,
                        calleeAvatar
                )

        try {
            if (Build.VERSION.SDK_INT >= 34) { // Android 14+
                startForeground(
                        104,
                        notification.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                startForeground(
                        104,
                        notification.build(),
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(104, notification.build())
            }
        } catch (e: Exception) {
            Log.e("SDK CALL", "Failed to start foreground service in onOutgoingCall: ${e.message}")
        }
        /*startActivity(Intent(this, ScreenCallActivity::class.java).apply {
            action = ACTION.OUTGOING
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            putExtras(intent)
        })*/
    }

    fun cancelCall() {
        isClosed = true
        socketManager.send("CANCEL", JSONObject().apply {})
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else stopForeground(true)
        stopSelf()
    }

    @RequiresPermission(Manifest.permission.POST_NOTIFICATIONS)
    fun outgoingCallStateUpdate(callState: String) {
        val safeOutgoingIntent = outgoingIntent ?: run {
            Log.w("SDK CALL", "outgoingIntent is null, skipping notification update")
            return
        }

        val notificationManager =
                CallNotificationManager.provideNotificationManagerCompat(
                        this,
                        "CALL_OUTGOING_CICARE",
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                            NotificationManager.IMPORTANCE_DEFAULT
                        } else Notification.PRIORITY_DEFAULT
                )
        this.callState.value = callState
        val calleeName = safeOutgoingIntent.getStringExtra("callee_name") ?: "unknown"
        val calleeAvatar = safeOutgoingIntent.getStringExtra("callee_avatar") ?: ""
        val statusText = metaData["call_$callState"] ?: callState
        val isTerminalState = callState in listOf("busy", "refused", "failed", "timeout")
        val notification = if (isTerminalState) {
            keepNotificationOnStop = true
            CallNotificationManager.terminalCallNotificationBuilder(
                this,
                "CALL_OUTGOING_CICARE",
                calleeName,
                calleeAvatar,
                statusText
            )
        } else {
            CallNotificationManager.outgoingCallNotificationBuilder(
                this,
                safeOutgoingIntent,
                "CALL_OUTGOING_CICARE",
                statusText,
                calleeName,
                calleeAvatar
            )
        }

        notificationManager.notify(104, notification.build())
    }

    private fun onOngoingCall(intent: Intent) {
        val callType = intent.getStringExtra("call_type") ?: "outgoing"
        val callerName =
                if (callType == "incoming") intent.getStringExtra("caller_name")
                else intent.getStringExtra("callee_name")
        val callerAvatar =
                if (callType == "incoming") intent.getStringExtra("caller_avatar")
                else intent.getStringExtra("callee_avatar")
        CallNotificationManager.provideNotificationManagerCompat(
                this,
                "CALL_ONGOING_CICARE",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationManager.IMPORTANCE_DEFAULT
                } else Notification.PRIORITY_DEFAULT
        )
        val notification =
                CallNotificationManager.ongoingCallNotificationBuilder(
                        this,
                        intent,
                        "CALL_ONGOING_CICARE",
                        callerName ?: this.callName ?: "unknown",
                        callerAvatar ?: ""
                )
        eventListener?.onCallStateChanged(CallState.CONNECTED)
        val foregroundType = when {
            Build.VERSION.SDK_INT >= 34 -> {
                val hasMic = hasMicrophonePermission()
                if (hasMic)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                else
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ->
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            else -> -1
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && foregroundType != -1) {
                startForeground(104, notification.build(), foregroundType)
            } else {
                startForeground(104, notification.build())
            }
        } catch (e: Exception) {
            Log.e("SDK CALL", "Failed to start foreground service in onOngoingCall: ${e.message}")
        }
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

    /**
     * Callback when local SDP offer/answer has been created.
     *
     * @param sdp The session description created.
     */
    override fun onLocalSdpCreated(sdp: SessionDescription) {
        socketManager.send(
                "SDP_OFFER",
                JSONObject().apply {
                    put("type", sdp.type)
                    put("sdp", sdp.description)
                }
        )
    }

    /**
     * Callback when an ICE candidate is generated.
     *
     * @param candidate The ICE candidate to be sent to remote peer.
     */
    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        socketManager.send(
                "ICE_CANDIDATE",
                JSONObject().apply {
                    put("sdpMid", candidate.sdpMid)
                    put("sdpMLineIndex", candidate.sdpMLineIndex)
                    put("candidate", candidate.sdp)
                }
        )
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
        eventListener?.onConnectionStateChanged(state)
    }

    @SuppressLint("MissingPermission")
    override fun onCallStateChanged(callState: CallState) {
        eventListener?.onCallStateChanged(callState)

        this@CiCareCallService.callState.value = callState.toString().lowercase()

        hasActiveCall =
                when (callState) {
                    CallState.CALLING,
                    CallState.RINGING,
                    CallState.ANSWERING,
                    CallState.CONNECTING,
                    CallState.CONNECTED -> true
                    else -> false
                }

        when (callState) {
            CallState.CALLING -> {
                playRingback(this)
                outgoingCallStateUpdate(this@CiCareCallService.callState.value)
            }
            CallState.ANSWERING -> serviceScope.launch { ackAnswer() }
            CallState.RINGING -> {
                outgoingCallStateUpdate(this@CiCareCallService.callState.value)
                playRingback(this)
                // Mulai timer 30 detik: auto-cancel jika tidak diangkat
                ringingTimeoutJob?.cancel()
                ringingTimeoutJob =
                        serviceScope.launch {
                            delay(30_000)
                            Log.i("SDK CALL", "Ringing timeout - auto cancel after 30s")
                            cancelCall()
                        }
            }
            CallState.RECONNECTING -> {}
            CallState.CONNECTING -> outgoingCallStateUpdate(this@CiCareCallService.callState.value)
            CallState.BUSY -> {
                stopRingback()
                outgoingCallStateUpdate(this@CiCareCallService.callState.value)
                this.eventListener?.onCallStateChanged(callState)
                stopSelf()
            }
            CallState.REFUSED -> {
                stopRingback()
                outgoingCallStateUpdate(this@CiCareCallService.callState.value)
                this.eventListener?.onCallStateChanged(callState)
                stopSelf()
            }
            CallState.CONNECTED -> {
                stopRingback()
                ringingTimeoutJob?.cancel() // Panggilan terhubung, batalkan timer timeout
                ringingTimeoutJob = null
                intent?.let { onOngoingCall(it) }
            }
            CallState.TIMEOUT -> {
                stopRingback()
                stopTimer()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                else stopForeground(true)
                stopSelf()
            }
            CallState.END, CallState.MISSED -> {
                hangup()
                Log.i("SDK CALL", "HANGUP")
            }
            CallState.RINGING_OK -> {}
        }
    }

    fun renegotiateRtC(sdpType: String) {
        if (isClosed) return
        socketManager.send("RECONNECT", JSONObject().apply {})
        serviceScope.launch(Dispatchers.Main) {
            webRTCManager.reconnectPeer()
            val sdp = webRTCManager.createOffer()
            socketManager.send(
                    "SDP_$sdpType",
                    JSONObject().apply {
                        put(
                                "sdp",
                                JSONObject().apply {
                                    put("type", sdp.type.toString())
                                    put("sdp", sdp.description)
                                }
                        )
                    }
            )
        }
    }

    override fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState) {
        when (state) {
            PeerConnection.IceConnectionState.CONNECTED -> {
                reconnectAttempt = 0
                connectionListener?.onSignalStateChanged("connected")
                // Jika sedang dalam proses menyambungkan, percepat ke status CONNECTED
                // agar UI segera menampilkan timer dan media aktif tanpa menunggu event socket.
                val currentState = callState.value.lowercase(Locale.ROOT)
                if (currentState == "connecting" || currentState == "answering") {
                    onCallStateChanged(CallState.CONNECTED)
                }
            }
            PeerConnection.IceConnectionState.DISCONNECTED -> {
                reconnectAttempt++
                if (reconnectAttempt > 3) {
                    webRTCManager.close()
                    return
                }
                connectionListener?.onSignalStateChanged("reconnecting")
                renegotiateRtC("OFFER")
            }
            PeerConnection.IceConnectionState.FAILED -> {
                webRTCManager.close()
            }
            PeerConnection.IceConnectionState.CLOSED -> {
                connectionListener?.onSignalStateChanged("lost")
            }
            else -> {
                // Log.d("SDK CALL", "ICE State: $state")
            }
        }
        Log.d("SDK CALL", "ICE State: $state")
    }
}
