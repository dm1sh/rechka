package ru.dm1sh.rechka.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import ru.dm1sh.rechka.R
import ru.dm1sh.rechka.model.GigaAmModel
import ru.dm1sh.rechka.stats.StatsStore
import ru.dm1sh.rechka.transcriber.AudioFileDecoder
import ru.dm1sh.rechka.transcriber.Dictionary
import ru.dm1sh.rechka.transcriber.OfflineTranscriber
import ru.dm1sh.rechka.transcriber.SpeechSegmenter
import ru.dm1sh.rechka.util.Prefs
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Receives an audio file from Android's share sheet and transcribes it offline. */
class ShareTranscriptionActivity : AppCompatActivity() {
    companion object {
        private const val KEY_STATS_RECORDED = "share_stats_recorded"
        // Decoded PCM and VAD segments are 16 kHz mono (see SpeechSegmenter).
        private const val SAMPLE_RATE = 16_000
    }

    private lateinit var status: MaterialTextView
    private lateinit var result: MaterialTextView
    private lateinit var shareScroll: View
    private lateinit var progress: LinearProgressIndicator
    private lateinit var progressTime: MaterialTextView
    private lateinit var copy: MaterialButton
    private lateinit var shareAgain: MaterialButton
    private var transcript = ""
    private var statsRecorded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_share_transcription)
        val shareRoot = findViewById<View>(R.id.shareRoot)
        ViewCompat.setOnApplyWindowInsetsListener(shareRoot) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(top = bars.top, bottom = bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(shareRoot)
        statsRecorded = savedInstanceState?.getBoolean(KEY_STATS_RECORDED, false) ?: false
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.shareToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        status = findViewById(R.id.shareStatus)
        result = findViewById(R.id.shareResult)
        shareScroll = findViewById(R.id.shareScroll)
        progress = findViewById(R.id.shareProgress)
        progressTime = findViewById(R.id.shareProgressTime)
        copy = findViewById(R.id.shareCopy)
        shareAgain = findViewById(R.id.shareAgain)
        copy.setOnClickListener { copyTranscript() }
        shareAgain.setOnClickListener { shareTranscript() }

        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, android.net.Uri::class.java)
            ?: intent.data
        if (uri == null) {
            showError(getString(R.string.share_transcription_no_file))
            return
        }
        findViewById<MaterialTextView>(R.id.shareFileName).text =
            resolveDisplayName(uri) ?: getString(R.string.share_transcription_audio_file)
        transcribe(uri)
    }

    private fun transcribe(uri: android.net.Uri) {
        progress.isIndeterminate = true
        lifecycleScope.launch {
            try {
                var speechMillisTotal = 0L
                withContext(Dispatchers.IO) {
                    val transcriber = OfflineTranscriber.getInstance(this@ShareTranscriptionActivity)
                    val useVad = Prefs.isFileVadEnabled(this@ShareTranscriptionActivity)
                    val segmenter = if (useVad) SpeechSegmenter(this@ShareTranscriptionActivity) else null
                    val maxChunkSamples = 30 * 16_000
                    // Total speech time across transcribed segments, fed into
                    // the dictation-minutes counter together with the words
                    // when the transcript completes. With VAD on, segments
                    // are speech-only; with VAD off they are fixed chunks,
                    // so this is the processed audio duration either way.
                    suspend fun transcribeSegments(segments: List<ShortArray>) {
                        for (segment in segments) {
                            if (segment.isEmpty()) continue
                            speechMillisTotal += segment.size * 1000L / SAMPLE_RATE
                            val bytes = ByteArray(segment.size * 2)
                            for (i in segment.indices) {
                                val sample = segment[i].toInt()
                                bytes[i * 2] = (sample and 0xff).toByte()
                                bytes[i * 2 + 1] = (sample shr 8).toByte()
                            }
                            transcriber.startAudio()
                            transcriber.sendAudioChunk(bytes)
                            val part = transcriber.stopAudioAndGetTranscript()
                            val replaced = Dictionary.applyReplacements(this@ShareTranscriptionActivity, part)
                            if (replaced.isNotBlank()) {
                                withContext(Dispatchers.Main) { appendTranscriptPart(replaced) }
                            }
                        }
                    }

                    try {
                        // Keep a small bounded queue between decoding/VAD and
                        // recognition. The decoder and VAD can continue producing
                        // completed speech segments while the recognizer handles
                        // the previous one. No arbitrary time-based segments are
                        // introduced; queue items are produced only by VAD.
                        val segmentQueue = Channel<ShortArray>(capacity = 2)
                        var fixedBuffer = ShortArray(maxChunkSamples)
                        var fixedCount = 0
                        suspend fun enqueueFixedChunks(input: ShortArray) {
                            var offset = 0
                            while (offset < input.size) {
                                val count = minOf(maxChunkSamples - fixedCount, input.size - offset)
                                input.copyInto(fixedBuffer, fixedCount, offset, offset + count)
                                fixedCount += count
                                offset += count
                                if (fixedCount == maxChunkSamples) {
                                    segmentQueue.send(fixedBuffer)
                                    fixedBuffer = ShortArray(maxChunkSamples)
                                    fixedCount = 0
                                }
                            }
                        }
                        coroutineScope {
                            val decoderJob = launch {
                                try {
                                    AudioFileDecoder.decodeStreaming(
                                        context = this@ShareTranscriptionActivity,
                                        uri = uri,
                                        onPcm = { pcmChunk ->
                                            if (useVad) {
                                                for (segment in segmenter!!.acceptPcm(pcmChunk)) {
                                                    segmentQueue.send(segment)
                                                }
                                            } else {
                                                enqueueFixedChunks(pcmChunk)
                                            }
                                        },
                                        onProgress = { positionUs, durationUs ->
                                            if (durationUs > 0L) {
                                                withContext(Dispatchers.Main) {
                                                    progress.isIndeterminate = false
                                                    progress.max = 100
                                                    progress.progress =
                                                        (positionUs * 100L / durationUs).toInt().coerceIn(0, 99)
                                                    progressTime.text = "${formatTime(positionUs)} / ${formatTime(durationUs)}"
                                                }
                                            }
                                        },
                                    )
                                    if (useVad) {
                                        for (segment in segmenter!!.flush()) {
                                            segmentQueue.send(segment)
                                        }
                                    } else if (fixedCount > 0) {
                                        segmentQueue.send(fixedBuffer.copyOf(fixedCount))
                                    }
                                } finally {
                                    segmentQueue.close()
                                }
                            }
                            try {
                                for (segment in segmentQueue) {
                                    transcribeSegments(listOf(segment))
                                }
                            } finally {
                                decoderJob.cancelAndJoin()
                            }
                        }
                        withContext(Dispatchers.Main) {
                            progress.isIndeterminate = false
                            progress.progress = 100
                        }
                    } finally {
                        segmenter?.close()
                    }
                }
                progress.visibility = View.GONE
                if (transcript.isBlank()) {
                    showError(getString(R.string.share_transcription_empty))
                } else {
                    if (!statsRecorded) {
                        StatsStore.addWords(applicationContext, StatsStore.countWords(transcript))
                        StatsStore.addSpeechMillis(applicationContext, speechMillisTotal)
                        statsRecorded = true
                    }
                    status.text = getString(R.string.share_transcription_done)
                    result.visibility = View.VISIBLE
                    copy.visibility = View.VISIBLE
                    shareAgain.visibility = View.VISIBLE
                }
            } catch (t: Throwable) {
                showError(getString(R.string.share_transcription_failed, t.message ?: ""))
            }
        }
    }

    private fun appendTranscriptPart(part: String) {
        val clean = part.trim()
        if (clean.isEmpty()) return
        val previous = transcript.lastOrNull()
        val first = clean.first()
        val needsSpace = previous != null &&
            !previous.isWhitespace() &&
            first.isLetterOrDigit() &&
            previous !in "([\\{\\\"'«"
        val scrollX = shareScroll.scrollX
        val scrollY = shareScroll.scrollY
        transcript += if (needsSpace) " $clean" else clean
        result.text = transcript
        result.visibility = View.VISIBLE
        // Updating TextView layout can otherwise reset the parent ScrollView's
        // viewport. Restore the exact position after the new layout is measured.
        shareScroll.post { shareScroll.scrollTo(scrollX, scrollY) }
    }

    /**
     * Human-readable file name for the header. lastPathSegment is URI-encoded
     * (and for shared files often embeds the whole encoded path, e.g.
     * "file%3A%2F%2F%2Fstorage%2F..."), so prefer the provider's
     * DISPLAY_NAME and fall back to the decoded tail of the path.
     */
    private fun resolveDisplayName(uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                contentResolver.query(
                    uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
                )?.use { cursor ->
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0 && cursor.moveToFirst()) {
                        cursor.getString(index)?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { return it }
                    }
                }
            } catch (_: Exception) {
                // Provider refused or has no name — fall through to the path.
            }
        }
        val decoded = Uri.decode(uri.lastPathSegment ?: uri.path ?: return null).trim()
        return decoded.substringAfterLast('/').takeIf { it.isNotEmpty() }
    }

    private fun formatTime(micros: Long): String {
        if (micros <= 0L) return "0:00"
        val totalSeconds = micros / 1_000_000L
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3_600
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(KEY_STATS_RECORDED, statsRecorded)
        super.onSaveInstanceState(outState)
    }

    private fun showError(message: String) {
        progress.visibility = View.GONE
        status.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun copyTranscript() {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(
            ClipData.newPlainText(getString(R.string.share_transcription_title), transcript)
        )
        Toast.makeText(this, R.string.share_transcription_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareTranscript() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, transcript)
        }, getString(R.string.share_transcription_share_chooser)))
    }
}
