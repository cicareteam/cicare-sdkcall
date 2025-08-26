package cc.cicare.sdkcall.signaling

import android.util.Log
import cc.cicare.sdkcall.event.CallStateListener
import cc.cicare.sdkcall.event.CallState
import cc.cicare.sdkcall.rtc.WebRTCManager
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.webrtc.SessionDescription

/**
 * Manages the socket connection for signaling between peers in a WebRTC call.
 *
 * This class establishes and listens to signaling events through a WebSocket
 * connection using the Socket.IO client. It communicates SDP offers/answers and
 * call control events such as HANGUP, RINGING, etc.
 *
 */
class SocketManager {
    private var socket: Socket? = null
    private var callStateListener: CallStateListener? = null

    private var webRTCManager: WebRTCManager? = null

    fun setCallStateListener(callStateListener: CallStateListener) {
        this.callStateListener = callStateListener
    }

    fun setWebrtc(webRTCManager: WebRTCManager) {
        this.webRTCManager = webRTCManager
    }

    /**
     * Connects to the signaling server via WebSocket using Socket.IO protocol.
     *
     * @param wssUrl The WebSocket URL (e.g. wss://example.com).
     * @param token Authentication token passed as query parameter.
     */
    fun connect(wssUrl: String, token: String) {
        //Log.i("SDK Call", "Connecting to $wssUrl")
        val opts = IO.Options().apply {
            query = "token=$token"
            reconnection = true
            reconnectionAttempts = Int.MAX_VALUE
            reconnectionDelay = 1000
        }

        socket = IO.socket(wssUrl, opts)
        socket?.connect()

        socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = args.getOrNull(0)
            Log.e("SocketManager", "Socket connection error: $error")
            //callStateListener?.onCallStateChanged(CallState.ENDED)

            this.disconnect()
        }

        socket?.on("MISSED_CALL") {
            callStateListener?.onCallStateChanged(CallState.ENDED)
        }

        socket?.on("RINGING_OK") {
            callStateListener?.onCallStateChanged(CallState.RINGING_OK)
        }

        // Event when the callee accepts the call
        socket?.on("INIT_OK") { _ ->
            Log.i("SDK CALL", "INIT_OK")
            callStateListener?.onCallStateChanged(CallState.CALLING)
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val offer = webRTCManager?.createOffer()

                    offer?.let {
                        send("SDP_OFFER", JSONObject().apply {
                            put("is_caller", true)
                            put("sdp", JSONObject().apply {
                                put("type", "offer")
                                put("sdp", it.description)
                            })
                        })
                    }
                } catch (e: Exception) {
                    Log.e("SDK CALL", "Error creating offer: ${e.message}", e)
                }
            }
        }

        // Event when the callee accepts the call
        socket?.on("ANSWER_OK") { _ ->
            callStateListener?.onCallStateChanged(CallState.ANSWERING)
        }

//        // Event when the callee accepts the call
//        socket?.on("ACCEPTED") { _ ->
//            callStateListener?.onCallStateChanged(CallState.CONNECTING)
//        }

        // Event when the callee accepts the call
        socket?.on("CONNECTED") { _ ->
            callStateListener?.onCallStateChanged(CallState.CONNECTED)
        }

        // Event when the call is ended from either side
        socket?.on("HANGUP") { _ ->
            callStateListener?.onCallStateChanged(CallState.ENDED)
            webRTCManager?.close()
            socket?.disconnect()
        }

        // Received SDP offer from the remote peer
        socket?.on("SDP_OFFER") { args ->
            //callEventListener.onCallStateChanged(CallState.CONNECTING)
            val json = args[0] as JSONObject
            val sdpString = json.getString("sdp")
            if (webRTCManager == null) {
                Log.e("SocketManager", "SignalingHelper is null! Cannot initRTC")
            } else {
                webRTCManager?.init()
                webRTCManager?.initMic()
            }

            val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
            webRTCManager?.setRemoteDescription(sdp)
        }

        // Ringing event sent to callee to indicate incoming call
        socket?.on("RINGING") { _ ->
            callStateListener?.onCallStateChanged(CallState.RINGING)
        }

        // Ringing event sent to callee to indicate incoming call
        socket?.on("REJECTED") { _ ->
            callStateListener?.onCallStateChanged(CallState.REFUSED)
        }

        // Received SDP answer from remote peer
        socket?.on("SDP_ANSWER") { args ->
            //callEventListener.onCallStateChanged(CallState.CONNECTING)
            val json = args[0] as JSONObject
            val sdpString = json.getString("sdp")
            Log.i("SDK CALL SDP_ANSWER", sdpString)
            val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
            webRTCManager?.setRemoteDescription(sdp)
        }
    }

    /**
     * Sends a signaling event through the WebSocket connection.
     *
     * @param event The event name (e.g. SDP_OFFER, SDP_ANSWER, etc.)
     * @param data The event payload in JSON format.
     */
    fun send(event: String, data: JSONObject) {
        socket?.emit(event, data)
    }

    /**
     * Disconnects the WebSocket connection.
     */
    fun disconnect() {
        socket?.disconnect()
    }
}