package cc.cicare.sdkcall.notifications.ui

import android.annotation.SuppressLint
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cc.cicare.sdkcall.event.CallEventListener
import cc.cicare.sdkcall.event.CallStateListener
import cc.cicare.sdkcall.event.CallState
import cc.cicare.sdkcall.event.ConnectionStateListener
import cc.cicare.sdkcall.event.MessageActionListener
import cc.cicare.sdkcall.event.MessageListenerHolder
import cc.cicare.sdkcall.libs.CallRepository
import cc.cicare.sdkcall.libs.CallRequest
import cc.cicare.sdkcall.libs.CallResult
import cc.cicare.sdkcall.notifications.ui.icons.SpeakerBluetooth
import cc.cicare.sdkcall.notifications.ui.model.CallViewModel
import cc.cicare.sdkcall.services.CiCareCallService
import cc.cicare.sdkcall.services.IncomingCallService
import cc.cicare.sdkcall.services.TimeTickerListener
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.webrtc.PeerConnection
import kotlin.collections.HashMap
import kotlin.coroutines.resume

data class CallInfo (
    val callerId: String,
    val callerName: String,
    val callerAvatar: String,
    val calleeId: String,
    val calleeName: String,
    val calleeAvatar: String,
    val checksum: String,
)
class ScreenCallActivity :
    ComponentActivity(),
    CallStateListener,
    TimeTickerListener,
    ConnectionStateListener
{

    private var callService: CiCareCallService? = null

    private var incomingService: IncomingCallService? = null
    private var bound: Boolean = false
    private var inbound: Boolean = false
    private var eventListener: CallStateListener = this
    private var tickerListener: TimeTickerListener = this
    private var connectionLister: ConnectionStateListener = this
    private var permissionGranted: Boolean = false
    //private var callDurationJob: Job? = null
    //private var callSeconds = 0

    private var timeTicker by mutableLongStateOf(0L)
    private val viewModel by viewModels<CallViewModel>()

    //private var callStatusRaw by mutableStateOf("initializing")
    private var isMicMuted by mutableStateOf(false)
    private var isSpeakerOn by mutableStateOf(false)
    private var isOnBluetooth by mutableStateOf(false)

    private val listener: MessageActionListener?
        get() = MessageListenerHolder.listener

    private val callEventListener: CallEventListener?
        get() = MessageListenerHolder.callEventListener

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
        //"call_name_title" to "Xanh SM Customer",
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
        "call_permission_microphone_demied_content" to "Please enable microphone access in your phone’s Settings",
        "call_permission_microphone_demied_title" to "Microphone access is required to make a call",
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

    private var connectionState by mutableStateOf("")
    private var networkErrorText by mutableStateOf("")
    private var showErrorDialog by mutableStateOf(false)
    private var isSystemError by mutableStateOf(false)

    private val callServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            callService = (binder as CiCareCallService.LocalBinder).getService()
            bound = true
            // Observe StateFlow
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    callService?.getCallStateFlow()?.collect {
                        viewModel.updateState(it)
                    }
                }
            }
            Log.i("SDK CALL", "Bounding service")
            callService?.setCallEventListener(eventListener)
            callService?.setTickerListener(tickerListener)
            callService?.setConnectionStateListener(connectionLister)
            callService?.let { service ->
                when(intent?.action) {
                    CiCareCallService.ACTION.ACCEPT -> lifecycleScope.launch {
                        service.answerCall(intent)
                    }
                    CiCareCallService.ACTION.INCOMING -> lifecycleScope.launch {
                        callService?.let {
                            it.callState.value = "incoming"
                        }
                    }
                    CiCareCallService.ACTION.OUTGOING -> lifecycleScope.launch {
                        if (permissionGranted) {
                            Log.i("SDK CALL", "OUTGOING GRANTED")
                            requestOutgoingCall(
                                callInfo = CallInfo(
                                    callerId = intent.getStringExtra("caller_id") ?: "",
                                    callerName = intent.getStringExtra("caller_name") ?: "",
                                    callerAvatar = intent.getStringExtra("caller_avatar") ?: "",
                                    calleeId = intent.getStringExtra("callee_id") ?: "",
                                    calleeName = intent.getStringExtra("callee_name") ?: "",
                                    calleeAvatar = intent.getStringExtra("callee_avatar") ?: "",
                                    checksum = intent.getStringExtra("checksum") ?: "",
                                ),
                                callService = service
                            )
                        }
                    }

                    else -> {
                        intent?.action?.let { Log.i("SDK Call", it) }
                    }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            bound = false
            callService = null
        }
    }

    private suspend fun requestOutgoingCall(callInfo: CallInfo, callService: CiCareCallService) {
        if (!checkInternetConnection()) {
            Log.i("SDK CALL", "NO INTERNET")
            callEventListener?.onError(100, "No internet connection")
            onNetworkError(
                state = (metaData["call_failed_no_connection"]
                    ?: "No internet connection") as String, systemError = false
            )
        } else {
            try {
                when (val result = CallRepository.requestCall(
                    CallRequest(
                        callerId = callInfo.callerId,
                        callerName = callInfo.callerName,
                        callerAvatar = callInfo.callerAvatar,
                        calleeId = callInfo.calleeId,
                        calleeName = callInfo.calleeName,
                        calleeAvatar = callInfo.calleeAvatar,
                        checkSum = callInfo.checksum,
                    )
                )) {
                    is CallResult.Success -> {
                        val apiResponse = result.data
                        Log.e("SDK CALL", "Success: $apiResponse")
                        callService.initCall(apiResponse.server, apiResponse.token)
                        Log.i("SDK Call", apiResponse.callee)
                    }

                    is CallResult.Failure -> {
                        Log.e("SDK CALL", "Error: ${result.error.code} - ${result.error.message}")
                        if (result.error.code == 400) {
                            val c = JSONObject(result.error.message)
                            if (c.get("code") == 3) {
                                callEventListener?.onError(c.get("code") as Int,
                                    c.get("message") as String
                                )
                                this.onNetworkError(
                                    state = (metaData[c.get("message")]
                                        ?: c.get("message")) as String,
                                    systemError = false
                                )
                            } else {
                                callEventListener?.onError(500,
                                    "Call failed due to system error"
                                )
                            }
                        } else {
                            val c = JSONObject(result.error.message)
                            callEventListener?.onError(result.error.code,
                                c.get("message") as String
                            )
                            this.onNetworkError(
                                state = (metaData["call_failed_api"]
                                    ?: "Call failed due to system error") as String,
                                systemError = true
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SDK CALL", "Error: ${e.message}")
                callEventListener?.onError(500,
                    "Call failed due to system error"
                )
                this.onNetworkError(
                    state = (metaData["call_failed_api"]
                        ?: "Call failed due to system error") as String,
                    systemError = true
                )
                Log.e("SDK CALL", "Error: ${e.cause} ${e.message}")
            }

        }
    }

    private val incomingServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            incomingService = (binder as IncomingCallService.LocalBinder).getService()
            inbound = true
            incomingService?.setCallListener(eventListener)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            inbound = false
            incomingService = null
        }
    }

    private fun checkInternetConnection(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onStart() {
        super.onStart()
        Intent(this, CiCareCallService::class.java).also {
            bindService(it, callServiceConnection, BIND_AUTO_CREATE)
        }

        Intent(this, IncomingCallService::class.java).also {
            bindService(it, incomingServiceConnection, BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        if (bound) {
            unbindService(callServiceConnection)
            bound = false
        }
        if (inbound) {
            unbindService(incomingServiceConnection)
            inbound = false
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        when(intent?.action) {
            CiCareCallService.ACTION.ACCEPT -> lifecycleScope.launch { answer() }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                callService?.getCallStateFlow()?.collect {
                    viewModel.updateState(it)
                }
            }
        }
        Log.i("SDK Call", "New Intent")
        callService?.setCallEventListener(eventListener)
        callService?.setConnectionStateListener(connectionLister)
        // handle update state or extras here
    }

//    private fun requestAudioFocus() {
//        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
//        /*val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
//            .setOnAudioFocusChangeListener { /* optional */ }
//            .build()*/
//        //audioManager.requestAudioFocus(focusRequest)
//        Log.i("SDK Call", "Audio focus")
//        val audioAttributes = AudioAttributes.Builder()
//            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
//            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
//            .build()
//
//        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
//            .setAudioAttributes(audioAttributes)
//            .setAcceptsDelayedFocusGain(false)
//            .setOnAudioFocusChangeListener { /* handle focus change */ }
//            .build()
//
//        audioManager.requestAudioFocus(focusRequest)
//    }

    private val requiredPermissions = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.READ_PHONE_STATE,
    )

    @RequiresApi(Build.VERSION_CODES.P)
    private val requiredPermissions28 = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.READ_PHONE_STATE,
    )

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private val requiredPermissionsTirmaisu = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.READ_PHONE_STATE,
    )

    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private val requiredPermissionsUpsideDownCake = arrayOf(
        android.Manifest.permission.RECORD_AUDIO,
        android.Manifest.permission.FOREGROUND_SERVICE,
        android.Manifest.permission.POST_NOTIFICATIONS,
        android.Manifest.permission.READ_PHONE_STATE,
        android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
        android.Manifest.permission.FOREGROUND_SERVICE_PHONE_CALL
    )

    fun isForegroundMicPermissionGranted(): Boolean {
        val recordAudioGranted = ContextCompat.checkSelfPermission(
            this,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val fgMicGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true // Di bawah Android 14 belum ada permission ini
        }

        return recordAudioGranted && fgMicGranted
    }

    private fun checkAndRequestPermissions(onResult: (Boolean) -> Unit) {
        val permissions = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.P -> requiredPermissions
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> requiredPermissions28
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> requiredPermissionsTirmaisu
            else -> requiredPermissionsUpsideDownCake
        }

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (notGranted.isEmpty()) {
            onResult(true)
            return
        }

        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { result ->
            val allGranted = result.values.all { it }
            permissionGranted = allGranted

            onResult(allGranted)
        }

        launcher.launch(notGranted.toTypedArray())
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        checkAndRequestPermissions {
            permissionGranted = it
        }

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        val context = this
        val myIntent = intent
//        requestAudioFocus()

        when(intent?.action) {
            CiCareCallService.ACTION.INCOMING -> lifecycleScope.launch {

                checkAndRequestPermissions { granted ->
                    if (granted) {
                        Log.i("SDK CALL", "Granted")
                        val intent = Intent(context, CiCareCallService::class.java).apply {
                            action = CiCareCallService.ACTION.INCOMING
                        }
                        startService(intent)
                        callService?.let {
                            it.callState.value = "incoming"
                        }
                    } else {
                        callEventListener?.onError(101, "Permission denied")
                    }
                }
            }
            CiCareCallService.ACTION.OUTGOING -> lifecycleScope.launch {

                checkAndRequestPermissions { granted ->
                    if (granted) {
                        val intent = Intent(context, CiCareCallService::class.java).apply {
                            action = CiCareCallService.ACTION.OUTGOING
                            putExtras(myIntent)
                        }
                        startService(intent)
                        lifecycleScope.launch {
                            callService?.let {
                                requestOutgoingCall(
                                    callInfo = CallInfo(
                                        callerId = intent.getStringExtra("caller_id") ?: "",
                                        callerName = intent.getStringExtra("caller_name") ?: "",
                                        callerAvatar = intent.getStringExtra("caller_avatar") ?: "",
                                        calleeId = intent.getStringExtra("callee_id") ?: "",
                                        calleeName = intent.getStringExtra("callee_name") ?: "",
                                        calleeAvatar = intent.getStringExtra("callee_avatar") ?: "",
                                        checksum = intent.getStringExtra("checksum") ?: "",
                                    ),
                                    callService = it
                                )
                            }
                        }
                    } else {
                        callEventListener?.onError(101, "Permission denied")
                        finish()
                    }
                }
            }
            CiCareCallService.ACTION.ACCEPT -> lifecycleScope.launch { answer() }
            CiCareCallService.ACTION.REJECT -> hangup()
        }


        val callerName = intent.getStringExtra("caller_name") ?: "Unknown"
        val callerAvatar = intent.getStringExtra("caller_avatar") ?: ""
        val calleeName = intent.getStringExtra("callee_name") ?: "unknown"
        val calleeAvatar = intent.getStringExtra("callee_avatar") ?: ""
        val callType = intent.getStringExtra("call_type") ?: "outgoing"

        metaData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val extra = intent.getSerializableExtra("meta_data", HashMap::class.java)
                ?.mapNotNull {
                    val key = it.key as? String
                    val value = it.value as? String
                    if (key != null && value != null) key to value else null
                }?.toMap() ?: emptyMap()
            HashMap(metaData + extra)
        } else {
            val extra = (intent.getSerializableExtra("meta_data") as? HashMap<*, *>)?.mapNotNull {
                val key = it.key as? String
                val value = it.value as? String
                if (key != null && value != null) key to value else null
            }?.toMap() ?: emptyMap()
            HashMap(metaData + extra)
        }
        enableEdgeToEdge()
        setContent {
            val callStatusRaw by viewModel.callStatusRaw.collectAsState()
            Box(modifier = Modifier.fillMaxSize()) {
                CallScreen(
                    callerName = if (callType == "incoming") callerName else calleeName,
                    callTimer = if
                                        (callStatusRaw == "connected") formatElapsedTime(timeTicker)
                    else metaData["call_$callStatusRaw"] ?: callStatusRaw,
                    callStatusRaw = callStatusRaw,
                    signalState = connectionState,
                    avatarUrl = if (callType == "incoming") callerAvatar else calleeAvatar,
                    isMicMuted,
                    isSpeakerOn,
                    isOnBluetooth,
                    metaData = metaData.mapKeys { it.key.toString() }
                        .mapValues { it.value.toString() },
                    onMuteClick = {
                        isMicMuted = !isMicMuted
                        callService?.setMute(isMicMuted)
                    },
                    onSpeakerClick = {
                        isSpeakerOn = !isSpeakerOn
                        callService?.setSpeaker(isSpeakerOn)
                    },
                    onMessageClick = {
                        listener?.onShowMessagePage()
                        hangup()
                    },
                    onAnswerCallClick = {
                        answer()
                    },
                    onEndCallClick = {
                        if (callType == "incoming" && callStatusRaw != "connected") {
                            incomingService?.reject()
                        } else {
                            if (callStatusRaw == "calling") {
                                callService?.cancelCall()
                            } else {
                                hangup()
                            }
                        }
                        finish()
                    },
                )
                ErrorAlertDialog(
                    showDialog = showErrorDialog,
                    onDismiss = {
                        showErrorDialog = false
                        callService?.hangup()
                        callService?.forceStop()
                        incomingService?.forceStop()
                        finish()
                    },
                    withIcon = isSystemError,
                    message = networkErrorText
                )
            }
        }
    }

    override fun onDestroy() {
        MessageListenerHolder.listener = null
        callService?.stopSelf()
        incomingService?.stopSelf()
        callService?.cancelCall()
        super.onDestroy()
    }


    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {

    }

    override fun onTimeTicketUpdate(seconds: Long) {
        timeTicker = seconds
    }

    override fun onCallStateChanged(callState: CallState) {
        callEventListener?.onCallStateChange(callState)
        //Log.i("HELLO", "RUN TIMER")
        //callStatusRaw = callState.toString().lowercase()
        /*when (callState) {
            CallState.RINGING -> callStatus = "ringing"
            CallState.CONNECTED -> callStatus = "connected"
            CallState.ENDED -> {
                callStatus = callState.name.lowercase()
                finish()
            }
            else -> callStatus = callStatusRaw
        }*/

        // if (callState == CallState.CONNECTED) {
        // Log.i("SCREEN", "RUN TIMER")
        // // Start timer
        // callSeconds = 0
        // callDurationJob?.cancel()
        // callDurationJob = CoroutineScope(Dispatchers.Main).launch {
        // while (isActive) {
        // delay(1000)
        // callSeconds++
        // callTimer = formatTime(callSeconds)
        // }
        // }
        // } else
        if (callState == CallState.END || callState == CallState.REFUSED || callState == CallState.BUSY) {
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 2000) // 3000 ms = 3 detik
        }
    }

    private fun answer() {
        callService?.answerCall(intent, true)
    }

    private fun hangup() {
        callService?.hangup()
        finish()
    }

    @SuppressLint("DefaultLocale")
    private fun formatElapsedTime(seconds: Long): String {
        val minutes = seconds / 60
        val secs = seconds % 60
        return String.format("%02d:%02d", minutes, secs)
    }

    override fun onSignalStateChanged(state: String) {
        if (callService?.callState == MutableStateFlow("connected")) {
            connectionState = if (state == "connected") "" else state
        }
    }

    override fun onNetworkError(state: String, systemError: Boolean) {
        showErrorDialog = true
        Log.i("NETWORK_ERROR", state)
        isSystemError = systemError
        networkErrorText = state
    }

