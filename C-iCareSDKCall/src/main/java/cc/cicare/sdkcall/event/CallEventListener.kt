package cc.cicare.sdkcall.event

import org.webrtc.PeerConnection.PeerConnectionState

enum class CallState {
    RINGING,
    CONNECTING,
    ANSWERED,
    ENDED
}

enum class ConnectionState {

}

interface CallEventListener {
    fun onConnectionStateChanged(state: PeerConnectionState)
    fun onCallStateChanged(callState: CallState)
}