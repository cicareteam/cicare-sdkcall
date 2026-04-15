package cc.cicare.sdkcall.notifications.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cc.cicare.sdkcall.event.CallEventListener
import cc.cicare.sdkcall.event.CallState
import cc.cicare.sdkcall.event.CallStateListener
import cc.cicare.sdkcall.event.ConnectionStateListener
import cc.cicare.sdkcall.event.MessageActionListener
import cc.cicare.sdkcall.event.MessageListenerHolder
import cc.cicare.sdkcall.libs.CallRepository
import cc.cicare.sdkcall.libs.CallRequest
import cc.cicare.sdkcall.libs.CallResult
import cc.cicare.sdkcall.notifications.ui.model.CallInfo
import cc.cicare.sdkcall.notifications.ui.model.CallViewModel
import cc.cicare.sdkcall.services.CiCareCallService
import cc.cicare.sdkcall.services.IncomingCallService
import cc.cicare.sdkcall.services.TimeTickerListener
import cc.cicare.sdkcall.utils.NetworkObserver
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.PeerConnection
import java.util.Locale

class ScreenCallActivity :
    ComponentActivity(), CallStateListener, TimeTickerListener, ConnectionStateListener {

    // ─────────────────────────────────────────────────────────────
    // Service binding
    // ─────────────────────────────────────────────────────────────

    private var callService: CiCareCallService? = null
    private var incomingService: IncomingCallService? = null

    /** True setelah callServiceConnection.onServiceConnected dipanggil. */
    private var bound: Boolean = false

    /** True setelah incomingServiceConnection.onServiceConnected dipanggil. */
    private var inbound: Boolean = false

    /**
     * Guard agar startCallService() hanya dieksekusi sekali per sesi Activity,
     * mencegah double-bind saat onStart dipanggil berulang (misal kembali dari Settings).
     */
    private var serviceStarted: Boolean = false

    // ─────────────────────────────────────────────────────────────
    // ViewModel & Listeners
    // ─────────────────────────────────────────────────────────────

    private val viewModel by viewModels<CallViewModel>()

    private val eventListener: CallStateListener = this
    private val tickerListener: TimeTickerListener = this
    private val connectionListener: ConnectionStateListener = this

    private val listener: MessageActionListener?
        get() = MessageListenerHolder.listener
    private val callEventListener: CallEventListener?
        get() = MessageListenerHolder.callEventListener

    // ─────────────────────────────────────────────────────────────
    // Flags
    // ─────────────────────────────────────────────────────────────

    private var isMicPermissionGranted: Boolean = false
    private var hasBeenConnected = false

    // ─────────────────────────────────────────────────────────────
    // Network observer
    // ─────────────────────────────────────────────────────────────

    private lateinit var networkObserver: NetworkObserver

    // ─────────────────────────────────────────────────────────────
    // Compose state
    // ─────────────────────────────────────────────────────────────

    private var timeTicker by mutableLongStateOf(0L)
    private var connectionState by mutableStateOf("")
    private var networkErrorText by mutableStateOf("")
    private var isMicMuted by mutableStateOf(false)
    private var isSpeakerOn by mutableStateOf(false)
    private var isOnBluetooth by mutableStateOf(false)
    private var showErrorDialog by mutableStateOf(false)
    private var isSystemError by mutableStateOf(false)
    private var isOutgoingCall by mutableStateOf(false)
    private var showMicPermissionDialog by mutableStateOf(false)
    private var isMicPermanentlyDenied by mutableStateOf(false)

    private val PREFS_NAME = "sdk_call_prefs"
    private val KEY_MIC_REQUESTED = "mic_ever_requested"

    // ─────────────────────────────────────────────────────────────
    // Permission launchers
    // ─────────────────────────────────────────────────────────────

    /**
     * Launcher untuk meminta RECORD_AUDIO permission dari sistem Android.
     * Dipanggil setelah user tap "Allow" di ShowDialogAskPermission.
     */
    private val micPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                isMicPermissionGranted = true
                showMicPermissionDialog = false
                isMicPermanentlyDenied = false
                onMicPermissionGranted()
            } else {
                // shouldShowRequestPermissionRationale = false → permanently denied
                // shouldShowRequestPermissionRationale = true  → denied tapi bisa tanya lagi
                val canAskAgain = shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                isMicPermanentlyDenied = !canAskAgain
                showMicPermissionDialog = true
            }
        }

    /**
     * Launcher untuk membuka halaman Settings app saat permission permanently denied.
     * Setelah user kembali, cek ulang apakah permission sudah di-grant.
     */
    private val micSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val micGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (micGranted) {
                isMicPermissionGranted = true
                showMicPermissionDialog = false
                isMicPermanentlyDenied = false
                onMicPermissionGranted()
            } else {
                isMicPermanentlyDenied = true
                showMicPermissionDialog = true
            }
        }

    // ─────────────────────────────────────────────────────────────
    // Metadata (UI strings, dapat di-override via intent)
    // ─────────────────────────────────────────────────────────────

    private var metaData: HashMap<*, *> = hashMapOf(
        "call_title" to "Free Call",
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
        "call_btn_message" to "Send Message",
        "call_btn_mute" to "Mute",
        "call_btn_speaker" to "Speaker",
        "call_failed_api" to "Call failed due to system error",
        "call_failed_no_connection" to "No internet connection",
        "call_feedback_bad" to "Bad experience",
        "call_feedback_bad_driver_cannot_hear" to "Driver couldn't hear me",
        "call_feedback_bad_lost_connection" to "Call was disconnected",
        "call_feedback_bad_noisy" to "Too much background noise",
        "call_feedback_bad_unstable_connection" to "Unstable connection",
        "call_feedback_btn_submit" to "Submit Feedback",
        "call_feedback_desc_content" to "Help us improve by sharing your experience",
        "call_feedback_desc_title" to "Tell us about your call experience",
        "call_feedback_good" to "Good experience",
        "call_feedback_good_connection" to "Good connection",
        "call_feedback_good_no_delay" to "No audio delay",
        "call_feedback_good_sound" to "Clear sound quality",
        "call_feedback_okay" to "Okay",
        "call_feedback_okay_delay" to "Audio was delayed",
        "call_feedback_okay_flickering_sound" to "Audio was flickering",
        "call_feedback_okay_small_sound" to "Sound was too low",
        "call_feedback_skip" to "Skip feedback",
        "call_feedback_title" to "Call Feedback",
        "call_option_btn_free_call" to "Free Call",
        "call_option_title" to "Call Options",
        "call_permission_btn_allow" to "Allow",
        "call_permission_btn_deny" to "Deny",
        "call_permission_btn_setting" to "Go to Settings",
        "call_permission_btn_skip" to "Skip",
        "call_permission_microphone_content" to "We need access to your microphone to make calls",
        "call_permission_microphone_denied_content" to "Please enable microphone access in your phone's Settings",
        "call_permission_microphone_denied_title" to "Microphone access is required to make a call",
        "call_permission_microphone_title" to "Microphone Permission",
        "call_status_call_customer" to "Calling customer",
        "call_status_call_customer_no_answer" to "Customer did not answer",
        "call_status_call_customer_refused" to "Customer refused the call",
        "call_status_call_driver" to "Calling driver",
        "call_status_call_driver_cancelled" to "Driver cancelled the call",
        "call_status_call_driver_no_answer" to "Driver did not answer",
        "call_status_call_driver_refused" to "Driver refused the call",
        "call_status_call_from_customer" to "Incoming call from customer",
        "call_status_call_from_customer_miss" to "Missed call from customer",
        "call_status_call_from_driver" to "Incoming call from driver",
        "call_status_call_from_driver_miss" to "Missed call from driver",
        "call_status_call_guide_again" to "Please try calling again",
        "call_status_call_guide_back" to "Please return to the app to continue the call",
        "call_suggestion_btn_dial" to "Dial",
        "call_suggestion_btn_free_call" to "Call for Free",
        "call_suggestion_btn_message" to "Send a Message",
        "call_suggestion_desc_travelling" to "The user might be traveling",
        "call_suggestion_desc_try_again" to "Try calling again in a moment",
    )

    // ─────────────────────────────────────────────────────────────
    // Service connections
    // ─────────────────────────────────────────────────────────────

    /**
     * Binding ke IncomingCallService — hanya untuk incoming call.
     * Dipakai untuk reject, show missed call notification, dan listen state.
     */
    private val incomingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            incomingService = (binder as IncomingCallService.LocalBinder).getService()
            inbound = true
            incomingService?.setCallListener(eventListener)
            incomingService?.setConnectionStateListener(connectionListener)
            // Jika service sudah END sebelum kita bind (race saat notif datang terlambat)
            if (incomingService?.callState == CallState.END) {
                hangup()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            inbound = false
            incomingService = null
        }
    }

    /**
     * Binding ke CiCareCallService — distart saat OUTGOING atau saat ACCEPT (answer).
     * Setelah bound, langsung jalankan aksi sesuai intent action.
     */
    private val callServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            callService = (binder as CiCareCallService.LocalBinder).getService()
            bound = true

            // Observe StateFlow dari service → update ViewModel → update UI
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    callService?.getCallStateFlow()?.collect { viewModel.updateState(it) }
                }
            }

            callService?.setCallEventListener(eventListener)
            callService?.setTickerListener(tickerListener)
            callService?.setConnectionStateListener(connectionListener)

            // Jalankan aksi sesuai action yang memicu binding ini
            val service = callService ?: return
            when (intent?.action) {
                CiCareCallService.ACTION.ACCEPT -> {
                    // Notif ACCEPT atau tombol answer di UI → langsung answerCall
                    service.answerCall(intent, true)
                }
                CiCareCallService.ACTION.OUTGOING -> {
                    // Service sudah running (distart di startCallService()),
                    // sekarang minta API lalu initCall
                    lifecycleScope.launch {
                        requestOutgoingCall(
                            callInfo = CallInfo(
                                callerId   = intent?.getStringExtra("caller_id")   ?: "",
                                callerName = intent?.getStringExtra("caller_name") ?: "",
                                callerAvatar = intent?.getStringExtra("caller_avatar") ?: "",
                                calleeId   = intent?.getStringExtra("callee_id")   ?: "",
                                calleeName = intent?.getStringExtra("callee_name") ?: "",
                                calleeAvatar = intent?.getStringExtra("callee_avatar") ?: "",
                                checksum   = intent?.getStringExtra("checksum")    ?: "",
                            ),
                            callService = service
                        )
                    }
                }
                else -> {
                    Log.i("SDK CALL", "callServiceConnection: unexpected action ${intent?.action}")
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            callService = null
        }
    }

    // ─────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────

    @SuppressLint("SourceLockedOrientationActivity")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Tampilkan Activity di atas lock screen
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Merge metadata dari intent (override default strings)
        metaData = mergeMetaData(intent)

        // Cek permission mic sebelum apapun
        if (intent.action == CiCareCallService.ACTION.ACCEPT || intent.action == CiCareCallService.ACTION.OUTGOING) {
            isMicPermissionGranted = checkMicPermissionGranted()
            if (!isMicPermissionGranted) {
                val everRequested = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getBoolean(KEY_MIC_REQUESTED, false)
                isMicPermanentlyDenied =
                    everRequested && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)
                showMicPermissionDialog = true
            }
        }

        // Untuk incoming: bind IncomingCallService yang sudah running
        if (intent.action == CiCareCallService.ACTION.INCOMING) {
            viewModel.updateState("incoming")
            Intent(this, IncomingCallService::class.java).also {
                bindService(it, incomingServiceConnection, BIND_AUTO_CREATE)
            }
            isOutgoingCall = false
        } else {
            isOutgoingCall = true
        }

        networkObserver = NetworkObserver(this) { isConnected ->
            if (!isConnected) {
                callEventListener?.onError(100, "No internet connection")
                onNetworkError(
                    state = metaData["call_failed_no_connection"]?.toString()
                        ?: "No internet connection",
                    systemError = true
                )
            }
        }

        val callerName  = intent.getStringExtra("caller_name")  ?: "Unknown"
        val callerAvatar = intent.getStringExtra("caller_avatar") ?: ""
        val calleeName  = intent.getStringExtra("callee_name")  ?: "Unknown"
        val calleeAvatar = intent.getStringExtra("callee_avatar") ?: ""
        val callType    = intent.getStringExtra("call_type")    ?: "outgoing"

        enableEdgeToEdge()
        setContent {
            val callStatusRaw by viewModel.callStatusRaw.collectAsState()

            // Tampilkan dialog permission jika mic belum di-grant
            if (!isMicPermissionGranted  && showMicPermissionDialog) {
                ShowDialogAskPermission()
            }

            // Tampilkan error dialog jika ada network/API error
            if (showErrorDialog) {
                ShowErrorDialog(
                    message = networkErrorText,
                    onDismiss = {
                        showErrorDialog = false
                        if (isSystemError) {
                            finish()
                        }
                    }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                CallScreen(
                    callerName   = if (callType == "incoming") callerName else calleeName,
                    callTimer    = if (callStatusRaw == "connected")
                        formatElapsedTime(timeTicker)
                    else
                        metaData["call_$callStatusRaw"]?.toString() ?: callStatusRaw,
                    callStatusRaw = callStatusRaw,
                    signalState  = if (connectionState == "connected") "" else connectionState,
                    avatarUrl    = if (callType == "incoming") callerAvatar else calleeAvatar,
                    isMicMuted   = isMicMuted,
                    isSpeakerOn  = isSpeakerOn,
                    isOnBluetooth = isOnBluetooth,
                    metaData     = metaData.mapKeys { it.key.toString() }
                        .mapValues { it.value.toString() },
                    onMuteClick = {
                        isMicMuted = !isMicMuted
                        callService?.setMute(isMicMuted)
                    },
                    onSpeakerClick = {
                        isSpeakerOn = !isSpeakerOn
                        callService?.setSpeaker(isSpeakerOn)
                    },
                    onMessageClick = null,
                    onAnswerCallClick = {
                        // Tombol answer di UI (hanya muncul saat incoming & status = "incoming")
                        if (isMicPermissionGranted) {
                            proceedAnswerCall()
                        } else {
                            // Tampilkan dialog permission — user belum grant saat tap answer
                            intent.action = CiCareCallService.ACTION.ACCEPT
                            showMicPermissionDialog = true
                        }
                    },
                    onEndCallClick = {
                        if (callType == "incoming" && callStatusRaw != "connected") {
                            // Incoming belum tersambung → reject
                            doReject()
                        } else if (callStatusRaw == "calling" ||
                            callStatusRaw == "connecting" ||
                            callStatusRaw == "ringing") {
                            // Outgoing masih ringing → cancel
                            callService?.cancelCall()
                            finish()
                        } else {
                            hangup()
                        }
                    },
                )
            }
        }
    }

    /**
     * Dipanggil setiap Activity menjadi visible.
     * Gunakan serviceStarted sebagai guard agar tidak double-bind.
     */
    override fun onStart() {
        super.onStart()
        networkObserver.start()

        intent.action?.let { Log.i("SDK CALL", "start action $it") }

        // Guard: hanya start service sekali per sesi Activity
        if (!serviceStarted && isMicPermissionGranted) {
            startCallService()
        }
    }

    override fun onStop() {
        super.onStop()
        networkObserver.stop()
    }

    /**
     * Dipanggil saat intent baru dikirim ke Activity yang sudah ada di back stack
     * (via FLAG_ACTIVITY_CLEAR_TOP dari notifikasi ACCEPT / HANGUP).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        when (intent.action) {
            CiCareCallService.ACTION.ACCEPT -> {
                if (isMicPermissionGranted) {
                    networkObserver.stop()
                    // Service sudah bound, langsung jawab
                    proceedAnswerCall()
                } else {
                    showMicPermissionDialog = true
                }
            }
            CiCareCallService.ACTION.HANGUP -> hangup()
        }
    }

    override fun onDestroy() {
        // Lepas semua binding agar service tidak leak
        if (bound) {
            Log.i("SDK CALL", "unbind callService ")
            callService?.hangup()
            try { unbindService(callServiceConnection) } catch (e: Exception) {
                Log.w("SDK CALL", "unbind callService failed: ${e.message}")
            }
            bound = false
        }
        if (inbound) {
            incomingService?.reject()
            try { unbindService(incomingServiceConnection) } catch (e: Exception) {
                Log.w("SDK CALL", "unbind incomingService failed: ${e.message}")
            }
            inbound = false
        }
        super.onDestroy()
    }

    // ─────────────────────────────────────────────────────────────
    // CallStateListener
    // ─────────────────────────────────────────────────────────────

    override fun onCallStateChanged(callState: CallState) {
        if (Thread.currentThread() != Looper.getMainLooper().thread) {
            runOnUiThread { onCallStateChanged(callState) }
            return
        }
        callEventListener?.onCallStateChange(callState)
        if (callState == CallState.CONNECTED) {
            hasBeenConnected = true
        }

        when (callState) {
            CallState.ANSWERING -> {
                // Callee sudah jawab, IncomingCallService tidak diperlukan lagi
                incomingService?.forceStop()
                if (inbound) {
                    try { unbindService(incomingServiceConnection) } catch (_: Exception) {}
                    inbound = false
                    incomingService = null
                }
                viewModel.updateState(metaData["call_connecting"]?.toString() ?: "connecting")
            }

            CallState.CONNECTED -> {
                hasBeenConnected = true
                networkObserver.stop()
            }

            CallState.TIMEOUT, CallState.MISSED -> {
                viewModel.updateState(metaData["call_end"]?.toString() ?: "end")
                tearDownCallService()
                val isIncomingMissed = !isOutgoingCall && !hasBeenConnected
                if (isIncomingMissed) {
                    try {
                        incomingService?.showMissedCallNotification()
                    } catch (e: SecurityException) {
                        incomingService?.forceStop()
                        Log.e("SDK CALL", "showMissedCallNotification: ${e.message}", e.cause)
                    }
                    finish()
                } else {
                    finishWithDelay()
                }
            }

            CallState.END, CallState.REFUSED, CallState.BUSY -> {
                val stateKey = "call_${callState.name.lowercase(Locale.ROOT)}"
                viewModel.updateState(metaData[stateKey]?.toString() ?: callState.name.lowercase())
                tearDownCallService()

                val isIncomingCancelled = !isOutgoingCall &&
                        callState == CallState.END && !hasBeenConnected
                if (isIncomingCancelled) {
                    try {
                        incomingService?.showMissedCallNotification()
                    } catch (e: SecurityException) {
                        incomingService?.forceStop()
                        Log.e("SDK CALL", "showMissedCallNotification: ${e.message}", e.cause)
                    }
                    finish()
                } else {
                    finishWithDelay()
                }
            }

            else -> { /* CALLING, RINGING, CONNECTING — UI diupdate via StateFlow */ }
        }
    }

    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        // Bisa digunakan untuk animasi koneksi di masa depan
    }

    override fun onTimeTicketUpdate(seconds: Long) {
        timeTicker = seconds
    }

    override fun onSignalStateChanged(state: String) {
        if (state.isEmpty()) return
        connectionState = if (
            callService?.callState?.value?.lowercase(Locale.ROOT) == "connected" &&
            state == "connected"
        ) "" else state
    }

    override fun onNetworkError(state: String, systemError: Boolean) {
        showErrorDialog = true
        isSystemError = systemError
        networkErrorText = state
        Log.i("SDK CALL NETWORK_ERROR", state)
    }

    // ─────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────

    /**
     * Cek apakah RECORD_AUDIO (dan FOREGROUND_SERVICE_MICROPHONE di API 34+) sudah granted.
     */
    private fun checkMicPermissionGranted(): Boolean {
        val recordAudio = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val fgMic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        return recordAudio && fgMic
    }

    /**
     * Dipanggil setelah permission mic granted (dari launcher atau kembali dari Settings).
     * Jika incoming: lanjutkan answer. Jika belum ada service & outgoing: start service.
     */
    private fun onMicPermissionGranted() {
        if (!serviceStarted) {
            startCallService()
        } else if (!isOutgoingCall) {
            // Sudah masuk onStart tapi service belum distart karena permission belum grant
            proceedAnswerCall()
        }
    }

    /**
     * Start dan bind CiCareCallService berdasarkan action intent.
     * Hanya dijalankan sekali berkat flag serviceStarted.
     *
     * - INCOMING : tidak start CiCareCallService di sini.
     *              Service distart nanti saat proceedAnswerCall() → answerCall().
     * - OUTGOING/ACCEPT : start + bind langsung, aksi dilanjutkan di onServiceConnected.
     */
    private fun startCallService() {
        when (intent?.action) {
            CiCareCallService.ACTION.INCOMING -> {
                // Tidak perlu start CiCareCallService.
                // Hanya perlu pastikan IncomingCallService sudah bound (dilakukan di onCreate).
                // serviceStarted ditandai agar onStart tidak mencoba lagi.
                serviceStarted = true
                isOutgoingCall = false
            }

            CiCareCallService.ACTION.OUTGOING,
            CiCareCallService.ACTION.ACCEPT -> {
                serviceStarted = true
                networkObserver.stop()
                isOutgoingCall = intent?.action == CiCareCallService.ACTION.OUTGOING

                // Hentikan incoming service jika masih ada (skenario: accept dari notif)
                incomingService?.forceStop()

                val svcIntent = Intent(this, CiCareCallService::class.java).apply {
                    action = intent?.action
                    intent?.extras?.let { putExtras(it) }
                }.also {
                    bindService(it, callServiceConnection, BIND_AUTO_CREATE)
                }

                intent?.action?.let { Log.i("SDK Call Logger", it) }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    startForegroundService(svcIntent)
                else
                    startService(svcIntent)
            }
        }
    }

    /**
     * Dipanggil setelah mic permission granted dan user memilih untuk menjawab panggilan.
     * Untuk INCOMING: baru di sini CiCareCallService distart dan answerCall dipanggil.
     * Untuk OUTGOING: tidak digunakan (flow berbeda via requestOutgoingCall).
     */
    private fun proceedAnswerCall() {
        // Batalkan notifikasi incoming
        NotificationManagerCompat.from(this).cancel(104)

        // Hentikan IncomingCallService
        incomingService?.forceStop()
        if (inbound) {
            try { unbindService(incomingServiceConnection) } catch (_: Exception) {}
            inbound = false
            incomingService = null
        }
        try {
            stopService(Intent(this, IncomingCallService::class.java))
        } catch (e: Exception) {
            Log.e("SDK CALL", "stopService IncomingCallService: ${e.message}")
        }

        if (bound && callService != null) {
            // Service sudah bound (misal dari ACCEPT action) → langsung answer
            callService?.answerCall(intent, true)
        } else {
            // Service belum ada → start + bind, answerCall dipanggil di onServiceConnected
            val svcIntent = Intent(this, CiCareCallService::class.java).apply {
                action = CiCareCallService.ACTION.ACCEPT
                intent?.extras?.let { putExtras(it) }
            }.also {
                bindService(it, callServiceConnection, BIND_AUTO_CREATE)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(svcIntent)
            else
                startService(svcIntent)
            serviceStarted = true
        }
    }

    /**
     * Hit API untuk mendapatkan server + token, lalu initCall ke CiCareCallService.
     */
    private suspend fun requestOutgoingCall(callInfo: CallInfo, callService: CiCareCallService) {
        if (!checkInternetConnection()) {
            Log.i("SDK CALL", "NO INTERNET")
            callEventListener?.onError(100, "No internet connection")
            onNetworkError(
                state = metaData["call_failed_no_connection"]?.toString()
                    ?: "No internet connection",
                systemError = true
            )
            return
        }
        try {
            when (val result = CallRepository.requestCall(
                CallRequest(
                    callerId     = callInfo.callerId,
                    callerName   = callInfo.callerName,
                    callerAvatar = callInfo.callerAvatar,
                    calleeId     = callInfo.calleeId,
                    calleeName   = callInfo.calleeName,
                    calleeAvatar = callInfo.calleeAvatar,
                    checkSum     = callInfo.checksum,
                )
            )) {
                is CallResult.Success -> {
                    val data = result.data
                    Log.i("SDK CALL", "requestOutgoingCall success: ${data.callee}")
                    callService.initCall(data.server, data.token)
                }
                is CallResult.Failure -> {
                    Log.e("SDK CALL", "requestOutgoingCall error: ${result.error.code} - ${result.error.message}")
                    handleOutgoingCallFailure(result.error.code, result.error.message)
                }
            }
        } catch (e: Exception) {
            Log.e("SDK CALL", "requestOutgoingCall exception: ${e.message}", e)
            callEventListener?.onError(500, "Call failed due to system error")
            onNetworkError(
                state = metaData["call_failed_api"]?.toString() ?: "Call failed due to system error",
                systemError = true
            )
        }
    }

    private fun handleOutgoingCallFailure(code: Int, rawMessage: String) {
        try {
            val json = JSONObject(rawMessage)
            val innerCode = json.optInt("code", -1)
            val innerMsg  = json.optString("message", rawMessage)
            if (code == 400 && innerCode == 3) {
                callEventListener?.onError(innerCode, innerMsg)
                onNetworkError(
                    state = metaData[innerMsg]?.toString() ?: innerMsg,
                    systemError = true
                )
            } else {
                callEventListener?.onError(code, innerMsg)
                onNetworkError(
                    state = metaData["call_failed_api"]?.toString() ?: "Call failed due to system error",
                    systemError = true
                )
            }
        } catch (e: Exception) {
            callEventListener?.onError(500, "Call failed due to system error")
            onNetworkError(
                state = metaData["call_failed_api"]?.toString() ?: "Call failed due to system error",
                systemError = true
            )
        }
    }

    /**
     * Tolak panggilan incoming.
     * Prioritas: via incomingService (sudah bound) → fallback via startService REJECT.
     */
    private fun doReject() {
        if (inbound && incomingService != null) {
            incomingService?.reject()
        } else {
            // Fallback: kirim REJECT action ke IncomingCallService yang mungkin masih hidup.
            // Pastikan extras diteruskan agar service punya caller_id untuk socket.
            val svcIntent = Intent(this, IncomingCallService::class.java).apply {
                action = CiCareCallService.ACTION.REJECT
                intent?.extras?.let { putExtras(it) }
            }
            startService(svcIntent)
        }
        finishAndRemoveTask()
    }

    /**
     * Hangup general — digunakan untuk outgoing/connected call.
     * Untuk reject incoming sebelum tersambung, gunakan doReject().
     */
    private fun hangup() {
        val currentState  = callService?.callState?.value?.lowercase(Locale.ROOT) ?: ""
        val currentAction = intent?.action ?: ""
        val callType      = intent?.getStringExtra("call_type") ?: ""

        Log.i("SDK CALL", "hangup() action=$currentAction type=$callType state=$currentState")

        if ((currentAction == CiCareCallService.ACTION.INCOMING || callType == "incoming")
            && currentState != "connected") {
            doReject()
            return
        }

        if (bound && callService != null) {
            callService?.hangup()
        } else {
            val svcIntent = Intent(this, CiCareCallService::class.java).apply {
                action = CiCareCallService.ACTION.HANGUP
                intent?.extras?.let { putExtras(it) }
            }
            startService(svcIntent)
        }
        finishAndRemoveTask()
    }

    /**
     * Lepas binding dan stop CiCareCallService secara aman.
     * unbindService dipanggil dulu, baru forceStop — bukan sebaliknya.
     */
    private fun tearDownCallService() {
        if (bound) {
            try { unbindService(callServiceConnection) } catch (e: Exception) {
                Log.w("SDK CALL", "tearDownCallService unbind: ${e.message}")
            }
            bound = false
            callService?.forceStop()
            callService = null
            serviceStarted = false
        }
    }

    /** Tutup Activity setelah 2 detik (memberi waktu UI menampilkan status akhir). */
    private fun finishWithDelay() {
        window.decorView.postDelayed({
            if (!isFinishing)
                finishAndRemoveTask()
        }, 2000)
    }

    private fun checkInternetConnection(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    @SuppressLint("DefaultLocale")
    private fun formatElapsedTime(seconds: Long): String {
        return String.format("%02d:%02d", seconds / 60, seconds % 60)
    }

    /**
     * Merge metadata dari intent dengan default metaData.
     * Mendukung API 33+ (getSerializableExtra dengan Class) dan di bawahnya.
     */
    @Suppress("DEPRECATION")
    private fun mergeMetaData(intent: Intent): HashMap<*, *> {
        val extra: Map<String, String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("meta_data", HashMap::class.java)
                ?.mapNotNull {
                    val k = it.key as? String
                    val v = it.value as? String
                    if (k != null && v != null) k to v else null
                }?.toMap() ?: emptyMap()
        } else {
            (intent.getSerializableExtra("meta_data") as? HashMap<*, *>)
                ?.mapNotNull {
                    val k = it.key as? String
                    val v = it.value as? String
                    if (k != null && v != null) k to v else null
                }?.toMap() ?: emptyMap()
        }
        return HashMap(metaData + extra)
    }

    // ─────────────────────────────────────────────────────────────
    // Composables
    // ─────────────────────────────────────────────────────────────

    /**
     * Dialog pertama yang muncul ketika mic permission belum di-grant.
     * - Confirm: request permission via micPermissionLauncher
     * - Dismiss: reject call dan tutup Activity
     *
     * Ketika isMicPermanentlyDenied = true:
     * - Confirm: buka Settings via micSettingsLauncher
     * - Dismiss: reject call
     */
    @Composable
    private fun ShowDialogAskPermission() {
        AlertDialog(
            onDismissRequest = {
                showMicPermissionDialog = false
                hangup()
            },
            title = {
                Text(
                    text = if (isMicPermanentlyDenied)
                        metaData["call_permission_microphone_denied_title"]?.toString()
                            ?: "Microphone access is required to make a call"
                    else
                        metaData["call_permission_microphone_title"]?.toString()
                            ?: "Microphone Permission"
                )
            },
            text = {
                Text(
                    text = if (isMicPermanentlyDenied)
                        metaData["call_permission_microphone_denied_content"]?.toString()
                            ?: "Please enable microphone access in your phone's Settings"
                    else
                        metaData["call_permission_microphone_content"]?.toString()
                            ?: "We need access to your microphone to make calls"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showMicPermissionDialog = false
                        if (isMicPermanentlyDenied) {
                            micSettingsLauncher.launch(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                            )
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                ) {
                    Text(
                        text = if (isMicPermanentlyDenied)
                            metaData["call_permission_btn_setting"]?.toString() ?: "Go to Settings"
                        else
                            metaData["call_permission_btn_allow"]?.toString() ?: "Allow",
                        color = Color(0xFF00BABD)
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showMicPermissionDialog = false
                        hangup()
                    }
                ) {
                    Text(
                        text = metaData["call_permission_btn_deny"]?.toString() ?: "Cancel",
                        color = Color.Gray
                    )
                }
            }
        )
    }

    /**
     * Dialog error untuk kegagalan jaringan atau API.
     */
    @Composable
    private fun ShowErrorDialog(message: String, onDismiss: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = "Error") },
            text = { Text(text = message) },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = "OK", color = Color(0xFF00BABD))
                }
            }
        )
    }
}