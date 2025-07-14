package cc.cicare.sdkcall.event

import org.webrtc.PeerConnection.PeerConnectionState

/**
 * Represents the current state of a call lifecycle.
 */
enum class CallState {

    /**
     * Call is outgoing.
     */
    CALLING,

    /**
     * Call is incoming and ringing on the callee side.
     */
    RINGING,

    /**
     * Call is being connected and signaling/negotiation is in progress.
     */
    CONNECTING,

    /**
     * Call has been answered and media stream is active.
     */
    ANSWERED,

    /**
     * Call has ended either by remote, local, or due to disconnection.
     */
    ENDED
}

/**
 * Interface to listen for call-related events from the SDK.
 *
 * Implement this interface to receive updates about connection and call state.
 */
interface CallEventListener {

    /**
     * Called whenever the WebRTC peer connection state changes.
     *
     * @param state The new connection state as defined by WebRTC.
     */
    fun onConnectionStateChanged(state: PeerConnectionState)

    /**
     * Called when the logical state of the call changes (e.g., ringing, answered, ended).
     *
     * @param callState The updated call state.
     */
    fun onCallStateChanged(callState: CallState)
}
