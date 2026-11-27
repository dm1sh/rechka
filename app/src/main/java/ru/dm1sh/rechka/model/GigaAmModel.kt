package ru.dm1sh.rechka.model

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import ru.dm1sh.rechka.BuildConfig
import java.io.File
import kotlin.concurrent.thread

/** Phone Whisper's full-context GigaAM v4 E2E RNN-T conversion for sherpa-onnx. */
object GigaAmModel {
    const val ENCODER = "gigaam_v4_e2e_rnnt_encoder_int8.onnx"
    const val DECODER = "gigaam_v4_e2e_rnnt_decoder.onnx"
    const val JOINER = "gigaam_v4_e2e_rnnt_joint.onnx"
    const val TOKENS = "gigaam_v4_e2e_rnnt_tokens.txt"

    enum class InstallState { CHECKING, INSTALLING, READY, MISSING, OUTDATED, FAILED }
    enum class CopyResult { SUCCESS, MISSING, INVALID, UNREADABLE }

    private const val TAG = "GigaAmModel"
    private const val INSTALL_DIR = "gigaam-v4-e2e-rnnt"
    private const val STAGING_DIR = ".gigaam-v4-e2e-rnnt.new"
    private const val BACKUP_DIR = ".gigaam-v4-e2e-rnnt.old"
    private val legacyDirectories = listOf(
        "gigaam-v3-e2e-rnnt", ".gigaam-v3-e2e-rnnt.new", ".gigaam-v3-e2e-rnnt.old"
    )

    // Pinned to https://github.com/ivkiwi/phone-whisper/releases/tag/models-gigaam-v4
    val FILES = listOf(
        ModelFile(ENCODER, 239_885_452L, "55a182f536b0c61eb1bb67214600967858ba4646fb68fe3bcb70549029a65ad8"),
        ModelFile(DECODER, 4_600_129L, "f665598c4e271dfe1641106751bf895dc02517c4ff98a35a2c2a7f2b6b990e17"),
        ModelFile(JOINER, 2_712_897L, "0d7888efb5cc6a07d04c4bbe9a5fe87951d16abf687bc11fdfb2046cae753228"),
        ModelFile(TOKENS, 9_982L, "4af4f59269ff664ea6a55838b12fa0cb5e07bd5e019202b4d38b3fbc9275e923"),
    )

    @Volatile
    var installState: InstallState = InstallState.CHECKING
        private set

    val isBundledVariant: Boolean get() = BuildConfig.BUNDLED_MODEL

    @Volatile private var initializationStarted = false
    @Volatile private var bundledInstallRunning = false
    @Volatile private var fileInstaller: ModelFileInstaller? = null
    private val stateLock = Any()
    private val modelFingerprint by lazy(LazyThreadSafetyMode.PUBLICATION) {
        ModelFileInstaller.fingerprint(FILES)
    }

    private fun installer(context: Context): ModelFileInstaller = fileInstaller ?: synchronized(this) {
        fileInstaller ?: ModelFileInstaller(
            root = File(context.applicationContext.filesDir, "models"),
            files = FILES,
            installDir = INSTALL_DIR,
            stagingDir = STAGING_DIR,
            backupDir = BACKUP_DIR,
            marker = modelFingerprint,
        ).also { fileInstaller = it }
    }

    /** The full APK installs its verified assets; the light APK needs a SAF folder. */
    @Synchronized
    fun initialize(context: Context, onDiagnostic: (String) -> Unit = {}) {
        if (initializationStarted) return
        initializationStarted = true
        val appContext = context.applicationContext
        val files = installer(appContext)
        files.recoverInterruptedSwap()

        if (isBundledVariant) {
            startBundledInstall(appContext, onDiagnostic)
            return
        }

        val marker = files.readMarker()
        when {
            marker == modelFingerprint && files.hasExpectedFiles() -> {
                installState = InstallState.READY
                thread(name = "model-cleanup", isDaemon = true) {
                    cleanupAfterSuccess(appContext)
                }
            }
            marker != null -> {
                installState = if (marker != modelFingerprint || hasLegacyModel(appContext)) {
                    InstallState.OUTDATED
                } else {
                    InstallState.MISSING
                }
            }
            files.hasExpectedFiles() -> {
                // Migration for an unmarked v4 installation: hash before trusting it.
                installState = InstallState.CHECKING
                thread(name = "model-validation", isDaemon = true, priority = Thread.NORM_PRIORITY - 1) {
                    try {
                        if (files.validateInstalled()) {
                            files.writeInstalledMarker()
                            installState = InstallState.READY
                            cleanupAfterSuccess(appContext)
                        } else {
                            installState = if (hasLegacyModel(appContext)) InstallState.OUTDATED else InstallState.MISSING
                            report(onDiagnostic, "Installed v4 model is invalid")
                        }
                    } catch (t: Throwable) {
                        installState = if (hasLegacyModel(appContext)) InstallState.OUTDATED else InstallState.MISSING
                        report(onDiagnostic, "Model validation failed: ${t.message}")
                    }
                }
            }
            hasLegacyModel(appContext) -> installState = InstallState.OUTDATED
            else -> installState = InstallState.MISSING
        }
    }

