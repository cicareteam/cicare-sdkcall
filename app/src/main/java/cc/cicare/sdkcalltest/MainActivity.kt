package cc.cicare.sdkcalltest

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import cc.cicare.sdkcall.CiCareCall
import cc.cicare.sdkcall.event.CallEventListener
import cc.cicare.sdkcall.event.CallState
import com.google.firebase.messaging.FirebaseMessaging
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import org.webrtc.PeerConnection
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query


class MainActivity : ComponentActivity(), CallEventListener {

    private lateinit var sdkCall: CiCareCall

    private var callState = mutableStateOf<CallState?>(null)

    private var calldest = mutableStateOf<String>("")

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { isGranted: Boolean ->
        if (isGranted) {
            Log.i("PERMISSION", "GRANTED")
        } else {
            Log.i("PERMISSION", "NOT GRANTED")
        }
    }

    private fun askNotificationPermission() {
        // This is only necessary for API level >= 33 (TIRAMISU)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                // FCM SDK (and your app) can post notifications.
            } else if (shouldShowRequestPermissionRationale(android.Manifest.permission.POST_NOTIFICATIONS)) {
                Log.i("PERMISSION", "APA YA?")
            } else {
                // Directly ask for the permission
                requestPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    //@SuppressLint("UnspecifiedRegisterReceiverFlag")
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        enableEdgeToEdge()
        setContent {
            askNotificationPermission()
            MainApp(sdkCall)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionNotification()
            }
        }

