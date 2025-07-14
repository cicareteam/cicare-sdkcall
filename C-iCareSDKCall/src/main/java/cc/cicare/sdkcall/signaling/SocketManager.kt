package cc.cicare.sdkcall.signaling

import android.util.Log
import cc.cicare.sdkcall.event.CallEventListener
import cc.cicare.sdkcall.event.CallState
import cc.cicare.sdkcall.rtc.WebRTCManager
import io.socket.client.IO
import io.socket.client.Socket
import org.json.JSONObject
import org.webrtc.SessionDescription

class SocketManager(
    private val callEventListener: CallEventListener,
    private val webrtcManager: WebRTCManager
) {
    private var socket: Socket? = null

    fun connect(wssUrl: String, token: String) {
        val opts = IO.Options().apply {
            query = "token=$token"
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1000
        }

        socket = IO.socket(wssUrl, opts)
        socket?.connect()

        socket?.on("ACCEPTED") { args ->
            callEventListener.onCallStateChanged(CallState.ANSWERED)
            Log.i("SOCKET", "ACCEPTED$args")
        }

        socket?.on("HANGUP") { args ->
            callEventListener.onCallStateChanged(CallState.ENDED)
            webrtcManager.close()
            Log.i("SOCKET", "HANGUP$args")
        }

        socket?.on("SDP_OFFER") { args ->
            Log.i("SOCKET", "SDP_OFFER$args")
            callEventListener.onCallStateChanged(CallState.CONNECTING)
            val json = args[0] as JSONObject
            val sdpString = json.getString("sdp")  // actual SDP content
            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            webrtcManager.setLocalDescription(sdp)
        }

        socket?.on("RINGING") { args ->
            callEventListener.onCallStateChanged(CallState.RINGING)
            Log.i("SOCKET", "RINGING$args")
        }

        socket?.on("SDP_ANSWER") { args ->
            callEventListener.onCallStateChanged(CallState.CONNECTING)
            val json = args[0] as JSONObject
            val sdpString = json.getString("sdp")  // actual SDP content
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
            webrtcManager.setRemoteDescription(sdp)
            Log.i("SOCKET", "SDP_ANSWER $sdpString")
        }
    }

    fun send(event: String, data: JSONObject) {
        socket?.emit(event, data)
    }

    fun disconnect() {
        socket?.disconnect()
    }
}

interface SocketListener {
    fun onSuccessInitialize() {}
    fun onAnsweredCall() {}
    fun onEndedCall() {}
}