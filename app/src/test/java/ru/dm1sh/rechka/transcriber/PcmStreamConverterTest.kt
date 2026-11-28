package ru.dm1sh.rechka.transcriber

import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmStreamConverterTest {
    private class FakeResampler(
        private val id: Int,
        private val events: MutableList<String>,
    ) : PcmResampler {
        override fun process(input: ByteBuffer, byteCount: Int): ShortArray {
            events += "process:$id:$byteCount"
            return shortArrayOf((id * 10 + 1).toShort())
        }

        override fun flush(): ShortArray {
            events += "flush:$id"
            return shortArrayOf((id * 10 + 2).toShort())
        }

        override fun close() {
            events += "close:$id"
        }
    }

    @Test fun usesDecodedFormatAndReusesItAcrossBuffers() = runBlocking {
        val events = mutableListOf<String>()
        var nextId = 0
        val converter = PcmStreamConverter(
            emit = { events += "emit:${it.single()}" },
            createResampler = { format ->
                events += "create:${format.sampleRate}:${format.channels}"
                FakeResampler(++nextId, events)
            },
        )
        // HE-AAC can have a 22,050 Hz encoded core but 44,100 Hz decoded PCM.
        val decodedFormat = DecodedPcmFormat(sampleRate = 44_100, channels = 2)
        converter.accept(ByteBuffer.allocateDirect(8), decodedFormat)
        converter.accept(ByteBuffer.allocateDirect(12), decodedFormat)
        converter.finish()
        converter.close()
        assertEquals(
            listOf("create:44100:2", "process:1:8", "emit:11", "process:1:12", "emit:11",
                "flush:1", "emit:12", "close:1"),
            events,
        )
    }

    @Test fun formatChangeFlushesAndClosesBeforeCreatingTheNextResampler() = runBlocking {
        val events = mutableListOf<String>()
        var nextId = 0
        val converter = PcmStreamConverter(
            emit = { events += "emit:${it.single()}" },
            createResampler = { format ->
                events += "create:${format.sampleRate}:${format.channels}"
                FakeResampler(++nextId, events)
            },
        )
        converter.accept(ByteBuffer.allocateDirect(4), DecodedPcmFormat(22_050, 1))
        converter.accept(ByteBuffer.allocateDirect(8), DecodedPcmFormat(44_100, 2))
        converter.finish()
        converter.close()
        assertEquals(
            listOf("create:22050:1", "process:1:4", "emit:11", "flush:1", "emit:12", "close:1",
                "create:44100:2", "process:2:8", "emit:21", "flush:2", "emit:22", "close:2"),
            events,
        )
    }

    @Test fun changingOnlyRateOrOnlyChannelCountRecreatesResampler() = runBlocking {
        val changes = listOf(
            DecodedPcmFormat(22_050, 2) to DecodedPcmFormat(44_100, 2),
            DecodedPcmFormat(44_100, 1) to DecodedPcmFormat(44_100, 2),
        )
        for ((first, second) in changes) {
            val created = mutableListOf<DecodedPcmFormat>()
            val events = mutableListOf<String>()
            val converter = PcmStreamConverter(
                emit = {},
                createResampler = { format ->
                    created += format
                    FakeResampler(created.size, events)
                },
            )
            converter.accept(ByteBuffer.allocateDirect(4), first)
            converter.accept(ByteBuffer.allocateDirect(4), second)
            converter.finish()
            converter.close()
            assertEquals(listOf(first, second), created)
            assertEquals(listOf("flush:1", "close:1", "flush:2", "close:2"),
                events.filter { it.startsWith("flush:") || it.startsWith("close:") })
        }
    }

    @Test fun invalidFormatDoesNotCreateAResampler() = runBlocking {
        val events = mutableListOf<String>()
        val converter = PcmStreamConverter(
            emit = { events += "emit" },
            createResampler = { events += "create"; FakeResampler(1, events) },
        )
        val failure = runCatching {
            converter.accept(ByteBuffer.allocateDirect(4), DecodedPcmFormat(0, 1))
        }.exceptionOrNull()
        converter.finish()
        converter.close()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(events.isEmpty())
    }

    @Test fun closesPreviousResamplerIfEmittingItsTailFails() = runBlocking {
        val events = mutableListOf<String>()
        var nextId = 0
        val converter = PcmStreamConverter(
            emit = {
                events += "emit:${it.single()}"
                if (it.single() == 12.toShort()) error("output cancelled")
            },
            createResampler = { format ->
                events += "create:${format.sampleRate}:${format.channels}"
                FakeResampler(++nextId, events)
            },
        )
        converter.accept(ByteBuffer.allocateDirect(4), DecodedPcmFormat(22_050, 1))
        val failure = runCatching {
            converter.accept(ByteBuffer.allocateDirect(8), DecodedPcmFormat(44_100, 2))
        }.exceptionOrNull()
        converter.close()
        assertTrue(failure is IllegalStateException)
        assertEquals(listOf("create:22050:1", "process:1:4", "emit:11", "flush:1", "emit:12", "close:1"), events)
    }
}
