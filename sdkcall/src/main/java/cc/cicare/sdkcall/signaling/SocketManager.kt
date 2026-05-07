package cc.cicare.sdkcall.signaling

import android.util.Log
import cc.cicare.sdkcall.event.CallStateListener
import cc.cicare.sdkcall.event.CallState
import cc.cicare.sdkcall.event.ConnectionStateListener
import cc.cicare.sdkcall.rtc.WebRTCManager
import io.socket.client.IO
import io.socket.client.Socket
import kotlinx.coroutines.*
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

data class PendingEvent(
    val event: String,
    val data: JSONObject
)

class SocketManager {
    private var socket: Socket? = null
    private var callStateListener: CallStateListener? = null
    private var connectionStateListener: ConnectionStateListener? = null

    private var webRTCManager: WebRTCManager? = null
    private var disconnectCount: Int = 0

    private var isWebrtcClosed = false

    private var connectStartTime: Long = 0
    private var pingStartTime: Long = 0
    private var latencyAverage: Double = 0.0
    private var isIntentionalDisconnect: Boolean = false

    private val socketScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var pingJob: Job? = null

    private var reconnecting: Boolean = false
    private var connected: Boolean = false
    private var sendReconnect: Boolean = false

    private val pendingEvents = mutableListOf<PendingEvent>()

    private var isCallCancelled = false
    private var pendingDisconnect = false

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
        Log.i("SDK Call", "Connecting to $wssUrl")
        val opts = IO.Options().apply {
            query = "token=$token"
            reconnection = false
            reconnectionAttempts = 5
            reconnectionDelay = 3000
            reconnectionDelayMax = 5000
            timeout = 5000
            forceNew = true
            transports = arrayOf("websocket")
        }

        socket = IO.socket(wssUrl, opts)
        connectStartTime = System.currentTimeMillis()
        socket?.connect()

        socket?.on(Socket.EVENT_CONNECT) {
            Log.i("SDK CALL", "Signal connected")
            reconnecting = false
            connected = true

            pendingEvents.forEach {
                if (socket != null) {
                    if (it.event == "INIT_CALL") isCallCancelled = false
                    socket?.emit(it.event, it.data)
                    Log.i("SDK CALL", "EXEC PENDING EVENT: ${it.event} ${it.data}")
                }
            }

            pendingEvents.clear()

            if (pendingDisconnect) {
                pendingDisconnect = false
                destroy()
                return@on
            }


            val elapsed = System.currentTimeMillis() - connectStartTime
            if (elapsed > 1500) {
                connectionStateListener?.onSignalStateChanged("weak")
            } else {
                connectionStateListener?.onSignalStateChanged("")
            }
            if (disconnectCount > 0) {
                if (!sendReconnect) {
                    sendReconnect = true
                    socket?.emit("RECONNECT")
                    Log.i("SDK CALL", "SEND RECONNECTING")
                    connectionStateListener?.onSignalStateChanged("reconnecting")
                }
            }
            disconnectCount = 0
            startPingLoop()
        }

        socket?.on(Socket.EVENT_DISCONNECT) {
            if (isIntentionalDisconnect) {
                callStateListener?.onCallStateChanged(CallState.END)
                sendReconnect = false
                return@on
            }

            Log.e("SocketManager", "Connection dropped, count=$disconnectCount")
            connectionStateListener?.onSignalStateChanged("reconnecting")

            if (disconnectCount > 5) {
                connectionStateListener?.onSignalStateChanged("lost")
                callStateListener?.onCallStateChanged(CallState.END)
                destroy()
            }
            // Jika count <= 3, Socket.IO akan otomatis retry (reconnectionAttempts=3)
        }

        socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            if (isIntentionalDisconnect) return@on

            val error = args.getOrNull(0)
            Log.e("SocketManager", "Connect error: $error")

            val message = when (error) {
                is Exception -> error.message
                else -> error?.toString()
            }

            Log.e("SocketManager", "message $message")

            if (message?.contains("Invalid token") == true) {
                Log.e("SocketManager", "Invalid token")
                callStateListener?.onCallStateChanged(CallState.END)
                disconnect()
                pendingEvents.clear()
                return@on
            } else if (message == "timeout") {

            }

            // prevent spam
            if (reconnecting) return@on

            disconnectCount++

            if (disconnectCount > 5) {
                connectionStateListener?.onSignalStateChanged("lost")
                callStateListener?.onCallStateChanged(CallState.END)
                destroy()
                return@on
            }

            reconnecting = true

