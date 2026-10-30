package ru.dm1sh.rechka.service

import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import ru.dm1sh.rechka.model.GigaAmModel
import ru.dm1sh.rechka.stats.StatsStore
import ru.dm1sh.rechka.transcriber.Dictionary
import ru.dm1sh.rechka.transcriber.NativeResampler
import ru.dm1sh.rechka.transcriber.OfflineTranscriber
import ru.dm1sh.rechka.transcriber.SpeechSegmenter
import ru.dm1sh.rechka.transcriber.VadRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileInputStream
import java.nio.ByteBuffer

/** Exposes the same offline recognizer through Android's speech-recognition API. */
class RechkaRecognitionService : RecognitionService() {
    private var recorder: VadRecorder? = null
    private var fileJob: Job? = null
    @Volatile
    private var fileInput: FileInputStream? = null
    private var callback: Callback? = null
    private var recognized = StringBuilder()
    private var scope: CoroutineScope? = null

    override fun onStartListening(intent: Intent?, listener: Callback) {
        stopCurrent()
        callback = listener
        recognized = StringBuilder()
        if (!GigaAmModel.isInstalled(this)) {
            listener.error(SpeechRecognizer.ERROR_SERVER)
            return
        }
        // A caller may supply the audio instead of asking for the microphone.
        // Checked before the mic path so no RECORD_AUDIO session is opened for
        // supplied audio, and so a caller we cannot serve never records the room.
        val supplied = intent?.getParcelableExtra(
            RecognizerIntent.EXTRA_AUDIO_SOURCE, ParcelFileDescriptor::class.java,
        )
        if (supplied != null) {
            val source = describe(supplied, intent)
            if (source == null) {
                try {
                    supplied.close()
                } catch (_: Throwable) {
                }
                listener.error(SpeechRecognizer.ERROR_CLIENT)
                return
            }
            startFileSession(source, listener)
            return
        }
        listener.readyForSpeech(Bundle())
        listener.beginningOfSpeech()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sessionScope
        val currentRecorder = VadRecorder(this)
        recorder = currentRecorder
        currentRecorder.start(
            scope = sessionScope,
            transcriberProvider = { OfflineTranscriber.getInstance(this@RechkaRecognitionService) },
            onSegment = { text ->
                if (text.isNotBlank()) {
                    if (recognized.isNotEmpty()) recognized.append(' ')
                    recognized.append(text)
                    StatsStore.addWords(applicationContext, StatsStore.countWords(text))
                    callback?.partialResults(resultBundle(recognized.toString()))
                }
            },
            onDone = {
                val text = recognized.toString().trim()
                // SpeechRecognizer clients expect endOfSpeech before the final
                // results callback. Some keyboards ignore results delivered in
                // the opposite order and appear to do nothing.
                callback?.endOfSpeech()
                callback?.results(resultBundle(text))
                clearSession()
            },
            useVad = true,
        )
    }

    /**
     * Format description of caller-supplied audio.
     *
     * [EXTRA_AUDIO_SOURCE] carries an already-open descriptor to a **headerless
     * PCM stream** (a pipe, typically), not an encoded container, and the format
     * is described by three companion extras whose documented defaults are
     * 16 kHz / mono / PCM 16-bit.
     */
    private class AudioSource(
        val pfd: ParcelFileDescriptor,
        val sampleRate: Int,
        val channelCount: Int,
    )

    private fun describe(pfd: ParcelFileDescriptor, intent: Intent?): AudioSource? {
        val rate = intent?.getIntExtra(
            RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000,
        ) ?: 16_000
        val channels = intent?.getIntExtra(
            RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1,
        ) ?: 1
        val encoding = intent?.getIntExtra(
            RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT,
        ) ?: AudioFormat.ENCODING_PCM_16BIT
        if (rate <= 0 || channels <= 0 || encoding != AudioFormat.ENCODING_PCM_16BIT) {
            // NativeResampler consumes S16 only; refuse rather than misread the stream.
            Log.w(TAG, "unsupported supplied audio: ${rate}Hz/${channels}ch/enc=$encoding")
            return null
        }
        return AudioSource(pfd, rate, channels)
    }

    /**
     * Recognizes caller-supplied audio. The descriptor is read directly as PCM —
     * never reopened as a file — resampled to the 16 kHz mono the segmenter and
     * the recognizer need, then segmented and transcribed exactly like the mic
     * path: partial results per speech segment, then endOfSpeech() + results().
     */
    private fun startFileSession(source: AudioSource, listener: Callback) {
        listener.readyForSpeech(Bundle())
        listener.beginningOfSpeech()
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sessionScope
        fileJob = sessionScope.launch {
            var speechMillis = 0L
            try {
                runFileSession(source, listener) { speechMillis += it }
            } catch (e: CancellationException) {
                // stopListening()/cancel(): report what was recognized so far and
                // let the cancellation propagate, like the mic path's onDone.
                withContext(NonCancellable) {
                    creditSpeech(speechMillis)
                    reportFileResults(listener)
                }
                throw e
            } catch (t: Throwable) {
                if (!currentCoroutineContext().isActive) {
                    // Stopped: closing the stream interrupted the blocking read.
                    // That is a finish, not a failure — report what we have.
                    withContext(NonCancellable) {
                        creditSpeech(speechMillis)
                        reportFileResults(listener)
                    }
                    return@launch
                }
                Log.w(TAG, "supplied-audio session failed", t)
                withContext(NonCancellable) {
                    if (callback === listener) {
                        listener.error(SpeechRecognizer.ERROR_CLIENT)
                        clearSession()
                    }
                }
                return@launch
            }
            // The descriptor is a dup into this process; the read stream above closes
            // it at end of stream. The caller's own descriptor is never touched, so a
            // cancelled session leaves nothing dangling on this side either.
            creditSpeech(speechMillis)
            withContext(NonCancellable) { reportFileResults(listener) }
        }
    }

