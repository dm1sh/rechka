package ru.dm1sh.rechka.transcriber

import java.nio.ByteBuffer

internal class NativeResampler(sampleRate: Int, channels: Int) : PcmResampler {
    private var handle = nativeCreate(sampleRate, channels)

    override fun process(input: ByteBuffer, byteCount: Int): ShortArray {
        check(handle != 0L) { "Native resampler is closed" }
        return nativeProcess(handle, input, byteCount)
    }

    override fun flush(): ShortArray {
        if (handle == 0L) return ShortArray(0)
        return nativeFlush(handle)
    }

    override fun close() {
        if (handle != 0L) {
            nativeRelease(handle)
            handle = 0L
        }
    }

    private companion object {
        init { System.loadLibrary("rechka_resampler") }
        external fun nativeCreate(sampleRate: Int, channels: Int): Long
        external fun nativeProcess(handle: Long, input: ByteBuffer, byteCount: Int): ShortArray
        external fun nativeFlush(handle: Long): ShortArray
        external fun nativeRelease(handle: Long)
    }
}
