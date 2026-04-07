package cc.cicare.sdkcall.signaling

import android.util.Log
import cc.cicare.sdkcall.event.CallStateListener
import cc.cicare.sdkcall.event.CallState
import cc.cicare.sdkcall.event.ConnectionStateListener
import cc.cicare.sdkcall.rtc.WebRTCManager
import io.socket.client.IO
import io.socket.client.Socket
import io.socket.emitter.Emitter
import kotlinx.coroutines.*
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
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
    private var connectionStateListener: ConnectionStateListener? = null

    private var webRTCManager: WebRTCManager? = null
    private var disconnectCount: Int = 0

    private var connectStartTime: Long = 0
    private var pingStartTime: Long = 0
    private var latencyAverage: Double = 0.0
    private var pingThread: Thread? = null

    fun setCallStateListener(callStateListener: CallStateListener) {
        this.callStateListener = callStateListener
    }

    fun setConnectionStateListener(connectionStateListener: ConnectionStateListener) {
        this.connectionStateListener = connectionStateListener
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
            reconnectionAttempts = 3
            reconnectionDelay = 1000
            reconnectionDelayMax = 3000
            timeout = 5000
            forceNew = true
            transports = arrayOf("websocket")
        }

        socket = IO.socket(wssUrl, opts)
        connectStartTime = System.currentTimeMillis()
        socket?.connect()

        socket?.on(Socket.EVENT_CONNECT) {
            val elapsed = System.currentTimeMillis() - connectStartTime
            if (elapsed > 1500) {
                connectionStateListener?.onSignalStateChanged("weak")
            }
            /*if (disconnectCount > 0) {
                callStateListener?.onCallStateChanged(CallState.RECONNECTING)
            }*/
            disconnectCount = 0
            startPingLoop()
        }

        socket?.on(Socket.EVENT_DISCONNECT) {
            disconnectCount++
            if (disconnectCount > 1) {
                callStateListener?.onCallStateChanged(CallState.END)
                this.disconnect()
            }
        }

        socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = args.getOrNull(0)
            Log.e("SocketManager", "Socket connection error: $error")
            if (error.toString() == "io.socket.engineio.client.EngineIOException: websocket error") {
                socket?.connect()
            } else {
                callStateListener?.onCallStateChanged(CallState.END)

                this.disconnect()
            }
        }

        socket?.on("PONG") {
            handlePong()
        }

        socket?.on("MISSED_CALL") {
            callStateListener?.onCallStateChanged(CallState.MISSED)
        }

        socket?.on("RINGING_OK") {
            callStateListener?.onCallStateChanged(CallState.RINGING_OK)
        }

        socket?.on("BUSY") {
            callStateListener?.onCallStateChanged(CallState.BUSY)
        }

        // Event when the callee accepts the call
        socket?.on("INIT_OK") { _ ->
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
        socket?.on("ACCEPTED") { _ ->
            callStateListener?.onCallStateChanged(CallState.CONNECTING)
        }

        // Event when the callee accepts the call
        socket?.on("CONNECTED") { _ ->
            callStateListener?.onCallStateChanged(CallState.CONNECTED)
        }

        // Event when the call is ended from either side
        socket?.on("HANGUP") { _ ->
            callStateListener?.onCallStateChanged(CallState.END)
            webRTCManager?.close()
            socket?.disconnect()
        }

        socket?.on("NO_ANSWER") { _ ->
            callStateListener?.onCallStateChanged(CallState.TIMEOUT)
            webRTCManager?.close()
            socket?.disconnect()
        }

        // Received SDP offer from the remote peer
        socket?.on("SDP_OFFER") { args ->
            try {
                //callEventListener.onCallStateChanged(CallState.CONNECTING)
                val json = args[0] as JSONObject
                val sdpString = json.getString("sdp")
                if (webRTCManager == null) {
                    Log.e("SocketManager", "WebRTCManager is null! Cannot handle SDP_OFFER")
                } else {
                    // Close existing connection before re-initializing to prevent resource leak
                    try { webRTCManager?.close() } catch (_: Exception) {}
                    webRTCManager?.init()
                    webRTCManager?.initMic()
                }

                val sdp = SessionDescription(SessionDescription.Type.OFFER, sdpString)
                webRTCManager?.setRemoteDescription(sdp)
            } catch (e: Exception) {
                Log.e("SocketManager", "Error parsing SDP_OFFER: ${e.message}")
                callStateListener?.onCallStateChanged(CallState.END)
                disconnect()
            }
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
            try {
                //callEventListener.onCallStateChanged(CallState.CONNECTING)
                val json = args[0] as JSONObject
                val sdpString = json.getString("sdp")
                Log.i("SDK CALL SDP_ANSWER", sdpString)
                val sdp = SessionDescription(SessionDescription.Type.ANSWER, sdpString)
                webRTCManager?.setRemoteDescription(sdp)
            } catch (e: Exception) {
                Log.e("SocketManager", "Error parsing SDP_ANSWER: ${e.message}")
                callStateListener?.onCallStateChanged(CallState.END)
                disconnect()
            }
        }
    }

    private fun startPingLoop() {
        pingThread?.interrupt()
        pingThread = Thread {
            try {
                while (socket?.connected() == true && !Thread.currentThread().isInterrupted) {
                    sendPing()
                    Thread.sleep(5000)
                }
            } catch (e: InterruptedException) {
                // Thread interrupted, exit safely
            }
        }
        pingThread?.start()
    }

    private fun sendPing() {
        pingStartTime = System.currentTimeMillis()
        socket?.emit("PING")
    }

    private fun handlePong() {
        val latency = System.currentTimeMillis() - pingStartTime
        latencyAverage = (latencyAverage * 0.8) + (latency * 0.2)
        if (latency > 300) {
            connectionStateListener?.onSignalStateChanged("weak")
        } else {
            connectionStateListener?.onSignalStateChanged("")
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
        pingThread?.interrupt()
        pingThread = null
        socket?.disconnect()
    }
}