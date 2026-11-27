package ru.dm1sh.rechka.model

import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelFileInstallerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun sha(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it) }

    private fun fixture(): Fixture {
        val root = temp.newFolder()
        val payloads = linkedMapOf("encoder" to "new encoder".toByteArray(), "tokens" to "new tokens".toByteArray())
        val specs = payloads.map { (name, data) -> ModelFile(name, data.size.toLong(), sha(data)) }
        val marker = ModelFileInstaller.fingerprint(specs)
        val installer = ModelFileInstaller(root, specs, "v4", ".new", ".old", marker)
        return Fixture(root, payloads, installer, marker)
    }

    private data class Fixture(
        val root: File,
        val payloads: Map<String, ByteArray>,
        val installer: ModelFileInstaller,
        val marker: String,
    ) {
        fun seedPrevious() {
            File(root, "v4").mkdirs()
            File(root, "v4/previous").writeText("old model")
            File(root, "gigaam-v3-e2e-rnnt").mkdirs()
            File(root, "gigaam-v3-e2e-rnnt/legacy").writeText("legacy model")
        }

        fun previousStillExists() {
            assertEquals("old model", File(root, "v4/previous").readText())
            assertEquals("legacy model", File(root, "gigaam-v3-e2e-rnnt/legacy").readText())
            assertFalse(File(root, ".new").exists())
        }
    }

    @Test fun verifiedInstallationSwapsOnlyAfterEveryFileMatches() {
        val f = fixture()
        f.seedPrevious()
        val result = f.installer.install(
            sourceLength = { 0L }, // unknown SAF length is allowed
            openSource = { ByteArrayInputStream(f.payloads.getValue(it.name)) },
        )
        assertEquals(ModelFileInstaller.Result.SUCCESS, result)
        assertEquals(f.marker, f.installer.readMarker())
        assertTrue(f.installer.hasExpectedFiles())
        assertTrue(f.installer.validateInstalled())
        assertFalse(File(f.root, "v4/previous").exists())
        assertFalse(File(f.root, ".old").exists())
        // Legacy cleanup is a separate operation after successful validation.
        assertEquals("legacy model", File(f.root, "gigaam-v3-e2e-rnnt/legacy").readText())
    }

    @Test fun wrongHashDoesNotReplaceTheInstalledModel() {
        val f = fixture()
        f.seedPrevious()
        val result = f.installer.install(
            sourceLength = { it.sizeBytes },
            openSource = {
                val data = f.payloads.getValue(it.name).copyOf()
                if (it.name == "tokens") data[0] = (data[0].toInt() xor 1).toByte()
                ByteArrayInputStream(data)
            },
        )
        assertEquals(ModelFileInstaller.Result.INVALID, result)
        f.previousStillExists()
    }

    @Test fun truncatedOrOversizedContentDoesNotReplacePreviousModel() {
        for (extra in listOf(-1, 1)) {
            val f = fixture()
            f.seedPrevious()
            val result = f.installer.install(
                sourceLength = { null },
                openSource = {
                    val original = f.payloads.getValue(it.name)
                    val data = if (it.name != "tokens") original else original.copyOf(original.size + extra)
                    ByteArrayInputStream(data)
                },
            )
            assertEquals(ModelFileInstaller.Result.INVALID, result)
            f.previousStillExists()
        }
    }

    @Test fun unreadableSourceAndWrongDeclaredSizePreservePreviousModel() {
        val f = fixture()
        f.seedPrevious()
        assertEquals(ModelFileInstaller.Result.UNREADABLE, f.installer.install(
            sourceLength = { null },
            openSource = { throw SecurityException("permission revoked") },
        ))
        f.previousStillExists()
        assertEquals(ModelFileInstaller.Result.INVALID, f.installer.install(
            sourceLength = { it.sizeBytes + 1 },
            openSource = { ByteArrayInputStream(f.payloads.getValue(it.name)) },
        ))
        f.previousStillExists()
    }

    @Test fun sourceReadFailureAfterPartialCopyPreservesPreviousModel() {
        val f = fixture()
        f.seedPrevious()
        val result = f.installer.install(
            sourceLength = { it.sizeBytes },
            openSource = {
                if (it.name == "encoder") ByteArrayInputStream(f.payloads.getValue(it.name))
                else object : java.io.InputStream() {
                    override fun read(): Int = throw java.io.IOException("provider disconnected")
                }
            },
        )
        assertEquals(ModelFileInstaller.Result.INVALID, result)
        f.previousStillExists()
    }

    @Test fun interruptedSwapRestoresBackupAndDoesNotDeleteLegacy() {
        val f = fixture()
        f.seedPrevious()
        assertTrue(File(f.root, "v4").renameTo(File(f.root, ".old")))
        File(f.root, ".new").mkdirs()
        File(f.root, ".new/partial").writeText("incomplete")
        f.installer.cleanupAbandonedDirectories()
        assertTrue(File(f.root, ".old/previous").exists())
        f.installer.recoverInterruptedSwap()
        f.previousStillExists()
    }

    @Test(timeout = 10000) fun cleanupCannotDeleteAnInProgressStagingDirectory() {
        val f = fixture()
        f.seedPrevious()
        val enteredRead = CountDownLatch(1)
        val continueRead = CountDownLatch(1)
        val cleanupDone = CountDownLatch(1)
        val outcome = AtomicReference<ModelFileInstaller.Result>()
        val worker = thread(isDaemon = true) {
            outcome.set(f.installer.install(
                sourceLength = { it.sizeBytes },
                openSource = {
                    object : ByteArrayInputStream(f.payloads.getValue(it.name)) {
                        private var first = true
                        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                            if (first) {
                                first = false
                                enteredRead.countDown()
                                check(continueRead.await(3, TimeUnit.SECONDS))
                            }
                            return super.read(bytes, offset, length)
                        }
                    }
                },
            ))
        }
        try {
            assertTrue(enteredRead.await(3, TimeUnit.SECONDS))
            val cleanup = thread(isDaemon = true) {
                f.installer.cleanupAbandonedDirectories()
                cleanupDone.countDown()
            }
            assertFalse("cleanup must wait for the copy", cleanupDone.await(200, TimeUnit.MILLISECONDS))
            continueRead.countDown()
            worker.join(3000)
            cleanup.join(3000)
            assertEquals(ModelFileInstaller.Result.SUCCESS, outcome.get())
            assertEquals(0L, cleanupDone.count)
            assertTrue(f.installer.validateInstalled())
        } finally {
            continueRead.countDown()
        }
    }

    @Test fun sameLengthCorruptionIsCaughtByFullValidation() {
        val f = fixture()
        assertEquals(ModelFileInstaller.Result.SUCCESS, f.installer.install(
            sourceLength = { it.sizeBytes },
            openSource = { ByteArrayInputStream(f.payloads.getValue(it.name)) },
        ))
        val tokens = File(f.root, "v4/tokens")
        val bytes = tokens.readBytes()
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        tokens.writeBytes(bytes)
        assertTrue(f.installer.hasExpectedFiles())
        assertFalse(f.installer.validateInstalled())
    }
}
