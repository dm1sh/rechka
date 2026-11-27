package ru.dm1sh.rechka.model

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** A single pinned artifact. The downloaded bytes must match both size and SHA-256. */
data class ModelFile(val name: String, val sizeBytes: Long, val sha256: String)

/**
 * Filesystem-only transactional installer. Its methods are serialized so a
 * background cleanup cannot delete an in-progress installation. A failed or
 * interrupted replacement must not destroy the previously installed model.
 */
internal class ModelFileInstaller(
    private val root: File,
    private val files: List<ModelFile>,
    private val installDir: String,
    private val stagingDir: String,
    private val backupDir: String,
    private val marker: String,
) {
    enum class Result { SUCCESS, INVALID, UNREADABLE }

    val directory: File get() = File(root, installDir)
    private val stage: File get() = File(root, stagingDir)
    private val backup: File get() = File(root, backupDir)

    @Synchronized
    fun readMarker(): String? = try {
        File(directory, MARKER_NAME).takeIf { it.isFile }?.readText()?.trim()
    } catch (_: Exception) {
        null
    }

    @Synchronized
    fun hasExpectedFiles(): Boolean = files.all {
        File(directory, it.name).let { file -> file.isFile && file.length() == it.sizeBytes }
    }

    @Synchronized
    fun validateInstalled(): Boolean = files.all {
        val file = File(directory, it.name)
        file.isFile && file.length() == it.sizeBytes &&
            file.inputStream().use { input -> sha256(input) } == it.sha256
    }

    @Synchronized
    fun writeInstalledMarker() {
        File(directory, MARKER_NAME).writeText(marker)
    }

    /** Restore the previous version if the process died between renames. */
    @Synchronized
    fun recoverInterruptedSwap() {
        if (!directory.exists() && backup.exists()) backup.renameTo(directory)
    }

    @Synchronized
    fun cleanupAbandonedDirectories() {
        stage.deleteRecursively()
        if (directory.exists()) backup.deleteRecursively()
    }

    /**
     * Copy and hash each artifact into a sibling staging directory, then swap
     * directories. A source length of null or zero is treated as unknown (SAF
     * providers may not expose a length); the actual bytes are always checked.
     */
    @Synchronized
    fun install(
        sourceLength: (ModelFile) -> Long?,
        openSource: (ModelFile) -> InputStream?,
    ): Result {
        if (!root.isDirectory && !root.mkdirs()) return Result.INVALID
        recoverInterruptedSwap()
        if (stage.exists() && !stage.deleteRecursively()) return Result.INVALID
        if (!stage.mkdirs()) return Result.INVALID

        try {
            for (expected in files) {
                val length = sourceLength(expected)
                if (length != null && length > 0 && length != expected.sizeBytes) {
                    return Result.INVALID
                }
                val input = try {
                    openSource(expected)
                } catch (_: Exception) {
                    return Result.UNREADABLE
                } ?: return Result.UNREADABLE

                val digest = MessageDigest.getInstance("SHA-256")
                var total = 0L
                input.use { source ->
                    File(stage, expected.name).outputStream().buffered(BUFFER_SIZE).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val count = source.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            total += count
                            // Never allow an unexpected provider to fill internal storage.
                            if (total > expected.sizeBytes) return Result.INVALID
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
                if (total != expected.sizeBytes || digest.digest().toHex() != expected.sha256) {
                    return Result.INVALID
                }
            }

            File(stage, MARKER_NAME).writeText(marker)
            if (!commitStagedDirectory()) return Result.INVALID
            return Result.SUCCESS
        } catch (_: SecurityException) {
            return Result.UNREADABLE
        } catch (_: Exception) {
            return Result.INVALID
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    private fun commitStagedDirectory(): Boolean {
        if (backup.exists() && !backup.deleteRecursively()) return false
        val hadPrevious = directory.exists()
        if (hadPrevious && !directory.renameTo(backup)) return false
        if (!stage.renameTo(directory)) {
            if (hadPrevious && backup.exists()) backup.renameTo(directory)
            return false
        }
        if (backup.exists()) backup.deleteRecursively()
        return true
    }

    companion object {
        private const val MARKER_NAME = ".model-version"
        private const val BUFFER_SIZE = 1024 * 1024

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
            return digest.digest().toHex()
        }

        fun fingerprint(files: List<ModelFile>): String {
            val manifest = files.joinToString("\n") { "${it.name}:${it.sizeBytes}:${it.sha256}" }
            return MessageDigest.getInstance("SHA-256")
                .digest(manifest.toByteArray(Charsets.UTF_8)).toHex()
        }
    }
}
