package ru.dm1sh.rechka.model

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import ru.dm1sh.rechka.BuildConfig
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.concurrent.thread

/** GigaAM v3 files installed in the app-private directory used by sherpa-onnx. */
object GigaAmModel {
    const val ENCODER = "gigaam_v3_e2e_rnnt_encoder_int8.onnx"
    const val DECODER = "gigaam_v3_e2e_rnnt_decoder.onnx"
    const val JOINER = "gigaam_v3_e2e_rnnt_joint.onnx"
    const val TOKENS = "gigaam_v3_e2e_rnnt_tokens.txt"

    data class ModelFile(val name: String, val sizeBytes: Long, val sha256: String)

    enum class InstallState { CHECKING, INSTALLING, READY, MISSING, OUTDATED, FAILED }
    enum class CopyResult { SUCCESS, MISSING, INVALID, UNREADABLE }

    private const val TAG = "GigaAmModel"
    private const val INSTALL_DIR = "gigaam-v3-e2e-rnnt"
    private const val STAGING_DIR = ".gigaam-v3-e2e-rnnt.new"
    private const val BACKUP_DIR = ".gigaam-v3-e2e-rnnt.old"
    private const val VERSION_MARKER = ".model-version"
    private const val COPY_BUFFER_SIZE = 1024 * 1024

    val FILES = listOf(
        ModelFile(ENCODER, 318_995_997L, "2cac62d0c270bd128f898f2be1a2d34780d524a6e9483888ebac7b00f97410f1"),
        ModelFile(DECODER, 4_600_058L, "781971998e6a355d6a714f6932a30eab295e7ba0d14fd7e0f78c83b87e811860"),
        ModelFile(JOINER, 2_712_896L, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
        ModelFile(TOKENS, 13_353L, "7ddf22514c42c531358182c81446a8159771e9921019f09ae743ea622d40221d"),
    )

    @Volatile
    var installState: InstallState = InstallState.CHECKING
        private set

    val isBundledVariant: Boolean
        get() = BuildConfig.BUNDLED_MODEL

    @Volatile
    private var initializationStarted = false

    private val modelFingerprint: String by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val manifest = FILES.joinToString(separator = "\n") {
            "${it.name}:${it.sizeBytes}:${it.sha256}"
        }
        sha256(manifest.toByteArray(Charsets.UTF_8))
    }

    /**
     * Called once from Application.onCreate. The light APK validates legacy
     * SAF-installed files and keeps a small marker for subsequent starts. The
     * full APK installs/replaces the bundled model off the main thread.
     */
    @Synchronized
    fun initialize(context: Context, onDiagnostic: (String) -> Unit = {}) {
        if (initializationStarted) return
        initializationStarted = true
        val appContext = context.applicationContext

        // Recover the old directory if the process was interrupted in the
        // tiny window between renaming the live directory and installing the
        // fully staged replacement.
        recoverInterruptedSwap(appContext)

        if (isBundledVariant) {
            startBundledInstall(appContext, onDiagnostic)
            return
        }

        val marker = readVersionMarker(appContext)
        when {
            marker == modelFingerprint && hasExpectedFiles(appContext) -> {
                installState = InstallState.READY
                thread(name = "model-cleanup", isDaemon = true) {
                    cleanupAbandonedDirectories(appContext)
                }
            }
            marker != null -> {
                // A light APK cannot replace a different model version. Ask
                // the user to install the full APK for this release (or select
                // the matching model files in onboarding).
                installState = InstallState.OUTDATED
            }
            hasExpectedFiles(appContext) -> {
                // One-time migration for users whose model was installed by an
                // older app version, before the private version marker existed.
                installState = InstallState.CHECKING
                thread(name = "legacy-model-validation", isDaemon = true, priority = Thread.NORM_PRIORITY - 1) {
                    try {
                        if (validateInstalled(appContext)) {
                            writeVersionMarker(modelDirectory(appContext))
                            installState = InstallState.READY
                        } else {
                            installState = InstallState.MISSING
                            report(onDiagnostic, "Legacy installed model is invalid or outdated")
                        }
                        cleanupAbandonedDirectories(appContext)
                    } catch (t: Throwable) {
                        installState = InstallState.MISSING
                        report(onDiagnostic, "Legacy model validation failed: ${t.message}")
                    }
                }
            }
            else -> installState = InstallState.MISSING
        }
    }