//    private fun _e(key: String, hashMap: Map<*, *>): Any {
//        return hashMap[key] ?: key
//    }

}

@Composable
fun CallScreen(
    callerName: String,
    callTimer: Any,
    callStatusRaw: String,
    signalState: String,
    avatarUrl: String,
    isMicMuted: Boolean,
    isSpeakerOn: Boolean,
    isOnBluetooth: Boolean,
    metaData: Map<String, String>,
    onMuteClick: () -> Unit,
    onSpeakerClick: () -> Unit,
    onMessageClick: () -> Unit,
    onAnswerCallClick: () -> Unit,
    onEndCallClick: () -> Unit
) {
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
    ) { padding ->
        Box {
            MultiLayerGradientBackground()

            Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    text = metaData["call_title"] ?: "Free Call",
                    style = MaterialTheme.typography.headlineSmall
                )

                Spacer(modifier = Modifier.height(60.dp))
            }

            // Avatar
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {

                    Text(text = callTimer as String, style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(55.dp))
                    CallAvatar(avatarUrl)
                    Spacer(modifier = Modifier.height(35.dp))

                    Text(text = if (metaData["call_name_title"]?.isBlank() == true)
                        callerName else metaData["call_name_title"] ?: callerName, style = MaterialTheme.typography.headlineSmall)
                    Spacer(modifier = Modifier.height(15.dp))
                    Text(
                        text = metaData[signalState] ?: signalState, // ->status network
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Red
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))

            //
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RoundIconButton(
                    icon = if (isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp else {
                        if (isOnBluetooth) SpeakerBluetooth
                        else Icons.AutoMirrored.Outlined.VolumeUp
                    },
                    label = metaData["call_btn_speaker"] ?: "Speaker",
                    onClick = onSpeakerClick,
                    backgroundColor = if (isSpeakerOn) Color(0xFF00BABD) else Color(0xFFE9F8F9),
                    iconTint = if (isSpeakerOn) Color.White else Color(0xFF17666A),
                    enabled = callStatusRaw.lowercase() != "ended"
                )

                RoundIconButton(
                    icon = Icons.Default.MicOff,
                    label = metaData["call_btn_mute"] ?: "Mute",
                    onClick = onMuteClick,
                    backgroundColor = if (isMicMuted) Color(0xFF00BABD) else Color(0xFFE9F8F9),
                    iconTint = if (isMicMuted) Color.White else Color(0xFF17666A),
                    enabled = callStatusRaw.lowercase() == "connected"
                )

//                if (callStatusRaw.lowercase() == "incoming") {
//                    RoundIconButton(
//                        icon = Icons.AutoMirrored.Outlined.Chat,
//                        label = metaData["call_btn_message"] ?: "Message",
//                        onClick = onMessageClick,
//                        backgroundColor = Color(0xFFE9F8F9),
//                        iconTint = Color(0xFF17666A),
//                    )
//                }
            }
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 55.dp)
            ) {

                RoundIconButton(
                    icon = Icons.Filled.Close,
                    label = "",
                    onClick = onEndCallClick,
                    backgroundColor = Color.Red,
                    iconTint = Color.White,
                    enabled = callStatusRaw.lowercase() != "ended"
                )
                if (callStatusRaw.lowercase() == "incoming") {
                    Spacer(modifier = Modifier.width(160.dp))
                    RoundIconButton(
                        icon = Icons.Default.Phone,
                        label = "",
                        onClick = onAnswerCallClick,
                        backgroundColor = Color.Green,
                        iconTint = Color.White,
                    )
                }
            }
            //}
        }
        }
    }
}

