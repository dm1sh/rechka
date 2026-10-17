package ru.dm1sh.rechka.transcriber

import android.content.Context
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.TenVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * Silero-VAD segmenter shared by offline-file processing. Input is 16 kHz,
 * mono, 16-bit PCM represented as samples. It deliberately uses the same
 * 512-sample windows and speech limits as the microphone recorder.
 */
class SpeechSegmenter(context: Context) : AutoCloseable {
    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val WINDOW_SIZE = 512
        private const val MIN_TAIL_SAMPLES = SAMPLE_RATE / 4
        private const val MAX_TAIL_SAMPLES = SAMPLE_RATE * 60
        // The VAD notices speech a little late, so its segment start can
        // clip the first phonemes. The recognizer also gets the few
        // windows just before the detected start (5 * 32 ms = 160 ms) —
        // but never audio already sent with the previous segment.
        private const val PRE_ROLL_SAMPLES = WINDOW_SIZE * 5
        private const val MODEL_ASSET = "silero_vad.onnx"
    }

    private val vad = Vad(null, VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(
            model = ensureModel(context),
            threshold = 0.5f,
            minSilenceDuration = ru.dm1sh.rechka.util.Prefs.getPauseLengthSeconds(context),
            minSpeechDuration = 0.25f,
            windowSize = WINDOW_SIZE,
            maxSpeechDuration = 30.0f,
        ),
        tenVadModelConfig = TenVadModelConfig(),
        sampleRate = SAMPLE_RATE,
        numThreads = 1,
        provider = "cpu",
        debug = false,
    ))
    private val pending = ShortArray(WINDOW_SIZE)
    private var pendingCount = 0
    private val tail = ShortArray(MAX_TAIL_SAMPLES)
    private var tailCount = 0
    // Absolute sample index just past the last emitted segment, and the
    // total number of samples ever appended to the tail — together with
    // tailCount they locate any recent position inside the tail buffer.
    private var lastProcessedEnd = 0
    private var appendedSamples = 0

    fun acceptPcm(input: ShortArray): List<ShortArray> {
        val result = ArrayList<ShortArray>()
        var offset = 0
        while (offset < input.size) {
            val count = minOf(WINDOW_SIZE - pendingCount, input.size - offset)
            input.copyInto(pending, pendingCount, offset, offset + count)
            pendingCount += count
            offset += count
            if (pendingCount == WINDOW_SIZE) {
                val window = pending.copyOf()
                appendTail(window)
                val samples = FloatArray(WINDOW_SIZE) { window[it] / 32768f }
                vad.acceptWaveform(samples)
                drain(result)
                pendingCount = 0
            }
        }
        return result
    }

    fun flush(): List<ShortArray> {
        val result = ArrayList<ShortArray>()
        if (pendingCount > 0) {
            val window = ShortArray(WINDOW_SIZE)
            pending.copyInto(window, 0, 0, pendingCount)
            appendTail(pending.copyOfRange(0, pendingCount))
            vad.acceptWaveform(FloatArray(WINDOW_SIZE) { window[it] / 32768f })
            pendingCount = 0
        }
        vad.flush()
        drain(result)
        if (result.isEmpty()) {
            // Raw fallback for a final phrase the VAD did not surface:
            // only the part not already emitted as a segment.
            val tailStart = appendedSamples - tailCount
            val from = maxOf(lastProcessedEnd, tailStart)
            if (appendedSamples - from >= MIN_TAIL_SAMPLES) {
                result += tail.copyOfRange(from - tailStart, appendedSamples - tailStart)
            }
        }
        tailCount = 0
        return result
    }

    private fun drain(result: MutableList<ShortArray>) {
        while (!vad.empty()) {
            val segment = vad.front()
            vad.pop()
            val pcm = ShortArray(segment.samples.size) {
                (segment.samples[it] * 32767f).toInt().coerceIn(-32768, 32767).toShort()
            }
            if (pcm.isNotEmpty()) {
                // Pre-roll: prepend the windows just before the detected
                // start, clamped to the previous segment's end.
                val expandedStart = maxOf(segment.start - PRE_ROLL_SAMPLES, lastProcessedEnd)
                val pre = tailSlice(expandedStart, segment.start)
                result += if (pre.isNotEmpty()) pre + pcm else pcm
                lastProcessedEnd = maxOf(lastProcessedEnd, segment.start + segment.samples.size)
            }
        }
    }

    /** Raw PCM from the tail buffer between absolute sample positions. */
    private fun tailSlice(fromSample: Int, toSample: Int): ShortArray {
        val tailStart = appendedSamples - tailCount
        val from = maxOf(fromSample, tailStart)
        val to = minOf(toSample, appendedSamples)
        if (to <= from) return ShortArray(0)
        return tail.copyOfRange(from - tailStart, to - tailStart)
    }

    private fun appendTail(samples: ShortArray) {
        appendedSamples += samples.size
        if (samples.size >= MAX_TAIL_SAMPLES) {
            samples.copyInto(tail, 0, samples.size - MAX_TAIL_SAMPLES, samples.size)
            tailCount = MAX_TAIL_SAMPLES
            return
        }
        val overflow = tailCount + samples.size - MAX_TAIL_SAMPLES
        if (overflow > 0) {
            tail.copyInto(tail, 0, overflow, tailCount)
            tailCount -= overflow
        }
        samples.copyInto(tail, tailCount)
        tailCount += samples.size
    }

    override fun close() {
        vad.release()
    }

    private fun ensureModel(context: Context): String {
        val dir = File(context.filesDir, "models/vad").also { it.mkdirs() }
        val file = File(dir, MODEL_ASSET)
        if (!file.exists() || file.length() <= 100_000) {
            context.assets.open(MODEL_ASSET).use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return file.absolutePath
    }
}