        //if (intent.getBooleanExtra("navigate_to_call", false)) {
        //    AppState.navController?.navigate(Screen.Call.route)
        //}
    }



    private fun requestPermissionNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    101
                )
            }
        }
    }

    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        Log.i("PEER_STATE", state.toString())
    }

    override fun onCallStateChanged(callState: CallState) {
        this.callState.value = callState
    }

    override fun onDestroy() {
        super.onDestroy()
        sdkCall.close()
    }

    override fun onResume() {
        super.onResume()
        /*AppState.callAnswerToken?.let { token ->
            AppState.server?.let { server ->
                lifecycleScope.launch {
                    sdkCall.initAnswer(server, token)
                }
            }
        }*/
    }



    @Composable
    fun MainApp(sdkCall: CiCareCall) {
        val navController = rememberNavController()
        //val callPayload = remember { AppState.callPayload }

        val context = LocalContext.current
        val sessionManager = remember { UserSessionManager(context) }
        val savedUserId = sessionManager.getUserId()
        val savedUserName = sessionManager.getUserName()
        var userId by remember { mutableStateOf(savedUserId ?: 0) }
        var username by remember { mutableStateOf(savedUserName ?: "") }
        var password by remember { mutableStateOf("") }

        val startDestination = if (savedUserId != null && savedUserId > 0) {
            Screen.Home.route
        } else {
            Screen.Login.route
        }

        NavHost(navController, startDestination = startDestination) {
            composable(Screen.Login.route) {
                LoginScreen(
                    username = username,
                    password = password,
                    onUsernameChange = { username = it },
                    onPasswordChange = { password = it },
                    onLoginSuccess = { user ->
                        userId = user.id
                        val sessionManager = UserSessionManager(context)
                        sessionManager.saveUserId(user.id, user.username)
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    }
                )
            }

            composable(Screen.Home.route) {
                HomeScreen(userId, username, navController)
            }

            composable(Screen.App2Phone.route) {
                App2Phone(userId, username, sdkCall, navController)
            }

            composable(Screen.App2App.route) {
                App2App(userId, username, sdkCall, navController)
            }
            composable(Screen.Call.route) {
                CallScreen(calldest, sdkCall, navController, callState)
            }
        }
    }

    @Composable
    fun CallScreen(
        callerName: State<String>,
        sdkCall: CiCareCall?,
        navController: NavHostController,
        callState: State<CallState?>
    ) {
        val coroutineScope = rememberCoroutineScope()
        var timer by remember { mutableStateOf("00:00") }
        val volumeLevel = remember { mutableStateOf(0.1f) } // dari 0.0 (diam) ke 1.0 (keras)
        var isMicMuted by remember { mutableStateOf(false) }
        var isOnLoadSpeaker by remember { mutableStateOf(false) }

        LaunchedEffect(callState.value) {
            if (callState.value === CallState.ANSWERED) {
                val startTime = System.currentTimeMillis()
                while (callState.value === CallState.ANSWERED) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val seconds = (elapsed / 1000).toInt()
                    val minutes = seconds / 60
                    val remainingSeconds = seconds % 60
                    timer = String.format("%02d:%02d", minutes, remainingSeconds)
                    delay(1000)
                }
            } else if (callState.value === CallState.ENDED) {
                navController.popBackStack()
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
                .padding(bottom = 50.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(100.dp))
            Column(
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "${callerName.value}",
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    text = "CALL ${callState.value.toString()}",
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

            Spacer(modifier = Modifier.height(250.dp))

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
                                    navController.popBackStack()
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
    fun App2App(userId: Int, currentUsername: String, sdkCall: CiCareCall, navController: NavHostController) {
        var destination by remember { mutableStateOf<UserOnline?>(null) }
        var loading by remember { mutableStateOf(false) }
        var context = LocalContext.current
        val coroutineScope = rememberCoroutineScope()
        var onlineUsers by remember { mutableStateOf<List<UserOnline>>(emptyList()) }

        LaunchedEffect(Unit) {
            try {
                val response = ApiClient.api.getUserOnline(userId = userId)
                if (response.isSuccessful) {
                    onlineUsers = response.body() ?: emptyList()
                    Log.i("ONLINE_USERS","")
                    response.body()?.get(0)?.name?.let { Log.i("ONLINE_USERS", it) }
                } else {
                    Log.e("API", "Failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("API", "Exception: ${e.message}")
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("App to App", style = MaterialTheme.typography.headlineSmall)

            Spacer(modifier = Modifier.height(24.dp))

            if (onlineUsers.isNotEmpty()) {
                OnlineUserDropdown(
                    users = onlineUsers,
                    selectedUser = destination,
                    onUserSelected = { destination = it }
                )
            } else {
                Text("No users are online yet", style = MaterialTheme.typography.bodyLarge)
            }

            Button(onClick = {
                coroutineScope.launch {
                    loading = true
                    if (destination === null) {
                        Toast.makeText(context, "Pilih user tujuan terlebih dahulu", Toast.LENGTH_SHORT).show()
                    }
                    destination?.let { target ->
                        try {
                            val response = ApiClient.api.requestCall(CallRequest(userId, true, ""+target.id))
                            if (response.isSuccessful) {
                                Log.i("APP TO APP", response.body().toString())
                                response.body()
                                    ?.let {
                                        callState.value = CallState.CALLING
                                        calldest.value = target.name
                                        sdkCall.initCall(it.tokenCall, it.server)
                                        navController.navigate(Screen.Call.route)
                                    }
                            } else {
                                Log.e("Call", "Call failed: ${response.code()}")
                            }
                        } catch (e: Exception) {
                            Log.e("Call", "Error: ${e.message}")
                        }
                    }
                    loading = false
                }

            }, enabled = !loading) {
                Icon(Icons.Default.Call, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Call User")
            }
        }
    }
}

sealed class Screen(val route: String) {
    object Login : Screen("login")
    object Home : Screen("home")
    object App2Phone: Screen("app_to_phone")
    object App2App: Screen("app_to_app")
    object Call: Screen("call")
    object Incoming: Screen("incoming")

}

@Composable
fun HomeScreen(userId: Int, username: String, navController: NavHostController) {

    FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val response = ApiClient.api.saveToken(TokenSaveRequest(userId, token))
                if (response.isSuccessful) {
                    Log.d("FCM", "Token saved")
                } else {
                    Log.e("FCM", "Token failed: ${response.code()}")
                }
            } catch (e: Exception) {
                Log.e("FCM", "Error saving token: ${e.message}")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(50.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Welcome $username",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.padding(bottom = 100.dp))
        Column {
            ImageTextButtonVertical(
                text = "App to App",
                modifier = Modifier.fillMaxWidth(),
                imageResId = R.drawable.ic_call,
                onClick = {
                    navController.navigate(Screen.App2App.route)
                }
            )
            Spacer(
                modifier = Modifier.padding(all = 20.dp)
            )
            ImageTextButtonVertical(
                text = "App to Phone",
                modifier = Modifier.fillMaxWidth(),
                imageResId = R.drawable.ic_call,
                onClick = {
                    navController.navigate(Screen.App2Phone.route)
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnlineUserDropdown(
    users: List<UserOnline>,
    selectedUser: UserOnline?,
    onUserSelected: (UserOnline) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded }
    ) {


        TextField(
            value = selectedUser?.name ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("Select User") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth()
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            users.forEach { user ->
                DropdownMenuItem(
                    text = { Text(user.name) },
                    onClick = {
                        onUserSelected(user)
                        expanded = false
                    }
                )
            }
        }
    }
}


@Composable
fun App2Phone(userId: Int, currentUsername: String, sdkCall: CiCareCall, navController: NavHostController) {
    var destination by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("App to Phone", style = MaterialTheme.typography.headlineSmall)

        Spacer(modifier = Modifier.height(24.dp))

        TextField(
            value = destination,
            onValueChange = { destination = it },
            label = { Text("Phone Number") }
        )

        Button(onClick = {
            coroutineScope.launch {
                loading = true
                try {
                    val response = ApiClient.api.requestCall(CallRequest(userId, false, destination))
                    if (response.isSuccessful) {
                        response.body()
                            ?.let {
                                sdkCall.initCall(it.tokenCall, it.server)
                                navController.navigate(Screen.Call.route)
                            }
                    } else {
                        Log.e("Call", "Call failed: ${response.code()}")
                    }
                } catch (e: Exception) {
                    Log.e("Call", "Error: ${e.message}")
                }
                loading = false
            }

        }, enabled = !loading) {
            Icon(Icons.Default.Call, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Call Phone")
        }
    }
}

@Composable
fun ImageTextButtonVertical(
    onClick: () -> Unit,
    imageResId: Int,
    text: String,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(id = imageResId),
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(text)
        }
    }
}

@Composable
fun LoginScreen(
    username: String,
    password: String,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onLoginSuccess: (User) -> Unit
) {
    var loading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Login", style = MaterialTheme.typography.headlineMedium)

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = username,
            onValueChange = onUsernameChange,
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = password,
            onValueChange = onPasswordChange,
            label = { Text("Password") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = {
                coroutineScope.launch {
                    loading = true
                    try {
                        val response = ApiClient.api.login(LoginRequest(username, password))
                        if (response.isSuccessful) {
                            response.body()?.user?.let { onLoginSuccess(it) }
                        } else {
                            Log.e("Login", "Login failed: ${response.code()}")
                        }
                    } catch (e: Exception) {
                        Log.e("Login", "Error: ${e.message}")
                    }
                    loading = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !loading
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text("Login")
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

data class UserOnline(
    val id: Int,
    @SerializedName("username")
    val name: String
)

data class LoginRequest(val username: String, val password: String)

data class TokenSaveRequest(val user_id: Int, val fcm_token: String)

data class LoginResponse(val message: String, val user: User?)

data class CallRequest(val user_id: Int, val is_online: Boolean, val destination: String)

data class CallResponse(
    @SerializedName("tokenCall")
    val tokenCall: String,
    @SerializedName("tokenAnswer")
    val tokenAnswer: String?,
    val server: String)

data class SuccessResponse(val message: String)

data class User(
    val id: Int,
    val username: String,
    val fcm_token: String?
)

interface ApiService {
    @POST("api/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @POST("api/save-token")
    suspend fun saveToken(@Body request: TokenSaveRequest): Response<SuccessResponse>

    @GET("api/user-online")
    suspend fun getUserOnline(@Query("user_id") userId: Int): Response<List<UserOnline>>

    @POST("api/request-call")
    suspend fun requestCall(@Body request: CallRequest): Response<CallResponse>
}

object ApiClient {
    private const val BASE_URL = "https://sip-gw.c-icare.cc:4443/" // untuk emulator Android

    val api: ApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            setLevel(HttpLoggingInterceptor.Level.BODY)
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .client(client)
            .build()
            .create(ApiService::class.java)
    }
}


