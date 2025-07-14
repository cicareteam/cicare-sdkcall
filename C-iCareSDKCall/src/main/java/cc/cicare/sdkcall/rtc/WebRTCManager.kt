package cc.cicare.sdkcall.rtc

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.*
import kotlin.coroutines.resumeWithException

class WebRTCManager(
    private val context: Context,
    private val callback: WebRTCEventCallback
) {

    private lateinit var peerConnection: PeerConnection
    private lateinit var eglBase: EglBase
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var audioTrack: AudioTrack

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
    )

    fun init() {
        setAudioOutputToSpeaker(false)
        eglBase = EglBase.create()
        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .createPeerConnectionFactory()

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {

            override fun onIceCandidate(candidate: IceCandidate) {
                callback.onIceCandidateGenerated(candidate)
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                transceiver?.receiver?.track()?.let { track ->
                    if (track is AudioTrack) {
                        Log.d("WebRTC", "Remote audio track received")

                        // WebRTC Android akan langsung memutar suaranya
                    }
                }
            }

            override fun onAddStream(stream: MediaStream) {}

            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
            override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
            override fun onIceConnectionReceivingChange(p0: Boolean) {}
            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onRemoveStream(p0: MediaStream?) {}
            override fun onDataChannel(p0: DataChannel?) {}
            override fun onRenegotiationNeeded() {}

        }) ?: throw IllegalStateException("Peerconnection failed to initialize")


    }

    fun initMic() {
        val audioSource = peerConnectionFactory.createAudioSource(MediaConstraints())
        audioTrack = peerConnectionFactory.createAudioTrack("101", audioSource)
        audioTrack.setEnabled(true)
        peerConnection.addTrack(audioTrack)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun createOffer(): SessionDescription = suspendCancellableCoroutine { cont ->
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }

        peerConnection.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    peerConnection.setLocalDescription(object : SdpObserverAdapter() {
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

    /*fun createOffer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        peerConnection.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection.setLocalDescription(object : SdpObserverAdapter() {
                        override fun onSetSuccess() {
                            callback.onLocalSdpCreated(it)
                        }
                    }, it)
                }
            }
        }, constraints)
    }*/

    fun setLocalDescription(sdp: SessionDescription?) {
        sdp?.let {
            peerConnection.setLocalDescription(object : SdpObserverAdapter() {
                override fun onSetSuccess() {
                    Log.d("WebRTC", "Remote SDP set successfully")
                }

                override fun onSetFailure(error: String?) {
                    Log.e("WebRTC", "Failed to set remote SDP: $error")
                }
            }, it)
        }
    }

    fun setRemoteDescription(sdp: SessionDescription?) {
        sdp?.let {
            peerConnection.setRemoteDescription(object : SdpObserverAdapter() {
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

        peerConnection.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp != null) {
                    peerConnection.setLocalDescription(object : SdpObserverAdapter() {
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
            // Always set the audio mode to MODE_IN_COMMUNICATION when managing communication audio.
            // This should generally be done when a communication session starts.
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

            if (enabled) {
                // --- Enable Speakerphone (Modern Approach for Android S and above) ---
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
                    // Fallback to deprecated method if speaker not found for some reason (rare)
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                    Log.d("AudioConfig", "Using deprecated isSpeakerphoneOn for speaker enable as fallback.")
                }
            } else {
                // --- Disable Speakerphone (Modern Approach for Android S and above) ---
                // Clear the communication device to revert to the default (e.g., earpiece or connected headset)
                audioManager.clearCommunicationDevice()
                Log.d("AudioConfig", "Communication device cleared (speaker disabled).")
            }
            Log.d("AudioConfig", "Audio output for communication updated. Speaker enabled: $enabled")

        } catch (e: SecurityException) {
            Log.e("AudioConfig", "SecurityException during audio configuration: ${e.message}")
        } catch (e: Exception) {
            Log.e("AudioConfig", "Error configuring audio output: ${e.message}")
        }
    }

    fun isPeerConnectionActive(): Boolean {
        return ::peerConnection.isInitialized &&
                peerConnection.connectionState() != PeerConnection.PeerConnectionState.CLOSED
    }

    fun setMicEnabled(enabled: Boolean) {
        audioTrack.setEnabled(enabled)
    }

    fun close() {
        try {
            peerConnection.dispose()
            peerConnectionFactory.dispose()
            eglBase.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

interface WebRTCEventCallback {
    fun onLocalSdpCreated(sdp: SessionDescription)
    fun onIceCandidateGenerated(candidate: IceCandidate)
    fun onRemoteStreamReceived(stream: MediaStream)
    fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
}

abstract class SdpObserverAdapter: SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) { }
}