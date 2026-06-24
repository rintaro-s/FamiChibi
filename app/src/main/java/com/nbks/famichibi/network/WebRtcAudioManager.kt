package com.nbks.famichibi.network

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack as AndroidAudioTrack
import android.media.MediaRecorder
import android.os.Process
import kotlinx.coroutines.*
import org.webrtc.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Basic WebRTC voice manager using Stream WebRTC Android.
 * Establishes peer connections via the REST signaling server.
 * Falls back to raw AudioRecord/AudioTrack if WebRTC native fails to initialize.
 */
class WebRtcAudioManager(private val context: Context) {
    private var factory: PeerConnectionFactory? = null
    private var localAudioSource: AudioSource? = null
    private var localAudioTrack: org.webrtc.AudioTrack? = null
    private var localStream: MediaStream? = null
    private val peerConnections = ConcurrentHashMap<String, PeerConnection>()
    private val remoteAudioTracks = ConcurrentHashMap<String, org.webrtc.AudioTrack>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onIceCandidate: ((targetUserId: String, candidate: IceCandidate) -> Unit)? = null
    var onOfferNeeded: ((targetUserId: String, sdp: SessionDescription) -> Unit)? = null
    var onAnswerNeeded: ((targetUserId: String, sdp: SessionDescription) -> Unit)? = null

    private var fallbackJob: Job? = null
    private var isFallback = false

    fun initialize() {
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(true)
                    .createInitializationOptions()
            )
            factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
            localAudioSource = factory?.createAudioSource(MediaConstraints())
            localAudioTrack = factory?.createAudioTrack("audio_track", localAudioSource)
            localStream = factory?.createLocalMediaStream("local_stream")
            localAudioTrack?.let { localStream?.addTrack(it) }
        } catch (e: Exception) {
            isFallback = true
        }
    }

    fun createPeerConnection(userId: String, isInitiator: Boolean, observer: PeerConnection.Observer): PeerConnection? {
        if (isFallback) return null
        val pc = factory?.createPeerConnection(
            listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()),
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
                override fun onIceCandidate(candidate: IceCandidate?) {
                    candidate?.let { onIceCandidate?.invoke(userId, it) }
                }
                override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
                override fun onAddStream(stream: MediaStream?) {
                    stream?.audioTracks?.firstOrNull()?.let { track ->
                        remoteAudioTracks[userId] = track
                        track.setEnabled(true)
                    }
                }
                override fun onRemoveStream(stream: MediaStream?) {}
                override fun onDataChannel(dc: DataChannel?) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
                override fun onRemoveTrack(receiver: RtpReceiver?) {}
            }
        ) ?: return null
        peerConnections[userId] = pc
        localStream?.let { pc.addStream(it) }
        if (isInitiator) {
            val constraints = MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")) }
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    sdp?.let {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onSetSuccess() { onOfferNeeded?.invoke(userId, it) }
                            override fun onCreateFailure(p0: String?) {}
                            override fun onSetFailure(p0: String?) {}
                        }, it)
                    }
                }
                override fun onSetSuccess() {}
                override fun onCreateFailure(error: String?) {}
                override fun onSetFailure(error: String?) {}
            }, constraints)
        }
        return pc
    }

    fun handleOffer(userId: String, sdp: SessionDescription) {
        val pc = peerConnections[userId] ?: createPeerConnection(userId, false, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidate(candidate: IceCandidate?) { candidate?.let { onIceCandidate?.invoke(userId, it) } }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dc: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
            override fun onRemoveTrack(receiver: RtpReceiver?) {}
        }) ?: return
        pc.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                val constraints = MediaConstraints().apply { mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true")) }
                pc.createAnswer(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription?) {
                        sdp?.let {
                            pc.setLocalDescription(object : SdpObserver {
                                override fun onCreateSuccess(p0: SessionDescription?) {}
                                override fun onSetSuccess() { onAnswerNeeded?.invoke(userId, it) }
                                override fun onCreateFailure(p0: String?) {}
                                override fun onSetFailure(p0: String?) {}
                            }, it)
                        }
                    }
                    override fun onSetSuccess() {}
                    override fun onCreateFailure(error: String?) {}
                    override fun onSetFailure(error: String?) {}
                }, constraints)
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, sdp)
    }

    fun handleAnswer(userId: String, sdp: SessionDescription) {
        peerConnections[userId]?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, sdp)
    }

    fun handleIceCandidate(userId: String, candidate: IceCandidate) {
        peerConnections[userId]?.addIceCandidate(candidate)
    }

    fun setMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun startFallbackAudio() {
        if (!isFallback) return
        fallbackJob?.cancel()
        fallbackJob = scope.launch {
            val sampleRate = 16000
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            val audioTrack = AndroidAudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AndroidAudioTrack.MODE_STREAM)
                .build()
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            audioRecord.startRecording()
            audioTrack.play()
            val buffer = ByteArray(bufferSize)
            while (isActive) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) audioTrack.write(buffer, 0, read)
            }
            audioRecord.stop(); audioRecord.release(); audioTrack.stop(); audioTrack.release()
        }
    }

    fun stop() {
        fallbackJob?.cancel()
        peerConnections.values.forEach { it.close() }
        peerConnections.clear()
        remoteAudioTracks.clear()
        localStream?.dispose()
        localAudioTrack?.dispose()
        localAudioSource?.dispose()
        factory?.dispose()
        scope.cancel()
    }
}