    @Volatile
    private var bundledInstallRunning = false

    /** Retries a failed bundled-model install (full APK onboarding only). */
    fun retryBundledInstall(context: Context, onDiagnostic: (String) -> Unit = {}) {
        if (!isBundledVariant || installState != InstallState.FAILED) return
        startBundledInstall(context.applicationContext, onDiagnostic)
    }

    private fun startBundledInstall(appContext: Context, onDiagnostic: (String) -> Unit) {
        synchronized(this) {
            if (bundledInstallRunning) return
            bundledInstallRunning = true
        }
        installState = InstallState.INSTALLING
        thread(name = "bundled-model-install", isDaemon = true, priority = Thread.NORM_PRIORITY - 1) {
            try {
                // Fast path: a previous launch of this model version already
                // installed and validated these exact files.
                if (readVersionMarker(appContext) == modelFingerprint &&
                    hasExpectedFiles(appContext)
                ) {
                    cleanupAbandonedDirectories(appContext)
                    installState = InstallState.READY
                    return@thread
                }
                // A model previously selected through SAF may already be
                // the exact model in this full APK. Validate it and mark it
                // rather than copying an identical 326 MB payload again.
                if (hasExpectedFiles(appContext) && validateInstalled(appContext)) {
                    writeVersionMarker(modelDirectory(appContext))
                    installState = InstallState.READY
                } else {
                    val result = installBundledModel(appContext)
                    if (result == CopyResult.SUCCESS) {
                        installState = InstallState.READY
                    } else {
                        installState = InstallState.FAILED
                        report(onDiagnostic, "Bundled model install failed: $result")
                    }
                }
                cleanupAbandonedDirectories(appContext)
            } catch (t: Throwable) {
                installState = InstallState.FAILED
                report(onDiagnostic, "Bundled model install failed: ${t.message}")
            } finally {
                bundledInstallRunning = false
            }
        }
    }

    /** Filesystem paths remain the sherpa-onnx source for both APK variants. */
    fun modelDir(context: Context): File =
        modelDirectory(context).also { it.mkdirs() }

    /** Fast readiness check used by recording paths. */
    fun isInstalled(context: Context): Boolean {
        if (installState != InstallState.READY) return false
        if (readVersionMarker(context) != modelFingerprint) return false
        return hasExpectedFiles(context)
    }

    /** Full content validation; called during legacy migration and bundled install. */
    fun validateInstalled(context: Context): Boolean = FILES.all {
        val file = File(modelDirectory(context), it.name)
        file.isFile && file.length() == it.sizeBytes && sha256(file) == it.sha256
    }

    /** Reads only the four required direct children of the selected SAF folder. */
    @Synchronized
    fun copyFromSelectedFolder(context: Context, treeUri: Uri): CopyResult {
        val folder = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (_: SecurityException) {
            return CopyResult.UNREADABLE
        } ?: return CopyResult.UNREADABLE

        val selected = FILES.associateBy { it.name }
        val files = try {
            folder.listFiles()
                .filter { it.isFile && selected.containsKey(it.name) }
                .associateBy { it.name }
        } catch (_: SecurityException) {
            return CopyResult.UNREADABLE
        }
        if (files.size != FILES.size) return CopyResult.MISSING

        val previousState = installState
        installState = InstallState.INSTALLING
        val result = installFromSources(
            context = context,
            sourceLength = { expected -> files[expected.name]?.length() },
            openSource = { expected ->
                files[expected.name]?.let { context.contentResolver.openInputStream(it.uri) }
            },
        )
        installState = when (result) {
            CopyResult.SUCCESS -> InstallState.READY
            else -> previousState.takeIf { it == InstallState.READY || it == InstallState.OUTDATED }
                ?: InstallState.MISSING
        }
        return result
    }