    /** Retry the automatic full-APK install after an error, without restarting setup. */
    fun retryBundledInstall(context: Context, onDiagnostic: (String) -> Unit = {}) {
        if (!isBundledVariant || installState != InstallState.FAILED) return
        startBundledInstall(context.applicationContext, onDiagnostic)
    }

    private fun startBundledInstall(appContext: Context, onDiagnostic: (String) -> Unit) {
        synchronized(this) {
            if (bundledInstallRunning) return
            bundledInstallRunning = true
            synchronized(stateLock) { installState = InstallState.INSTALLING }
        }
        thread(name = "bundled-model-install", isDaemon = true, priority = Thread.NORM_PRIORITY - 1) {
            try {
                val files = installer(appContext)
                val alreadyVerified = files.readMarker() == modelFingerprint && files.hasExpectedFiles()
                if (alreadyVerified || (files.hasExpectedFiles() && files.validateInstalled())) {
                    if (!alreadyVerified) files.writeInstalledMarker()
                    installState = InstallState.READY
                    cleanupAfterSuccess(appContext)
                    return@thread
                }
                val result = files.install(
                    sourceLength = { null },
                    openSource = { expected -> appContext.assets.open(expected.name) },
                )
                if (result == ModelFileInstaller.Result.SUCCESS) {
                    installState = InstallState.READY
                    cleanupAfterSuccess(appContext)
                } else {
                    installState = InstallState.FAILED
                    report(onDiagnostic, "Bundled model install failed: $result")
                }
            } catch (t: Throwable) {
                installState = InstallState.FAILED
                report(onDiagnostic, "Bundled model install failed: ${t.message}")
            } finally {
                bundledInstallRunning = false
            }
        }
    }

    /** Filesystem paths remain the sherpa-onnx source for both APK variants. */
    fun modelDir(context: Context): File = installer(context).directory.also { it.mkdirs() }

    /** Fast, nonblocking check: do not wait for a 247 MB copy on the UI thread. */
    fun isInstalled(context: Context): Boolean {
        if (installState != InstallState.READY) return false
        val directory = File(File(context.applicationContext.filesDir, "models"), INSTALL_DIR)
        val marker = try {
            File(directory, ".model-version").readText().trim()
        } catch (_: Exception) {
            null
        }
        val valid = marker == modelFingerprint && FILES.all {
            File(directory, it.name).let { file -> file.isFile && file.length() == it.sizeBytes }
        }
        if (!valid) synchronized(stateLock) {
            // A replacement may have started while the files were inspected.
            if (installState == InstallState.READY) {
                installState = if (isBundledVariant) InstallState.FAILED else InstallState.MISSING
            }
        }
        return valid && installState == InstallState.READY
    }

    /** Full content validation, rather than the fast marker/size check. */
    fun validateInstalled(context: Context): Boolean = installer(context).validateInstalled()

    /** Reads only the four required direct children of the selected SAF folder. */
    @Synchronized
    fun copyFromSelectedFolder(context: Context, treeUri: Uri): CopyResult {
        val folder = try {
            DocumentFile.fromTreeUri(context, treeUri)
        } catch (_: Exception) {
            return CopyResult.UNREADABLE
        } ?: return CopyResult.UNREADABLE

        val selected = FILES.associateBy { it.name }
        val sources = try {
            folder.listFiles()
                .filter { it.isFile && selected.containsKey(it.name) }
                .associateBy { it.name }
        } catch (_: Exception) {
            return CopyResult.UNREADABLE
        }
        if (sources.size != FILES.size) return CopyResult.MISSING

        val previousState = synchronized(stateLock) {
            installState.also { installState = InstallState.INSTALLING }
        }
        val result = installer(context).install(
            sourceLength = { expected -> sources[expected.name]?.length() },
            openSource = { expected ->
                sources[expected.name]?.let { context.contentResolver.openInputStream(it.uri) }
            },
        )
        val copyResult = CopyResult.valueOf(result.name)
        installState = when (copyResult) {
            CopyResult.SUCCESS -> InstallState.READY
            else -> previousState.takeIf { it == InstallState.READY || it == InstallState.OUTDATED }
                ?: InstallState.MISSING
        }
        if (copyResult == CopyResult.SUCCESS) cleanupAfterSuccess(context)
        return copyResult
    }

    private fun hasLegacyModel(context: Context): Boolean {
        val root = File(context.filesDir, "models")
        return legacyDirectories.any { File(root, it).exists() }
    }

    /** Never delete v3 until v4 is fully copied, hashed and committed. */
    private fun cleanupAfterSuccess(context: Context) {
        val files = installer(context)
        files.cleanupAbandonedDirectories()
        if (files.readMarker() != modelFingerprint || !files.hasExpectedFiles()) return
        val root = File(context.filesDir, "models")
        legacyDirectories.forEach { File(root, it).deleteRecursively() }
    }

    private fun report(onDiagnostic: (String) -> Unit, message: String) {
        Log.w(TAG, message)
        runCatching { onDiagnostic(message) }
    }
}
