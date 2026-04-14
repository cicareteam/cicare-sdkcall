package cc.cicare.sdkcall.rtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import org.webrtc.SessionDescription
import kotlin.coroutines.resumeWithException

class WebRTCManager(
    private val context: Context,
    private val callback: WebRTCEventCallback
) {

    @Volatile
    private var peerConnection: PeerConnection? = null
    private var eglBase: EglBase? = null
    private var eglReleased = false
    @Volatile
    private var isClosed = false
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var audioTrack: AudioTrack? = null
    private var audioSource: AudioSource? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    fun init() {
        setAudioOutputToSpeaker(false)
        eglBase = EglBase.create()
        eglReleased = false
        isClosed = false

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        createPeerConnection()
    }

    private fun reinit() {
        setAudioOutputToSpeaker(false)
        eglBase = EglBase.create()
        eglReleased = false
        isClosed = false

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory.builder().createPeerConnectionFactory()

        createPeerConnection()
    }

    private fun createPeerConnection() {
        val factory = peerConnectionFactory ?: throw IllegalStateException("Factory not initialized")

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {

            override fun onIceCandidate(candidate: IceCandidate) {
                callback.onIceCandidateGenerated(candidate)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                transceiver?.receiver?.track()?.let { track ->
                    if (track is AudioTrack) {
                        Log.d("WebRTC", "Remote audio track received")
                        // jika butuh callback:
                        // callback.onRemoteAudioTrack(track)
                    }
                }
            }

            override fun onAddStream(stream: MediaStream) {}
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                p0?.let { callback.onIceConnectionStateChanged(it) }
            }
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                newState?.let { callback.onConnectionStateChanged(it) }
            }
        }) ?: throw IllegalStateException("Peerconnection failed to initialize")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun reconnectPeer() = suspendCancellableCoroutine { cont ->
        if (isClosed) {
            if (cont.isActive) cont.resume(Unit) {}
            return@suspendCancellableCoroutine
        }
        Log.i("WebRTC", "Reconnecting PeerConnection...")

        // safe close current pc (non-blocking) then create fresh
        safeClosePeerConnectionAndKeepFactory {
            try {
                createPeerConnection()
                // Re-attach audio track jika sudah dibuat
                audioTrack?.let { track ->
                    try {
                        peerConnection?.addTrack(track)
                        Log.i("WebRTC", "Audio track reattached to new peer")
                    } catch (e: Exception) {
                        Log.e("WebRTC", "Failed to reattach audio track: ${e.message}")
                    }
                }
                Log.i("WebRTC", "PeerConnection successfully reconnected")
                if (cont.isActive) cont.resume(Unit) {}
            } catch (e: Exception) {
                Log.e("WebRTC", "Failed to reconnect PeerConnection: ${e.message}")
                if (cont.isActive) cont.resumeWithException(e)
            }
        }
    }

    fun initMic() {
        val factory = peerConnectionFactory ?: throw IllegalStateException("Factory not initialized")
        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        }
        // simpan audioSource agar bisa di-dispose nantinya
        audioSource = factory.createAudioSource(audioConstraints)
        audioTrack = factory.createAudioTrack("101", audioSource)
        audioTrack?.setEnabled(true)
        peerConnection?.addTrack(audioTrack)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun createOffer(): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            cont.resume(sdp) {} // resume coroutine with sdp
                        }

                        override fun onSetFailure(error: String?) {
                            cont.resumeWithException(RuntimeException("SetLocalDescription failed: $error"))
                        }
                    }, sdp)
                } else {
                    cont.resumeWithException(RuntimeException("SDP is null"))
                }
            }

            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(RuntimeException("CreateOffer failed: $error"))
            }
        }, constraints)
    }

    fun setLocalDescription(sdp: SessionDescription?) {
        sdp?.let {
            peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    Log.d("WebRTC", "Local SDP set successfully")
                }

                override fun onSetFailure(error: String?) {
                    Log.e("WebRTC", "Failed to set local SDP: $error")
                }
            }, it)
        }
    }

    fun setDTMF(
        digits: String,
        durationMs: Int = 160,
        interToneGapMs: Int = 70
    ): Boolean {
        val pc = peerConnection ?: return false

        return try {
            val audioSender = pc.senders?.firstOrNull { sender ->
                sender.track() is AudioTrack
            }
            val dtmfSender = audioSender?.dtmf()
            if (dtmfSender == null || !dtmfSender.canInsertDtmf()) {
                Log.w("WebRTC", "DTMF not supported on this PeerConnection")
                return false
            }

            dtmfSender.insertDtmf(digits, durationMs, interToneGapMs)
            Log.d("WebRTC", "DTMF sent: $digits")
            true
        } catch (e: Exception) {
            Log.e("WebRTC", "Failed to send DTMF: ${e.message}")
            false
        }
    }

    fun setRemoteDescription(sdp: SessionDescription?) {
        sdp?.let {
            peerConnection?.setRemoteDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    Log.d("WebRTC", "Remote SDP set successfully")
                }

                override fun onSetFailure(error: String?) {
                    Log.e("WebRTC", "Failed to set remote SDP: $error")
                }
            }, it)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun createAnswer(): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    peerConnection?.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            cont.resume(sdp) {} // resume coroutine with sdp
                        }

                        override fun onSetFailure(error: String?) {
                            cont.resumeWithException(RuntimeException("SetLocalDescription failed: $error"))
                        }
                    }, sdp)
                } else {
                    cont.resumeWithException(RuntimeException("SDP is null"))
                }
            }

            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(RuntimeException("CreateAnswer failed: $error"))
            }
        }, constraints)
    }

    fun setAudioOutputToSpeaker(enabled: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        if (audioManager == null) {
            Log.e("AudioConfig", "AudioManager is null. Cannot configure audio output.")
            return
        }

        try {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enabled) {
                    val speakerDevice = audioManager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }

                    if (speakerDevice != null) {
                        val result = audioManager.setCommunicationDevice(speakerDevice)
                        if (result) {
                            Log.d("AudioConfig", "Successfully set communication device to speaker.")
                        } else {
                            Log.e("AudioConfig", "Failed to set communication device to speaker.")
                        }
                    } else {
                        Log.w("AudioConfig", "Built-in speaker device not found among communication devices. Falling back.")
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                    }
                } else {
                    audioManager.clearCommunicationDevice()
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = false
                    Log.d("AudioConfig", "Communication device cleared (speaker disabled).")
                }
            } else {
                audioManager.mode = AudioManager.MODE_IN_CALL
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = enabled
            }
            Log.d("AudioConfig", "Audio output for communication updated. Speaker enabled: $enabled")

        } catch (e: SecurityException) {
            Log.e("AudioConfig", "SecurityException during audio configuration: ${e.message}")
        } catch (e: Exception) {
            Log.e("AudioConfig", "Error configuring audio output: ${e.message}")
        }
    }

    fun isPeerConnectionActive(): Boolean {
        return peerConnection != null &&
                peerConnection?.connectionState() != PeerConnection.PeerConnectionState.CLOSED
    }

    fun setMicEnabled(enabled: Boolean) {
        Log.i("MUTE", enabled.toString())
        audioTrack?.setEnabled(!enabled)
    }

    /**
     * SAFE CLOSE: dispose peerConnection and related resources.
     * Ensures order:
     * 1) Stop/disable tracks & transceivers
     * 2) Close peerConnection
     * 3) Dispose peerConnection
     * 4) Dispose audioSource/audioTrack
     * 5) Dispose factory (last)
     *
     * All executed on Main thread and protected from double calls.
     */
    fun close() {
        // mark closed and enqueue cleanup on main thread
        if (isClosed) return
        isClosed = true

        // run cleanup on main looper to avoid JNI/thread race
        mainHandler.post {
            performCleanup(disposeFactory = true)
        }
    }

    /**
     * Close only the peerConnection but keep factory alive.
     * Callback after close finished (on main thread).
     */
    private fun safeClosePeerConnectionAndKeepFactory(onComplete: (() -> Unit)? = null) {
        // If already closed completely, nothing to do
        if (isClosed && peerConnection == null) {
            mainHandler.post { onComplete?.invoke() }
            return
        }

        // Ensure run on main thread
        mainHandler.post {
            performCleanup(disposeFactory = false)
            // give small delay for native threads to finish (helps Android 15)
            mainHandler.postDelayed({ onComplete?.invoke() }, 200)
        }
    }

    /**
     * Core cleanup method (must be called on main thread)
     */
    private fun performCleanup(disposeFactory: Boolean) {
        try {
            // 1) disable senders/tracks/transceivers
            try {
                peerConnection?.let { pc ->
                    try {
                        // disable senders' tracks
                        pc.senders.forEach { sender ->
                            try {
                                sender.track()?.let { t ->
                                    if (t is AudioTrack) {
                                        try { t.setEnabled(false) } catch (_: Exception) {}
                                    }
                                    try { t.dispose() } catch (_: Exception) {}
                                }
                            } catch (_: Exception) {}
                        }

                        // stop transceivers
                        pc.transceivers.forEach { tr ->
                            try { tr.stop() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
            } catch (_: Exception) {}

            // 2) close & dispose peerConnection
            try {
                peerConnection?.let { pc ->
                    try { pc.close() } catch (e: Exception) { Log.w("WebRTC", "pc.close() failed: ${e.message}") }
                    try { pc.dispose() } catch (e: Exception) { Log.w("WebRTC", "pc.dispose() failed: ${e.message}") }
                }
            } catch (e: Exception) { Log.w("WebRTC", "Error closing/disposing pc: ${e.message}") }

            // nullify pc ref
            peerConnection = null

            // 3) dispose audio track & source
            try {
                audioTrack?.let { at ->
                    try { at.setEnabled(false) } catch (_: Exception) {}
                    try { at.dispose() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            audioTrack = null

            try {
                audioSource?.let { src ->
                    try { src.dispose() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
            audioSource = null

            // 4) release egl
            try {
                eglBase?.let { eb ->
                    if (!eglReleased) {
                        try { eb.release() } catch (_: Exception) {}
                        eglReleased = true
                    }
                }
            } catch (_: Exception) {}
            eglBase = null

            // 5) dispose factory last if requested
            if (disposeFactory) {
                try {
                    peerConnectionFactory?.let { factory ->
                        try { factory.dispose() } catch (e: Exception) { Log.w("WebRTC", "factory.dispose() failed: ${e.message}") }
                    }
                } catch (_: Exception) {}
                peerConnectionFactory = null
            }

        } catch (e: Exception) {
            Log.e("WebRTC", "performCleanup error: ${e.message}")
        }
    }
}

interface WebRTCEventCallback {
    fun onLocalSdpCreated(sdp: SessionDescription)
    fun onIceCandidateGenerated(candidate: IceCandidate)
    fun onRemoteStreamReceived(stream: MediaStream)
    fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
    fun onIceConnectionStateChanged(state: PeerConnection.IceConnectionState)
}

abstract class SdpObserverAdapter: SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) { }
}
