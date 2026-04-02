package cc.cicare.sdkcall.notifications.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import cc.cicare.sdkcall.notifications.ui.icons.SpeakerBluetooth
import coil.compose.AsyncImage

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
    onAnswerCallClick: () -> Unit,
    onEndCallClick: () -> Unit,
    onMessageClick: (() -> Unit)? = null,
) {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Box {
            MultiLayerGradientBackground()

            Column(
                modifier = Modifier.padding(padding).fillMaxSize(),
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
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = callTimer as String, style = MaterialTheme.typography.bodyLarge)
                        Spacer(modifier = Modifier.height(55.dp))
                        CallAvatar(avatarUrl)
                        Spacer(modifier = Modifier.height(35.dp))

                        Text(
                            text =
                                if (metaData["call_name_title"]?.isBlank() == true)
                                    callerName
                                else metaData["call_name_title"] ?: callerName,
                            style = MaterialTheme.typography.headlineSmall
                        )
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
                    modifier =
                        Modifier.fillMaxWidth()
                            .padding(horizontal = 32.dp, vertical = 15.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RoundIconButton(
                        icon =
                            if (isSpeakerOn) Icons.AutoMirrored.Filled.VolumeUp
                            else {
                                if (isOnBluetooth) SpeakerBluetooth
                                else Icons.AutoMirrored.Outlined.VolumeUp
                            },
                        label = metaData["call_btn_speaker"] ?: "Speaker",
                        onClick = onSpeakerClick,
                        backgroundColor =
                            if (isSpeakerOn) Color(0xFF00BABD) else Color(0xFFE9F8F9),
                        iconTint = if (isSpeakerOn) Color.White else Color(0xFF17666A),
                        enabled = callStatusRaw.lowercase() != "ended"
                    )

                    RoundIconButton(
                        icon = Icons.Default.MicOff,
                        label = metaData["call_btn_mute"] ?: "Mute",
                        onClick = onMuteClick,
                        backgroundColor =
                            if (isMicMuted) Color(0xFF00BABD) else Color(0xFFE9F8F9),
                        iconTint = if (isMicMuted) Color.White else Color(0xFF17666A),
                        enabled = callStatusRaw.lowercase() == "connected"
                    )

                    if (onMessageClick != null && callStatusRaw.lowercase() == "incoming") {
                        RoundIconButton(
                            icon = Icons.AutoMirrored.Outlined.Chat,
                            label = metaData["call_btn_message"] ?: "Message",
                            onClick = onMessageClick,
                            backgroundColor = Color(0xFFE9F8F9),
                            iconTint = Color(0xFF17666A),
                        )
                    }
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
                // }
            }
        }
    }
}

@Composable
fun MultiLayerGradientBackground(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize()) {
        // Layer 3: Base horizontal gradient (270deg)
        Box(
            modifier =
                Modifier.matchParentSize()
                    .background(
                        brush =
                            Brush.horizontalGradient(
                                colorStops =
                                    arrayOf(
                                        0.0f to
                                                Color(
                                                    0xFFFFF4DF
                                                ), // Left becomes
                                        // #FFF4DF
                                        0.5f to Color(0xFFFFFFFF),
                                        1.0f to
                                                Color(
                                                    0xFFDAFFFF
                                                ) // Right becomes
                                        // #DAFFFF
                                    )
                            )
                    )
        )

        // Layer 2: Vertical fade (180deg)
        Box(
            modifier =
                Modifier.matchParentSize()
                    .background(
                        brush =
                            Brush.verticalGradient(
                                colorStops =
                                    arrayOf(
                                        0.3167f to
                                                Color(
                                                    0x00F6F6F6
                                                ), // transparent
                                        1.0f to Color(0xFFF6F6F6)
                                    )
                            )
                    )
        )

        // Layer 1: Diagonal fade (224.7deg ≈ ~45° flip)
        Box(
            modifier =
                Modifier.matchParentSize()
                    .background(
                        brush =
                            Brush.linearGradient(
                                colorStops =
                                    arrayOf(
                                        0.3943f to
                                                Color(
                                                    0x00DFEFFF
                                                ), // transparent
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
            modifier = Modifier.size(160.dp).clip(CircleShape).background(Color.LightGray),
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
            modifier =
                Modifier.size(160.dp)
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
            modifier =
                Modifier.size(64.dp).clip(CircleShape).background(actualBackground).let {
                    if (enabled) it.clickable(onClick = onClick) else it
                },
            contentAlignment = Alignment.Center
        ) { Icon(imageVector = icon, contentDescription = label, tint = actualTint) }

        if (label.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, style = MaterialTheme.typography.bodySmall, color = actualText)
        }
    }
}