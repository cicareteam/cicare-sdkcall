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

/**
 * Main SDK class for managing voice call sessions between two clients using WebRTC and socket signaling.
 *
 * This class encapsulates call lifecycle including initialization, answering, hanging up, and managing audio output.
 *
 * @param context The application context.
 * @param callEventListener Callback interface for call state and connection updates.
 */
class CiCareCall(
    private val context: Context,
    private val callEventListener: CallEventListener
): WebRTCEventCallback {
    private lateinit var socketManager: SocketManager
    private lateinit var webrtcManager: WebRTCManager

    /**
     * Initialize a new outgoing call.
     *
     * @param token Authentication token for signaling server.
     * @param server URL or IP address of the signaling server.
     */
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

    /**
     * Initialize an incoming call.
     *
     * @param server The signaling server address.
     * @param token The authentication token for incoming / receiving call.
     */
    fun initReceive(server: String, token: String) {
        webrtcManager = WebRTCManager(context, this)
        socketManager = SocketManager(callEventListener, webrtcManager)
        socketManager.connect(server, token)
        socketManager.send("RINGING_CALL", JSONObject().apply {})
    }

    /**
     * Answer an incoming call.
     * This will initialize WebRTC and send an SDP offer back to the caller.
     */
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

    /**
     * Reject the current incoming call.
     */
    fun rejectCall() {
         socketManager.send("REJECT", JSONObject().apply {})
    }

    /**
     * Hang up the current call session.
     */
    fun hangupCall() {
        socketManager.send("REQUEST_HANGUP", JSONObject().apply {})
    }

    /**
     * Check microphone permission and request if not granted.
     *
     * @param activity The current activity, required for permission request.
     */
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

    /**
     * Enable or disable the local microphone.
     *
     * @param muted If true, the mic is muted.
     */
    fun setMute(muted: Boolean) {
        webrtcManager.setMicEnabled(!muted)
    }

    /**
     * Set audio output to speaker (loud speaker mode).
     */
    fun setOnLoadSpeaker() {
        webrtcManager.setAudioOutputToSpeaker(true)
    }

    /**
     * Set audio output to phone earpiece (normal call mode).
     */
    fun setOnPhoneSpeaker() {
        webrtcManager.setAudioOutputToSpeaker(false)
    }

    /**
     * Release all resources and close connections.
     */
    fun close() {
        if (webrtcManager.isPeerConnectionActive())
            webrtcManager.close()
        socketManager.disconnect()
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
        callEventListener.onConnectionStateChanged(state);
    }
}