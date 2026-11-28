package ru.dm1sh.rechka.transcriber

import java.nio.ByteBuffer

/** The PCM format reported by the decoder for an output buffer. */
internal data class DecodedPcmFormat(val sampleRate: Int, val channels: Int)

internal interface PcmResampler : AutoCloseable {
    fun process(input: ByteBuffer, byteCount: Int): ShortArray
    fun flush(): ShortArray
}

/** Converts decoded PCM to 16 kHz mono, including mid-stream format changes. */
internal class PcmStreamConverter(
    private val emit: suspend (ShortArray) -> Unit,
    private val createResampler: (DecodedPcmFormat) -> PcmResampler = {
        NativeResampler(it.sampleRate, it.channels)
    },
) : AutoCloseable {
    private var format: DecodedPcmFormat? = null
    private var resampler: PcmResampler? = null

    suspend fun accept(bytes: ByteBuffer, decodedFormat: DecodedPcmFormat) {
        require(decodedFormat.sampleRate > 0 && decodedFormat.channels > 0) {
            "Invalid decoded PCM format: $decodedFormat"
        }
        if (decodedFormat != format) {
            finish()
            resampler = createResampler(decodedFormat)
            format = decodedFormat
        }
        val output = checkNotNull(resampler).process(bytes, bytes.remaining())
        if (output.isNotEmpty()) emit(output)
    }

    /** Flush and release the old resampler before changing formats or at EOF. */
    suspend fun finish() {
        val previous = resampler ?: return
        resampler = null
        format = null
        try {
            val output = previous.flush()
            if (output.isNotEmpty()) emit(output)
        } finally {
            previous.close()
        }
    }

    /** Release without emitting buffered samples when decoding fails or is cancelled. */
    override fun close() {
        val previous = resampler
        resampler = null
        format = null
        previous?.close()
    }
}
