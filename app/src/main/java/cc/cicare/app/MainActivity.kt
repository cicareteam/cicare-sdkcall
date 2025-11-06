package cc.cicare.app

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import cc.cicare.sdkcall.CiCareSdkCall
import cc.cicare.app.theme.MyApplicationTheme
import com.google.firebase.installations.FirebaseInstallations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import androidx.core.content.edit
import cc.cicare.sdkcall.event.CallEventListener
import cc.cicare.sdkcall.event.CallState
import com.google.firebase.messaging.FirebaseMessaging

// ------------ Data Models ------------
data class User(
    val id: String,
    val name: String,
    val avatar: String
)

class MainActivity : ComponentActivity(), CallEventListener {

    private val requiredPermissions = mutableListOf(
        android.Manifest.permission.READ_PHONE_STATE
    ).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(android.Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private fun checkAndRequestPermissions(): Boolean {
        val notGranted = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        return if (notGranted.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, notGranted.toTypedArray(), 101)
            false
        } else {
            true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CiCareSdkCall.init(this)
        CiCareSdkCall.setEventListener(this)


        CiCareSdkCall.setAPI(
            "https://sip-gw.c-icare.cc:8443",
            "a1b2c3d4e5f60718293a4b5c6d7e8f90123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")

        val uri = Uri.parse("android.resource://${this.packageName}/${cc.cicare.sdkcall.R.raw.miremix}")

        CiCareSdkCall.setRingTone(uri)
        enableEdgeToEdge()

        val context = this

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Box(modifier = Modifier.fillMaxHeight().padding(innerPadding)) {
                        ContentView(
                            activity = this@MainActivity,
                            onLoggedIn = { ->
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    checkAndRequestPermissions()
                                }
                        }
                        )
                    }
                }
            }
        }
    }

    override fun onCallStateChange(callState: CallState) {
        Log.i("SDK Call", "onCallStateChange: $callState")
    }

    override fun onError(code: Int, message: String) {
        Log.e("SDKCALL ERROR", "onError: $code $message")
    }
}
// ------------ Main ContentView ------------
@Composable
fun ContentView(
    activity: ComponentActivity,
    onLoggedIn: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    var isLoggedIn by remember { mutableStateOf(false) }
    var currentUserId by remember { mutableIntStateOf(0) }
    var username by remember { mutableStateOf("") }
    var avatar by remember { mutableStateOf("") }
    var loginError by remember { mutableStateOf<String?>(null) }

    // Autologin
    LaunchedEffect(Unit) {
        val savedId = prefs.getInt("currentUserId", 0)
        val savedUsername = prefs.getString("username", null)
        val savedAvatar = prefs.getString("avatar", null)

        if (savedId != 0 && savedUsername != null) {
            currentUserId = savedId
            username = savedUsername
            avatar = savedAvatar ?: ""
            isLoggedIn = true

        }
    }

    if (isLoggedIn) {
        onLoggedIn()
        FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Log.w("FCM", "Fetching FCM registration token failed", task.exception)
                    return@addOnCompleteListener
                }
                val token = task.result
                CoroutineScope(Dispatchers.IO).launch {
                    val response = ApiClient.api.saveToken(TokenSaveRequest(currentUserId, token))
                    if (response.isSuccessful) {
                        Log.d("SDK Call", "Token saved ${token}")
                    } else {
                        Log.e("SDK Call", "Token failed: ${response.code()}")
                    }
                }
        }
        CallView(
            currentUserId = currentUserId,
            username = username,
            activity = activity,
            onLogout = {
                FirebaseInstallations.getInstance().delete()
                prefs.edit { clear() }
                currentUserId = 0
                username = ""
                avatar = ""
                isLoggedIn = false
            }
        )
    } else {
        LoginView(
            errorMessage = loginError,
            onLogin = { user, pass ->
                login(
                    username = user,
                    password = pass,
                    prefs = prefs,
                    onSuccess = { id, uname, av ->
                        currentUserId = id
                        username = uname
                        avatar = av
                        isLoggedIn = true
                        loginError = null
                    },
                    onError = { msg ->
                        loginError = msg
                    }
                )
            }
        )
    }
}

