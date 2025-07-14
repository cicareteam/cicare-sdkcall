package cc.cicare.sdkcalltest

import android.content.Context
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cc.cicare.sdkcall.CiCareCall
import cc.cicare.sdkcall.event.CallEventListener
import cc.cicare.sdkcall.event.CallState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.webrtc.PeerConnection

class IncomingCallActivity : ComponentActivity(), CallEventListener {
    private lateinit var sdkCall: CiCareCall
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val callerName = intent.getStringExtra("caller_name") ?: "Unknown"
        val tokenAnswer = intent.getStringExtra("token_receive") ?: return
        val server = intent.getStringExtra("server") ?: return

        sdkCall = CiCareCall(this, this)

        //registerReceiver(incomingCallReceiver, IntentFilter("INCOMING_CALL"))
        /*if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.i("SDK", "INIT INCOMING" + Build.VERSION.SDK_INT)
            registerReceiver(incomingCallReceiver, IntentFilter("INCOMING_CALL"), RECEIVER_EXPORTED)
        } else {
            Log.i("SDK", "INIT INCOMING" + Build.VERSION.SDK_INT)
            registerReceiver(incomingCallReceiver, IntentFilter("INCOMING_CALL"))
        }*/
        sdkCall.checkMicPermission(this)

        sdkCall.initReceive(server, tokenAnswer)
        enableEdgeToEdge()
        setContent {
            MainApp(
                callerName,
                tokenAnswer,
                server,
                sdkCall
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // Stop WebRTC NetworkMonitor (jika kamu inisialisasi secara manual)
        org.webrtc.NetworkMonitor.getInstance().stopMonitoring()

        // Release PeerConnectionFactory jika kamu buat sendiri
        sdkCall.close()
    }

    private var ringtone: Ringtone? = null

    fun playSystemRingtone(context: Context) {
        try {
            val uri: Uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context.applicationContext, uri)
            ringtone?.play()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopSystemRingtone() {
        ringtone?.stop()
    }

    @Composable
    fun MainApp(callerName: String,
                tokenAnswer: String,
                server: String,
                sdkCall: CiCareCall?,) {
        val navController = rememberNavController()
        //val callPayload = remember { AppState.callPayload }
        val context = LocalContext.current
        var userId by remember { mutableStateOf(0) }

        NavHost(navController, startDestination = Screen.Incoming.route) {

            composable(Screen.Incoming.route) {
                IncomingCallScreen(
                    callerName,
                    sdkCall,
                    navController
                )
            }

            composable(Screen.Call.route) {
                CallScreen(callerName, sdkCall)
            }

            /*composable(Screen.Call.route) {
                callPayload?.let { payload ->
                    CallScreen(
                        callerName = payload.callerName,
                        onAccept = { /* logic */ },
                        onReject = { /* logic */ }
                    )
                }
            }*/
        }
    }

    @Composable
    fun IncomingCallScreen(
        callerName: String,
        sdkCall: CiCareCall?,
        navController: NavHostController
    ) {
        val coroutineScope = rememberCoroutineScope()
        playSystemRingtone(LocalContext.current)
        sdkCall?.checkMicPermission(this)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray)
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(100.dp))
            Text(
                text = "$callerName is calling...",
                style = MaterialTheme.typography.headlineMedium
            )

            Spacer(modifier = Modifier.height(150.dp))

            CallPulseAnimation()

            Spacer(modifier = Modifier.height(250.dp))

            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier.fillMaxWidth()
            ) {
                Button(
                    onClick = {
                        coroutineScope.launch { // ✅ Panggil suspend function di sini
                            try {
                                sdkCall?.rejectCall()
                                stopSystemRingtone()
                                finish()
                            } catch (e: Exception) {
                                // tangani error kalau perlu
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Reject", tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Reject", color = Color.White)
                }

                Button(
                    onClick = {
                        coroutineScope.launch { // ✅ Panggil suspend function di sini
                            try {
                                sdkCall?.answerCall()
                                stopSystemRingtone()
                                navController.navigate(Screen.Call.route)
                            } catch (e: Exception) {
                                // tangani error kalau perlu
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Green)
                ) {
                    Icon(Icons.Default.Call, contentDescription = "Accept", tint = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Text("Accept", color = Color.White)
                }
            }
        }
    }

    @Composable
    fun CallScreen(
        callerName: String,
        sdkCall: CiCareCall?
    ) {
        val coroutineScope = rememberCoroutineScope()
        var timer by remember { mutableStateOf("00:00") }
        val volumeLevel = remember { mutableStateOf(0.1f) } // dari 0.0 (diam) ke 1.0 (keras)
        var isMicMuted by remember { mutableStateOf(false) }
        var isOnLoadSpeaker by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val startTime = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val seconds = (elapsed / 1000).toInt()
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                timer = String.format("%02d:%02d", minutes, remainingSeconds)
                delay(1000)
            }
        }

        // Animasi dari volume
        val animatedScale by animateFloatAsState(
            targetValue = 1f + (volumeLevel.value * 1.5f),
            animationSpec = tween(200),
            label = "volumeScale"
        )

        val animatedAlpha by animateFloatAsState(
            targetValue = 0.3f + (volumeLevel.value * 0.5f),
            animationSpec = tween(200),
            label = "volumeAlpha"
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray)
                .padding(bottom = 80.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(100.dp))
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "$callerName",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "Connected",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = timer,
                    style = MaterialTheme.typography.bodyLarge
                )
            }

            Spacer(modifier = Modifier.height(150.dp))

            CallPulseAnimation(
                scaleC = animatedScale,
                alphaC = animatedAlpha
            )

            Spacer(modifier = Modifier.height(100.dp))

            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 30.dp)
                ) {
                    // Tombol Mute/Unmute Mic
                    IconButton(
                        onClick = {
                            isMicMuted = !isMicMuted
                            sdkCall?.setMute(!isMicMuted)
                        },
                        modifier = Modifier
                            .size(32.dp) // ✅ Kontrol ukuran tombol
                            .background(
                                color = if (isMicMuted) Color.Gray else Color.Green,
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isMicMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = "Toggle Mic",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    // Tombol Toggle Speaker
                    IconButton(
                        onClick = {
                            isOnLoadSpeaker = !isOnLoadSpeaker
                            if (isOnLoadSpeaker)
                                sdkCall?.setOnLoadSpeaker()
                            else
                                sdkCall?.setOnPhoneSpeaker()
                        },
                        modifier = Modifier
                            .size(32.dp) // ✅ Kontrol ukuran tombol
                            .background(
                                color = if (isOnLoadSpeaker) Color.Green else Color.Gray,
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = if (isOnLoadSpeaker) Icons.Filled.VolumeUp else Icons.Filled.Hearing,
                            contentDescription = "Toggle Speaker",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Button(
                        onClick = {
                            coroutineScope.launch { // ✅ Panggil suspend function di sini
                                try {
                                    sdkCall?.hangupCall()
                                    stopSystemRingtone()
                                    finish()
                                } catch (e: Exception) {
                                    // tangani error kalau perlu
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Call, contentDescription = "Hangup", tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Hangup", color = Color.White)
                    }
                }
            }
        }
    }

    @Composable
    fun CallPulseAnimation(
        icon: ImageVector = Icons.Default.Call,
        iconColor: Color = Color.White,
        pulseColor: Color = Color.Green,
        modifier: Modifier = Modifier,
        size: Dp = 120.dp,
        scaleC: Float? = null,
        alphaC: Float? = null
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "pulse")

        val scale = scaleC ?: run {
            infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1000, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "scale"
            ).value
        }

        val alpha = alphaC ?: run {
            infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1000),
                    repeatMode = RepeatMode.Restart
                ),
                label = "alpha"
            ).value
        }

        Box(
            modifier = modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            // Pulse layer
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .background(pulseColor.copy(alpha = 0.5f), shape = CircleShape)
            )

            // Static background circle
            Box(
                modifier = Modifier
                    .size(size / 2)
                    .background(pulseColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = "Call",
                    tint = iconColor,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }


    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        Log.i("PEER_STATE", state.toString())
    }

    override fun onCallStateChanged(callState: CallState) {

    }

    @Composable
    @Preview
    fun PreviceScreen() {
        CallScreen(
            "Test",
            null
        )
    }
}