@Composable
fun MultiLayerGradientBackground(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        // Layer 3: Base horizontal gradient (270deg)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.0f to Color(0xFFFFF4DF), // Left becomes #FFF4DF
                            0.5f to Color(0xFFFFFFFF),
                            1.0f to Color(0xFFDAFFFF)  // Right becomes #DAFFFF
                        )
                    )
                )
        )

        // Layer 2: Vertical fade (180deg)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    brush = Brush.verticalGradient(
                        colorStops = arrayOf(
                            0.3167f to Color(0x00F6F6F6), // transparent
                            1.0f to Color(0xFFF6F6F6)
                        )
                    )
                )
        )

        // Layer 1: Diagonal fade (224.7deg ≈ ~45° flip)
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    brush = Brush.linearGradient(
                        colorStops = arrayOf(
                            0.3943f to Color(0x00DFEFFF), // transparent
                            1.0f to Color(0xFFEBFFFF)
                        ),
                        start = Offset.Infinite,
                        end = Offset.Zero
                    )
                )
        )
    }
}

@Composable
fun CallAvatar(imageUrl: String?) {
    if (imageUrl.isNullOrBlank()) {
        // Tampilkan icon orang jika URL kosong
        Box(
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(Color.LightGray),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Person,
                contentDescription = "Default Avatar",
                tint = Color.White,
                modifier = Modifier.size(80.dp)
            )
        }
    } else {
        // Tampilkan gambar dari URL
        AsyncImage(
            model = imageUrl,
            contentDescription = "Caller Avatar",
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .border(2.dp, Color.Gray, CircleShape)
        )
    }
}

