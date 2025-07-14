package cc.cicare.sdkcall

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import cc.cicare.sdkcall.event.CallEventListener
import cc.cicare.sdkcall.rtc.WebRTCEventCallback
import cc.cicare.sdkcall.rtc.WebRTCManager
import cc.cicare.sdkcall.signaling.SocketManager
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription

class CiCareCall(
    private val context: Context,
    private val callEventListener: CallEventListener
): WebRTCEventCallback {
    private lateinit var socketManager: SocketManager
    private lateinit var webrtcManager: WebRTCManager

    suspend fun initCall(token: String, server: String) {
        webrtcManager = WebRTCManager(context, this)
        webrtcManager.init()
        webrtcManager.initMic()
        socketManager = SocketManager(callEventListener, webrtcManager)
        socketManager.connect(server, token)
        socketManager.send("INIT_CALL", JSONObject().apply {
            put("is_caller", true)
            put("sdp", JSONObject().apply {
                put("type", "offer")
                put("sdp", webrtcManager.createOffer().description)
            })
        })
    }


    fun initReceive(server: String, token: String) {
        webrtcManager = WebRTCManager(context, this)
        socketManager = SocketManager(callEventListener, webrtcManager)
        socketManager.connect(server, token)
        socketManager.send("RINGING_CALL", JSONObject().apply {})
    }

    suspend fun answerCall() {
        webrtcManager.init()
        webrtcManager.initMic()
        socketManager.send("ANSWER_CALL", JSONObject().apply {
            put("is_caller", false)
            put("sdp", JSONObject().apply {
                put("type", "offer")
                put("sdp", webrtcManager.createOffer().description)
            })
        })
    }

    fun rejectCall() {
         socketManager.send("REJECT", JSONObject().apply {})
    }

    fun hangupCall() {
        socketManager.send("REQUEST_HANGUP", JSONObject().apply {})
    }

    fun checkMicPermission(activity: Activity) {
        if (ContextCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i("AUDIO", "PERMISSION_NOT_GRANTED")
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                1001
            )
        } else {
            Log.i("AUDIO", "PERMISSION_GRANTED")
        }
    }

    fun setMute(muted: Boolean) {
        webrtcManager.setMicEnabled(!muted)
    }

    fun setOnLoadSpeaker() {
        webrtcManager.setAudioOutputToSpeaker(true)
    }
    fun setOnPhoneSpeaker() {
        webrtcManager.setAudioOutputToSpeaker(false)
    }

    fun close() {
        if (webrtcManager.isPeerConnectionActive())
            webrtcManager.close()
        socketManager.disconnect()
    }

    override fun onLocalSdpCreated(sdp: SessionDescription) {
        socketManager.send("SDP_OFFER", JSONObject().apply {
            put("type", sdp.type)
            put("sdp", sdp.description)
        })
    }

    override fun onIceCandidateGenerated(candidate: IceCandidate) {
        socketManager.send("ICE_CANDIDATE", JSONObject().apply {
            put("sdpMid", candidate.sdpMid)
            put("sdpMLineIndex", candidate.sdpMLineIndex)
            put("candidate", candidate.sdp)
        })
    }

    override fun onRemoteStreamReceived(stream: MediaStream) {
        if (stream.audioTracks.isNotEmpty()) {
            val remoteAudioTrack = stream.audioTracks[0]
            remoteAudioTrack.setEnabled(true)
        }
    }

    override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
        callEventListener.onConnectionStateChanged(state);
    }
}