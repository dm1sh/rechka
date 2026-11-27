package ru.dm1sh.rechka.transcriber

import android.content.Context
import android.util.Log
import ru.dm1sh.rechka.model.GigaAmModel
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Full-context, offline GigaAM v4 E2E RNN-T via sherpa-onnx's NeMo transducer.
 * VAD segments are decoded independently; this is not the v4 streaming model.
 * The singleton tries NNAPI first and falls back to CPU if initialization fails.
 */
class OfflineTranscriber private constructor(
    private val recognizer: OfflineRecognizer,
    /** Requested execution provider; NNAPI may delegate unsupported operations to CPU. */
    val activeProvider: String
) : Transcriber {

    // Guards every call into the native sherpa-onnx session — both the hot
    // path (createStream/decode/getResult) and release(). Without this, a
    // release() on the IME-hidden path could race with an in-flight
    // transcription tail and SEGV inside libsherpa-onnx-jni.so when
    // createStream() is called against a freed ONNX session. Locking is
    // cheap here — transcription holds the monitor for ~200–500 ms, and
    // release blocks the caller (Main thread on idle UI) only briefly.
    private val nativeLock = Any()

    // Set to true once release() has destroyed the underlying ONNX session.
    // Anyone who already holds (or later acquires) a stale reference to this
    // OfflineTranscriber instance must check this flag inside the nativeLock
    // before touching `recognizer` — otherwise we get a use-after-free SEGV.
    @Volatile private var isReleased = false

    companion object {
        private const val TAG = "OfflineTranscriber"

        @Volatile private var instance: OfflineTranscriber? = null

        suspend fun getInstance(context: Context): OfflineTranscriber = withContext(Dispatchers.IO) {
            instance?.let { return@withContext it }
            synchronized(this@Companion) {
                instance?.let { return@synchronized it }
                val (recognizer, provider) = buildRecognizer(context)
                OfflineTranscriber(recognizer, provider).also { instance = it }
            }
        }

        fun release() {
            synchronized(this) {
                val toRelease = instance ?: return
                instance = null
                // Take the per-instance native lock so any in-flight
                // transcription finishes before we destroy the ONNX session.
                // Mark as released *inside* the lock so any caller who got
                // here later (via a cached reference) sees the flag and bails
                // instead of touching the freed recognizer.
                synchronized(toRelease.nativeLock) {
                    toRelease.isReleased = true
                    toRelease.recognizer.release()
                }
            }
        }

        private fun buildRecognizer(context: Context): Pair<OfflineRecognizer, String> {
            check(GigaAmModel.isInstalled(context)) { "GigaAM v4 model is not installed or is invalid" }
            val dir = GigaAmModel.modelDir(context)
            val missing = GigaAmModel.FILES.map { File(dir, it.name) }.filter { !it.exists() }
            if (missing.isNotEmpty()) {
                throw IllegalStateException("Model files missing: ${missing.map { it.name }}")
            }

            for (provider in listOf("nnapi", "cpu")) {
                try {
                    val config = buildConfig(dir, provider)
                    val recognizer = OfflineRecognizer(null, config)
                    Log.i(TAG, "GigaAM v4 provider=$provider — OK")
                    return Pair(recognizer, provider)
                } catch (e: Exception) {
                    Log.w(TAG, "provider '$provider' FAILED: ${e.message}")
                    if (provider == "cpu") throw e
                }
            }
            throw IllegalStateException("All providers failed")
        }

        private fun buildConfig(dir: File, provider: String): OfflineRecognizerConfig {
            val modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = File(dir, GigaAmModel.ENCODER).absolutePath,
                    decoder = File(dir, GigaAmModel.DECODER).absolutePath,
                    joiner = File(dir, GigaAmModel.JOINER).absolutePath
                ),
                tokens = File(dir, GigaAmModel.TOKENS).absolutePath,
                numThreads = 4,
                provider = provider,
                modelType = "nemo_transducer"
            )
            return OfflineRecognizerConfig(
                // The v4 encoder consumes 64 mel bins; sherpa's GigaAM branch
                // also detects this from the model metadata.
                featConfig = FeatureConfig(sampleRate = 16000, featureDim = 64),
                modelConfig = modelConfig
            )
        }
    }

    private val audioBuffer = ByteArrayOutputStream()

    override suspend fun connect() {
        Log.i(TAG, "Offline transcriber ready (GigaAM v4)")
    }

    override suspend fun startAudio(rate: Int, width: Int, channels: Int) {
        audioBuffer.reset()
    }

    override suspend fun sendAudioChunk(pcmData: ByteArray, rate: Int, width: Int, channels: Int) {
        audioBuffer.write(pcmData)
    }

    override suspend fun stopAudioAndGetTranscript(): String = withContext(Dispatchers.Default) {
        val pcm = audioBuffer.toByteArray()
        audioBuffer.reset()
        if (pcm.isEmpty()) return@withContext ""

        val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val samples = FloatArray(buf.remaining()) { buf.get().toFloat() / 32768f }

        // Hold the native lock for the entire critical section. release()
        // can run at any time (IME hidden, onTrimMemory) and would crash
        // the JNI layer if it freed the ONNX session mid-call. Inside the
        // lock we also check isReleased — VadRecorder may have captured
        // this instance before release() ran, in which case we must NOT
        // dereference the freed recognizer.
        val text = synchronized(nativeLock) {
            if (isReleased) {
                Log.w(TAG, "stopAudioAndGetTranscript called on released instance — dropping ${pcm.size}B")
                return@synchronized ""
            }
            val stream = recognizer.createStream()
            try {
                stream.acceptWaveform(samples, sampleRate = 16000)
                recognizer.decode(stream)
                recognizer.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }

        // Privacy: log only the length, never the recognised text content.
        // logcat is visible to anyone who runs `adb logcat` while the app is
        // active and persists in the system ring buffer; even though it's
        // volatile, leaking the user's dictated text there violates the
        // "nothing leaves the device" promise that's the project's main USP.
        Log.i(TAG, "Offline transcript: ${text.length} chars")
        text
    }

    override fun disconnect() {
        audioBuffer.reset()
    }
}
