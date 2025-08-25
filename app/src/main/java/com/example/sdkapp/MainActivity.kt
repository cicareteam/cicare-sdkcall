package com.example.sdkapp

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import cc.cicare.sdkcall.CiCareSdkCall
import com.example.sdkapp.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        CiCareSdkCall.init(this)
        CiCareSdkCall.setAPI("https://sip-gw.c-icare.cc:8443/",
            "Q8v7X2pL9sT4bW1eR6kJ3zF0aC5dN8hU7yV5qS2mP4aZ6xC3rB8wL1tG9fE0hJ7kU5sT2vB9nM3qP8rD6wF4zL1yC7xA0hE")
        //CiCareSdkCall.setRingTone()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            CiCareSdkCall.init(this).checkAndRequestPermissions(this)
        }
        enableEdgeToEdge()
        val metaData: Map<String, String> = hashMapOf(
            "calling" to "Memanggil...",
            "incoming" to "Panggilan Masuk",
            "ringing" to "Berdering...",
            "connected" to "Terhubung",
            "ended" to "Tutup",
            "answer" to "Jawab",
            "decline" to "Tolak",
            "mute" to "Bisu",
            "unmute" to "Tidak Bisu",
            "speaker" to "Nyaring",
        )
//        FirebaseApp.initializeApp(this)
//        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
//            Log.d("SDK Call", "Token: $token")
//        }
        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TestServiceButtons(
                        modifier = Modifier
                            .padding(innerPadding)
                            .padding(24.dp),
                        onStartOutbound = {
                            lifecycleScope.launch {
                                CiCareSdkCall.makeCall(
                                    "3",
                                    "Anis",
                                    "https://avatar.iran.liara.run/public/",
                                    "4",
                                    "Al",
                                    "https://avatar.iran.liara.run/public/",
                                    "",
                                    metaData
                                )
                            }
                        },
                        onStartInbound = {

                        }
//                        onStartInbound = {
//                            CiCareSdkCall.init(this).showIncoming(
//                                "1",
//                                "Annas",
//                                "",
//                                "",
//                                "",
//                                "",
//                                "",
//                                metaData,
//                                "djksfgakjsdghfjkadsfgajkdsfgjasd",
//                                "http://sip-gq.c-icare.cc:8443/",
//                                false
//                            )
//                        }
                    )
                }
            }
        }
    }
}

@Composable
fun TestServiceButtons(
    modifier: Modifier = Modifier,
    onStartOutbound: () -> Unit,
    onStartInbound: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(onClick = onStartOutbound, modifier = Modifier.fillMaxWidth()) {
            Text("Start Outbound Call Service")
        }

        Button(onClick = onStartInbound, modifier = Modifier.fillMaxWidth()) {
            Text("Start Inbound Call Service")
        }
    }
}