// ------------ Login View ------------
@Composable
fun LoginView(
    errorMessage: String?,
    onLogin: (String, String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.padding(120.dp))
            Text("Login", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.padding(20.dp))
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Your Username") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            if (isLoading) {
                CircularProgressIndicator()
            }

            Button(
                onClick = {
                    if (username.isNotEmpty() && password.isNotEmpty()) {
                        isLoading = true
                        onLogin(username, password)
                        isLoading = false
                    }
                },
                modifier = Modifier.padding(top = 16.dp)
            ) {
                Text("Login")
            }
        }
    }
}

// ------------ Call View ------------
@Composable
fun CallView(
    currentUserId: Int,
    activity: ComponentActivity,
    username: String,
    onLogout: () -> Unit
) {
    var users by remember { mutableStateOf(listOf<User>()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        users = fetchUsers(currentUserId)
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Welcome, $username", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = onLogout, colors = ButtonDefaults.buttonColors(MaterialTheme.colorScheme.error)) {
                Text("Logout")
            }
        }

        Spacer(modifier = Modifier.fillMaxWidth().padding(25.dp))

        if (isLoading) {
            CircularProgressIndicator()
        } else {
            LazyColumn {
                items(users.size) { i ->
                    val user = users[i]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = user.avatar,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(40.dp).clip(CircleShape)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(user.name, modifier = Modifier.weight(1f))
                        Button(onClick = { makeCall(activity, currentUserId, username, user) }) {
                            Text("Call")
                        }
                    }
                }
            }
        }
    }
}

// ------------ API Login ------------
fun login(
    username: String,
    password: String,
    prefs: SharedPreferences,
    onSuccess: (Int, String, String) -> Unit,
    onError: (String) -> Unit
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val url = URL("https://sip-gw.c-icare.cc:4443/api/login")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
                outputStream.write(
                    """
                    {"username":"$username","password":"$password","type":"android"}
                    """.trimIndent().toByteArray()
                )
            }

            val response = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(response)

            if (json.optBoolean("success")) {
                val user = json.getJSONObject("user")
                val id = user.getInt("id")
                val uname = user.getString("username")
                val avatar = user.optString("avatar_url", "")

                prefs.edit {
                    putInt("currentUserId", id)
                        .putString("username", uname)
                        .putString("avatar", avatar)
                }

                withContext(Dispatchers.Main) {
                    onSuccess(id, uname, avatar)
                }
            } else {
                withContext(Dispatchers.Main) {
                    onError("Invalid username or password")
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                onError("Login error: ${e.localizedMessage}")
            }
        }
    }
}

// ------------ Fetch Users ------------
suspend fun fetchUsers(currentUserId: Int): List<User> = withContext(Dispatchers.IO) {
    val url = URL("https://sip-gw.c-icare.cc:4443/api/user-online?user_id=$currentUserId")
    val conn = url.openConnection() as HttpURLConnection
    conn.requestMethod = "GET"

    return@withContext try {
        val response = conn.inputStream.bufferedReader().readText()
        val arr = org.json.JSONArray(response)
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.getJSONObject(i)
            val id = obj.optInt("id")
            val name = obj.optString("username")
            val avatar = obj.optString("avatar_url", "https://avatar.iran.liara.run/public/boy")
            if (id != currentUserId) User(id.toString(), name, avatar) else null
        }
    } catch (e: Exception) {
        emptyList()
    }
}

fun makeCall( activity: ComponentActivity, currentUserId: Int, username: String, user: User) {
    Log.i("SDK Call", "makeCall")
    CiCareSdkCall.makeCall(
        activity = activity,
        callerId = currentUserId.toString(),
        callerName = username,
        callerAvatar = "https://avatar.iran.liara.run/public/boy",
        calleeId = user.id,
        calleeName = user.name,
        calleeAvatar = user.avatar,
        checkSum = "asdfasdf",
        metaData = mapOf(
            "call_title" to "Free Call",
            "call_not_found" to "Call not found"
        )
    )
}

//@Preview
//@Composable
//fun TestServiceButtonsPreview() {
//    MyApplicationTheme {
//        ContentView()
//    }
//}