@Composable
fun RoundIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    backgroundColor: Color = Color.LightGray,
    iconTint: Color = Color.Black,
    enabled: Boolean = true
) {
    val actualBackground = if (enabled) backgroundColor else backgroundColor.copy(alpha = 0.4f)
    val actualTint = if (enabled) iconTint else iconTint.copy(alpha = 0.6f)
    val textColor = Color(0XFF7F7F7F)
    val actualText = if (enabled) textColor else textColor.copy(alpha = 0.6f)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(actualBackground)
                .let {
                    if (enabled) it.clickable(onClick = onClick) else it
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = icon, contentDescription = label, tint = actualTint)
        }

        if (label.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = actualText
            )
        }
    }
}

//
@Composable
@Preview
fun DefaultPreview() {
    val metaData: HashMap<*, *> = hashMapOf(
        "initializing" to "Initializing",
        "call_title" to "Telpone gratis",
        "ringing" to "Ringing",
        "connected" to "Connected",
        "ended" to "Ended",
        "answer" to "Answer",
        "decline" to "Decline",
        "mute" to "Mute",
        "unmute" to "Unmute",
        "speaker" to "Speaker",
        "phone_speaker" to "Phone Speaker",
    )
    Box(modifier = Modifier.fillMaxSize()) {
    CallScreen(
        "Driver Andhi",
        "",
        "connected",
        signalState = "call_lost_connection",
        "",
        isMicMuted = true,
        isSpeakerOn = false,
        isOnBluetooth = true,
        metaData = metaData.mapKeys { it.key.toString() }.mapValues { it.value.toString() },
        onMuteClick = {},
        onEndCallClick = {},
        onAnswerCallClick = {},
        onSpeakerClick = {},
        onMessageClick = {}
    )
        ErrorAlertDialog(
            showDialog = true,
            onDismiss = {
            },
            withIcon = true,
            message = "networkErrorText"
        )
    }
}
