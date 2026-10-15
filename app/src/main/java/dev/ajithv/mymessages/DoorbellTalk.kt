package dev.ajithv.mymessages

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Minimal full-duplex "talk" client for the doorbell, over go2rtc's WebSocket WebRTC signalling.
 *
 * ONE audio-only sendrecv peer:
 *   phone mic  -> go2rtc -> camera speaker   (go2rtc RTSP/ONVIF backchannel)
 *   camera mic -> go2rtc -> phone (played here, so WebRTC's own AEC has the camera audio as its
 *                 echo-cancel reference = clean full-duplex, no howl).
 * Video keeps coming from libVLC in [CameraActivity]; this peer carries NO video.
 *
 * Signalling (go2rtc `/api/ws?src=<stream>`): we send {"type":"webrtc/offer","value":<sdp>};
 * server replies {"type":"webrtc/answer","value":<sdp>} and trickles
 * {"type":"webrtc/candidate","value":<line>} (both directions).
 */
class DoorbellTalk(
    ctx: Context,
    private val wsUrl: String,
    private val onState: (State) -> Unit
) {
    enum class State { CONNECTING, CONNECTED, FAILED, CLOSED }

    private val app: Context = ctx.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient()

    private var factory: PeerConnectionFactory? = null
    private var adm: JavaAudioDeviceModule? = null
    private var pc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var remoteAudio: AudioTrack? = null   // the camera's audio (what you hear while talking)
    private var ws: WebSocket? = null

    @Volatile private var closed = false
    @Volatile private var listenMuted = false     // mute the camera->phone audio without ending talk
    private var prevAudioMode = AudioManager.MODE_NORMAL
    private var prevSpeaker = false

    fun start() {
        onState(State.CONNECTING)
        try {
            initFactory()
        } catch (e: Exception) { fail("factory: ${e.message}"); return }

        // Route for a hands-free call: communication mode + speakerphone so you hear the door.
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            prevAudioMode = am.mode
            prevSpeaker = am.isSpeakerphoneOn
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = true
        } catch (_: Exception) {}

        val rtc = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
            )
        ).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        pc = factory!!.createPeerConnection(rtc, pcObserver)
        if (pc == null) { fail("createPeerConnection null"); return }

        // AEC stays on (echo). AGC OFF — we apply our own tunable makeup gain in applyGain() instead,
        // otherwise AGC would normalise the boost away. NS + high-pass are user-tunable (TalkConfig).
        val micCons = MediaConstraints().apply {
            optional.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            optional.add(MediaConstraints.KeyValuePair("googAutoGainControl", "false"))
            optional.add(MediaConstraints.KeyValuePair("googNoiseSuppression", if (TalkConfig.noiseSuppression) "true" else "false"))
            optional.add(MediaConstraints.KeyValuePair("googHighpassFilter", if (TalkConfig.highpass) "true" else "false"))
        }
        audioSource = factory!!.createAudioSource(micCons)
        audioTrack = factory!!.createAudioTrack("mic0", audioSource).apply { setEnabled(true) }
        pc!!.addTrack(audioTrack, listOf("talk0"))   // sendrecv audio

        ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), wsListener)
    }

    /** Mute/unmute ONLY the camera->phone audio (your mic keeps streaming to the door). */
    fun setListenMuted(muted: Boolean) {
        listenMuted = muted
        try { remoteAudio?.setEnabled(!muted) } catch (_: Exception) {}
    }

    fun stop() {
        if (closed) return
        closed = true
        try { ws?.close(1000, "bye") } catch (_: Exception) {}
        try { ws?.cancel() } catch (_: Exception) {}
        ws = null
        try { pc?.dispose() } catch (_: Exception) {}
        pc = null
        try { audioTrack?.dispose() } catch (_: Exception) {}
        try { audioSource?.dispose() } catch (_: Exception) {}
        audioTrack = null; audioSource = null
        try { factory?.dispose() } catch (_: Exception) {}
        factory = null
        try { adm?.release() } catch (_: Exception) {}
        adm = null
        try {
            val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = prevSpeaker
            am.mode = prevAudioMode
        } catch (_: Exception) {}
        main.post { onState(State.CLOSED) }
    }

    // ---- internals -------------------------------------------------------------------------

    /** Apply the user-tunable makeup gain to the captured mic PCM, in place, with a soft-knee limiter
     *  so a louder level never hard-clips. PCM_16BIT little-endian. Runs per 10 ms mic buffer. */
    private fun applyGain(buf: ByteBuffer, audioFormat: Int, bytesRead: Int) {
        if (audioFormat != 2) return            // AudioFormat.ENCODING_PCM_16BIT
        val g = TalkConfig.gain
        if (g == 1.0f) return
        val prevOrder = buf.order()
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val start = buf.position()
        val end = start + bytesRead
        var i = start
        while (i + 1 < end) {
            val s = buf.getShort(i).toInt()
            var v = s * g
            val a = abs(v)
            if (a > KNEE) {                     // soft-knee: asymptotically approaches full-scale
                val sign = if (v < 0) -1f else 1f
                v = sign * (KNEE + (a - KNEE) / (1f + (a - KNEE) / (CEIL - KNEE)))
            }
            var iv = v.toInt()
            if (iv > 32767) iv = 32767 else if (iv < -32768) iv = -32768
            buf.putShort(i, iv.toShort())
            i += 2
        }
        buf.order(prevOrder)
    }

    private fun initFactory() {
        synchronized(DoorbellTalk::class.java) {
            if (!pcfInit) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(app)
                        .createInitializationOptions()
                )
                pcfInit = true
            }
        }
        // Use WebRTC's OWN software AEC3 + NS instead of the phone's hardware effects. On Qualcomm/
        // OnePlus the HW AEC/NS is often aggressive and muffles voice; the software APM is cleaner and
        // better tuned, and it still cancels echo using the played-back door audio as its reference.
        // The mic-buffer callback applies a user-tunable makeup gain (HA dashboard -> TalkConfig).
        adm = JavaAudioDeviceModule.builder(app)
            .setUseHardwareAcousticEchoCanceler(false)
            .setUseHardwareNoiseSuppressor(false)
            .setAudioBufferCallback { buffer, audioFormat, _, _, bytesRead, captureTimeNs ->
                applyGain(buffer, audioFormat, bytesRead); captureTimeNs
            }
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .createPeerConnectionFactory()
    }

    private fun createOffer() {
        val cons = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        }
        pc?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription) {
                pc?.setLocalDescription(setObserver("setLocal"), desc)
                try {
                    ws?.send(JSONObject().put("type", "webrtc/offer").put("value", desc.description).toString())
                } catch (e: Exception) { fail("send offer: ${e.message}") }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(p0: String?) { fail("createOffer: $p0") }
            override fun onSetFailure(p0: String?) {}
        }, cons)
    }

    private fun setObserver(tag: String) = object : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() { Log.i(TAG, "$tag ok") }
        override fun onCreateFailure(p0: String?) { Log.w(TAG, "$tag createFail $p0") }
        override fun onSetFailure(p0: String?) { Log.w(TAG, "$tag setFail $p0") }
    }

    private val wsListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) { createOffer() }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                val o = JSONObject(text)
                when (o.optString("type")) {
                    "webrtc/answer" -> pc?.setRemoteDescription(
                        setObserver("setRemote"),
                        SessionDescription(SessionDescription.Type.ANSWER, o.optString("value"))
                    )
                    "webrtc/candidate" -> {
                        val line = o.optString("value")
                        if (line.isNotEmpty()) pc?.addIceCandidate(IceCandidate("0", 0, line))
                    }
                    "error" -> fail("go2rtc: ${o.optString("value")}")
                }
            } catch (e: Exception) { Log.w(TAG, "ws msg: ${e.message}") }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            fail("ws: ${t.message}")
        }
    }

    private val pcObserver = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            try {
                ws?.send(JSONObject().put("type", "webrtc/candidate").put("value", c.sdp).toString())
            } catch (_: Exception) {}
        }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            Log.i(TAG, "ice=$s")
            when (s) {
                PeerConnection.IceConnectionState.CONNECTED,
                PeerConnection.IceConnectionState.COMPLETED ->
                    main.post { if (!closed) onState(State.CONNECTED) }
                PeerConnection.IceConnectionState.FAILED -> fail("ice failed")
                else -> {}
            }
        }
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
            val t = receiver?.track() as? AudioTrack ?: return     // camera audio (listen direction)
            remoteAudio = t
            t.setEnabled(!listenMuted)                             // honour the current mute state
        }
        override fun onConnectionChange(s: PeerConnection.PeerConnectionState?) {}
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
        override fun onIceConnectionReceivingChange(p0: Boolean) {}
        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
        override fun onAddStream(p0: MediaStream?) {}
        override fun onRemoveStream(p0: MediaStream?) {}
        override fun onDataChannel(p0: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
        override fun onTrack(transceiver: RtpTransceiver?) {}
    }

    private fun fail(msg: String) {
        Log.w(TAG, "fail: $msg")
        main.post { if (!closed) onState(State.FAILED) }
    }

    companion object {
        const val TAG = "DoorbellTalk"
        @Volatile private var pcfInit = false
        private const val KNEE = 24000f   // soft-limiter knee (|sample| in int16)
        private const val CEIL = 32767f   // full scale (never exceeded)
    }
}