            // gunakan coroutine untuk delay
            CoroutineScope(Dispatchers.IO).launch {
                Log.d("SocketManager", "Retrying in 3s...")

                kotlinx.coroutines.delay(3000)

                reconnecting = false

                // ❗ jangan pakai connect kalau auto-reconnect aktif
                if (socket?.connected() != true) {
                    Log.i("SocketManager", "Try Connecting")
                    socket?.connect()
                }
            }
        }

        /*socket?.on(Socket.EVENT_DISCONNECT) {
            Log.e("SocketManager", "Connection dropped, count=$disconnectCount")
            disconnectCount++
            connected = false
            if (disconnectCount > 1) {
                callStateListener?.onCallStateChanged(CallState.END)
                this.disconnect()
            }
        }

        socket?.on(Socket.EVENT_CONNECT_ERROR) { args ->
            val error = args.getOrNull(0)
            connected = false
            Log.e("SocketManager", "Socket connection error: $error")
            if (error.toString() == "io.socket.engineio.client.EngineIOException: websocket error") {
                socket?.connect()
            } else {
                callStateListener?.onCallStateChanged(CallState.END)

                this.disconnect()
            }
        }*/

        socket?.on("PONG") {
            handlePong()
        }

        socket?.on("MISSED_CALL") {
            Log.i("SDK CALL", "MISSED CALL")
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
            Log.i("SDK CALL", "HANGUP")
            callStateListener?.onCallStateChanged(CallState.END)
            //webRTCManager?.close()
            disconnect()
        }

        socket?.on("NO_ANSWER") { _ ->
            callStateListener?.onCallStateChanged(CallState.TIMEOUT)
            //webRTCManager?.close()
            disconnect()
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

        socket?.on("RECONNECTING") {
            socketScope.launch(Dispatchers.Main) {
                sendReconnect = false

                if (isWebrtcClosed) {
                    performReconnect()
                }
            }
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

    private fun emitOrQueue(event: String, data: JSONObject) {
        if (event == "INIT_CALL" && isCallCancelled) {
            Log.i("SDK CALL", "SKIP INIT_CALL because cancelled")
            return
        } else if (event == "CANCEL") {
            isCallCancelled = true
            removePendingEvent("INIT_CALL")
        }
        if (socket?.connected() == true) {
            socket?.emit(event, data)
        } else {
            Log.i("SDK CALL", "QUEUE EVENT: $event")
            pendingEvents.add(
                PendingEvent(event, data)
            )
        }
    }

    private fun removePendingEvent(event: String) {
        pendingEvents.removeAll {
            it.event == event
        }
    }

    private suspend fun performReconnect() {
        Log.i("SDK CALL", "RECONNECTING WEBRTC")

        try {
            webRTCManager?.reconnectPeer()
        } catch (e: Exception) {
            Log.e("SocketManager", "Error reconnecting: ${e.message}")
            disconnect()
        }

        // Tidak perlu launch lagi — sudah di dalam coroutine (Dispatchers.Main)
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
    }

    private fun startPingLoop() {
        pingJob?.cancel()
        pingJob = socketScope.launch {
            while (isActive && socket?.connected() == true) {
                sendPing()
                delay(5000)
            }
        }
    }

    private fun sendPing() {
        pingStartTime = System.currentTimeMillis()
        socket?.emit("PING")
    }

    private fun handlePong() {
        val latency = System.currentTimeMillis() - pingStartTime
        latencyAverage = (latencyAverage * 0.8) + (latency * 0.2)
        connectionStateListener?.onSignalStateChanged(if (latency > 300) "weak" else "")
    }

    fun isConnected(): Boolean = socket?.connected() == true

    /**
     * Sends a signaling event through the WebSocket connection.
     *
     * @param event The event name (e.g. SDP_OFFER, SDP_ANSWER, etc.)
     * @param data The event payload in JSON format.
     */
    fun send(event: String, data: JSONObject) {
        emitOrQueue(event, data)
    }

    /**
     * Disconnects the WebSocket connection.
     */
    fun disconnect() {
        if (pendingEvents.size > 0) {
            pendingDisconnect = true
            return
        }
        isIntentionalDisconnect = true
        pingJob?.cancel()
        pingJob = null
        webRTCManager?.close()
        webRTCManager = null
        Log.i("SDK CALL", "DISCONNECTING")
        socket?.disconnect()
    }

    fun webrtcClosed() {
        isWebrtcClosed = true
        if (connected) {
            socketScope.launch(Dispatchers.Main) {
                performReconnect()
            }
        }
    }

    // Panggil ini saat Activity/Fragment destroy untuk cleanup total
    fun destroy() {
        disconnect()
        socketScope.cancel()
    }
}