    private fun creditSpeech(millis: Long) {
        if (millis > 0) StatsStore.addSpeechMillis(applicationContext, millis)
    }

    /** Delivers final results to this session's client, unless superseded. */
    private fun reportFileResults(target: Callback) {
        if (callback !== target) return
        val text = recognized.toString().trim()
        target.endOfSpeech()
        target.results(resultBundle(text))
        clearSession()
    }

    /** Reads [source] and transcribes every speech segment it contains. */
    private suspend fun runFileSession(
        source: AudioSource,
        listener: Callback,
        onSpeechMillis: (Long) -> Unit,
    ) {
        val transcriber = OfflineTranscriber.getInstance(this)
        SpeechSegmenter(this).use { segmenter ->
            NativeResampler(source.sampleRate, source.channelCount).use { resampler ->
                val input = FileInputStream(source.pfd.fileDescriptor)
                fileInput = input
                try {
                    input.use {
                        val buffer = ByteBuffer.allocateDirect(64 * 1024)
                        val pcm = ByteArray(buffer.capacity())
                        // NativeResampler drops a trailing partial PCM frame
                        // (input_bytes / (2 * channels)); a pipe read may end
                        // mid-frame, so carry the remainder into the next read
                        // instead of byte-shifting the rest of the stream.
                        val frameSize = 2 * source.channelCount
                        var leftover = ByteArray(0)
                        while (true) {
                            val read = input.read(pcm)
                            if (read < 0) break
                            if (read == 0) continue
                            val chunk =
                                if (leftover.isEmpty()) {
                                    pcm.copyOf(read)
                                } else {
                                    leftover + pcm.copyOf(read)
                                }
                            val usable = chunk.size - chunk.size % frameSize
                            leftover =
                                if (usable < chunk.size) {
                                    chunk.copyOfRange(usable, chunk.size)
                                } else {
                                    ByteArray(0)
                                }
                            if (usable == 0) continue
                            for (samples in acceptPcm(resampler, segmenter, buffer, chunk, usable)) {
                                val text = transcribeSegment(transcriber, samples)
                                onSpeechMillis(samples.size * 1000L / SAMPLE_RATE)
                                appendSegment(text, listener)
                            }
                        }
                        // A trailing partial frame at EOF is incomplete: drop it.
                        for (samples in flush(resampler, segmenter)) {
                            val text = transcribeSegment(transcriber, samples)
                            onSpeechMillis(samples.size * 1000L / SAMPLE_RATE)
                            appendSegment(text, listener)
                        }
                    }
                } finally {
                    if (fileInput === input) fileInput = null
                }
            }
        }
    }

    private fun acceptPcm(
        resampler: NativeResampler,
        segmenter: SpeechSegmenter,
        buffer: ByteBuffer,
        data: ByteArray,
        byteCount: Int,
    ): List<ShortArray> {
        buffer.clear()
        buffer.put(data, 0, byteCount)
        buffer.position(0)
        buffer.limit(byteCount)
        return segmenter.acceptPcm(resampler.process(buffer, byteCount))
    }

    private fun flush(resampler: NativeResampler, segmenter: SpeechSegmenter): List<ShortArray> {
        val segments = ArrayList<ShortArray>()
        val tail = resampler.flush()
        if (tail.isNotEmpty()) segments += segmenter.acceptPcm(tail)
        segments += segmenter.flush()
        return segments
    }

    private suspend fun appendSegment(text: String, listener: Callback) {
        val replaced = Dictionary.applyReplacements(this, text)
        if (replaced.isBlank()) return
        if (recognized.isNotEmpty()) recognized.append(' ')
        recognized.append(replaced)
        StatsStore.addWords(applicationContext, StatsStore.countWords(replaced))
        withContext(Dispatchers.Main) {
            if (callback === listener) {
                listener.partialResults(resultBundle(recognized.toString()))
            }
        }
    }

    private suspend fun transcribeSegment(
        transcriber: OfflineTranscriber,
        samples: ShortArray,
    ): String = withContext(Dispatchers.Default) {
        if (samples.isEmpty()) return@withContext ""
        val bytes = ByteArray(samples.size * 2)
        var i = 0
        while (i < samples.size) {
            val sample = samples[i].toInt()
            bytes[i * 2] = (sample and 0xff).toByte()
            bytes[i * 2 + 1] = (sample shr 8).toByte()
            i++
        }
        transcriber.startAudio()
        transcriber.sendAudioChunk(bytes)
        transcriber.stopAudioAndGetTranscript()
    }

    override fun onStopListening(listener: Callback) {
        recorder?.stop()
        // Supplied audio: the caller closing the descriptor is the natural end of
        // the stream, so stopListening() means "finish now" — cancel the read and
        // still deliver what was recognized (see the CancellationException branch).
        fileJob?.cancel()
        closeFileInput()
    }

    override fun onCancel(listener: Callback) {
        stopCurrent()
    }

    private fun resultBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    private fun closeFileInput() {
        try {
            fileInput?.close()
        } catch (_: Throwable) {
        }
    }

    private fun stopCurrent() {
        recorder?.stop()
        fileJob?.cancel()
        fileJob = null
        closeFileInput()
        scope?.cancel()
        recorder = null
        scope = null
    }

    private fun clearSession() {
        recorder = null
        scope?.cancel()
        scope = null
        callback = null
    }

    override fun onDestroy() {
        stopCurrent()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "RechkaRecognition"
        const val SAMPLE_RATE = 16_000
    }
}