    private fun installBundledModel(context: Context): CopyResult = installFromSources(
        context = context,
        sourceLength = { null },
        openSource = { expected -> context.assets.open(expected.name) },
    )

    /** Copy, validate, and atomically swap a complete set of model files. */
    private fun installFromSources(
        context: Context,
        sourceLength: (ModelFile) -> Long?,
        openSource: (ModelFile) -> InputStream?,
    ): CopyResult {
        val root = modelRoot(context).also { it.mkdirs() }
        val stage = File(root, STAGING_DIR)
        val destination = File(root, INSTALL_DIR)
        val backup = File(root, BACKUP_DIR)

        if (stage.exists() && !stage.deleteRecursively()) return CopyResult.INVALID
        if (!stage.mkdirs()) return CopyResult.INVALID

        try {
            for (expected in FILES) {
                val incomingLength = sourceLength(expected)
                if (incomingLength != null && incomingLength != expected.sizeBytes) {
                    return CopyResult.INVALID
                }

                val input = try {
                    openSource(expected)
                } catch (_: SecurityException) {
                    return CopyResult.UNREADABLE
                } catch (_: Exception) {
                    return CopyResult.UNREADABLE
                } ?: return CopyResult.UNREADABLE

                val stagedFile = File(stage, expected.name)
                input.use { source ->
                    stagedFile.outputStream().buffered(COPY_BUFFER_SIZE).use { output ->
                        source.copyTo(output, COPY_BUFFER_SIZE)
                    }
                }
                if (!stagedFile.isFile || stagedFile.length() != expected.sizeBytes ||
                    sha256(stagedFile) != expected.sha256
                ) {
                    return CopyResult.INVALID
                }
            }

            writeVersionMarker(stage)
            if (!commitStagedDirectory(stage, destination, backup)) return CopyResult.INVALID
            return CopyResult.SUCCESS
        } catch (_: SecurityException) {
            return CopyResult.UNREADABLE
        } catch (_: Exception) {
            return CopyResult.INVALID
        } finally {
            if (stage.exists()) stage.deleteRecursively()
        }
    }

    private fun commitStagedDirectory(stage: File, destination: File, backup: File): Boolean {
        if (backup.exists() && !backup.deleteRecursively()) return false

        val hadPrevious = destination.exists()
        if (hadPrevious && !destination.renameTo(backup)) return false

        if (!stage.renameTo(destination)) {
            if (hadPrevious && backup.exists()) backup.renameTo(destination)
            return false
        }

        if (backup.exists()) backup.deleteRecursively()
        return true
    }

    private fun recoverInterruptedSwap(context: Context) {
        val root = modelRoot(context)
        val destination = File(root, INSTALL_DIR)
        val backup = File(root, BACKUP_DIR)
        if (!destination.exists() && backup.exists()) {
            backup.renameTo(destination)
        }
    }

    private fun cleanupAbandonedDirectories(context: Context) {
        val root = modelRoot(context)
        val destination = File(root, INSTALL_DIR)
        File(root, STAGING_DIR).deleteRecursively()
        if (destination.exists()) File(root, BACKUP_DIR).deleteRecursively()
    }

    private fun hasExpectedFiles(context: Context): Boolean = FILES.all {
        val file = File(modelDirectory(context), it.name)
        file.isFile && file.length() == it.sizeBytes
    }

    private fun modelRoot(context: Context): File = File(context.filesDir, "models")

    private fun modelDirectory(context: Context): File = File(modelRoot(context), INSTALL_DIR)

    private fun markerFile(directory: File): File = File(directory, VERSION_MARKER)

    private fun readVersionMarker(context: Context): String? = try {
        markerFile(modelDirectory(context)).takeIf { it.isFile }?.readText()?.trim()
    } catch (_: Exception) {
        null
    }

    private fun writeVersionMarker(directory: File) {
        markerFile(directory).writeText(modelFingerprint)
    }

    private fun report(onDiagnostic: (String) -> Unit, message: String) {
        Log.w(TAG, message)
        runCatching { onDiagnostic(message) }
    }

    private fun sha256(file: File): String = file.inputStream().use(::sha256)

    private fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(COPY_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
