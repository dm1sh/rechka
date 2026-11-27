package ru.dm1sh.rechka.ui.onboarding

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.dm1sh.rechka.R
import ru.dm1sh.rechka.model.GigaAmModel

/**
 * Model step. The light APK installs the model from a user-selected SAF
 * folder; the full APK installs the model bundled in its assets, which
 * LiteApp starts automatically on first launch after (re)installation.
 */
class ModelFragment : OnboardingStepFragment() {
    private lateinit var status: MaterialTextView
    private lateinit var downloadButton: MaterialButton
    private lateinit var chooseButton: MaterialButton
    private var filesView: View? = null
    private var warningView: View? = null
    private var statePolling: Job? = null

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        chooseButton.isEnabled = false
        status.setText(R.string.onb_model_copying)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                GigaAmModel.copyFromSelectedFolder(requireContext(), uri)
            }
            showResult(result)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_onboarding_model, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        status = view.findViewById(R.id.modelStatus)
        downloadButton = view.findViewById(R.id.modelDownload)
        chooseButton = view.findViewById(R.id.modelChoose)
        filesView = view.findViewById(R.id.modelFiles)
        warningView = view.findViewById(R.id.modelFolderWarning)
        downloadButton.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MODEL_RELEASE_URL)))
        }
        chooseButton.setOnClickListener {
            if (GigaAmModel.isBundledVariant &&
                GigaAmModel.installState == GigaAmModel.InstallState.FAILED
            ) {
                GigaAmModel.retryBundledInstall(requireContext())
                refreshState()
            } else {
                folderPicker.launch(null)
            }
        }
        refreshState()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshState()
    }

    override fun onStepFocused() {
        if (::status.isInitialized) refreshState()
    }

    private fun refreshState() {
        val bundled = GigaAmModel.isBundledVariant
        // Detect a removed/corrupt installation before displaying READY.
        GigaAmModel.isInstalled(requireContext())
        chooseButton.setText(R.string.onb_model_choose)
        // Manual installation is a light-APK flow; the full APK only offers
        // a retry button if its automatic bundled install failed.
        downloadButton.visibility = if (bundled) View.GONE else View.VISIBLE
        filesView?.visibility = if (bundled) View.GONE else View.VISIBLE
        warningView?.visibility = if (bundled) View.GONE else View.VISIBLE

        when (GigaAmModel.installState) {
            GigaAmModel.InstallState.READY -> {
                chooseButton.visibility = if (bundled) View.GONE else View.VISIBLE
                chooseButton.isEnabled = true
                status.setText(R.string.onb_model_ready)
                setStepComplete(true)
            }
            GigaAmModel.InstallState.CHECKING -> {
                chooseButton.visibility = View.GONE
                status.setText(R.string.onb_model_checking)
                setStepComplete(false)
                pollState()
            }
            GigaAmModel.InstallState.INSTALLING -> {
                chooseButton.visibility = View.GONE
                status.setText(
                    if (bundled) R.string.onb_model_bundled_installing
                    else R.string.onb_model_copying
                )
                setStepComplete(false)
                pollState()
            }
            GigaAmModel.InstallState.OUTDATED -> {
                chooseButton.visibility = if (bundled) View.GONE else View.VISIBLE
                chooseButton.isEnabled = true
                status.setText(R.string.onb_model_outdated)
                setStepComplete(false)
            }
            GigaAmModel.InstallState.FAILED -> {
                if (bundled) {
                    chooseButton.visibility = View.VISIBLE
                    chooseButton.isEnabled = true
                    chooseButton.setText(R.string.onb_model_retry)
                    status.setText(R.string.onb_model_bundled_failed)
                } else {
                    chooseButton.visibility = View.VISIBLE
                    status.setText(R.string.onb_model_invalid)
                }
                setStepComplete(false)
            }
            GigaAmModel.InstallState.MISSING -> {
                chooseButton.visibility = if (bundled) View.GONE else View.VISIBLE
                chooseButton.isEnabled = true
                status.setText(
                    if (bundled) R.string.onb_model_bundled_failed else R.string.onb_model_pending
                )
                setStepComplete(false)
            }
        }
    }

    /** Background install/validation runs in the Application; just watch it. */
    private fun pollState() {
        if (statePolling?.isActive == true) return
        statePolling = viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                delay(500L)
                val state = GigaAmModel.installState
                if (state != GigaAmModel.InstallState.CHECKING &&
                    state != GigaAmModel.InstallState.INSTALLING
                ) break
            }
            if (::status.isInitialized) refreshState()
        }
    }

    private fun showResult(result: GigaAmModel.CopyResult) {
        // Restore the button and completion state even if a failed replacement
        // left an already-installed, verified model intact.
        refreshState()
        when (result) {
            GigaAmModel.CopyResult.SUCCESS -> Unit
            GigaAmModel.CopyResult.MISSING -> status.setText(R.string.onb_model_missing)
            GigaAmModel.CopyResult.INVALID -> status.setText(R.string.onb_model_invalid)
            GigaAmModel.CopyResult.UNREADABLE -> status.setText(R.string.onb_model_unreadable)
        }
    }

    companion object {
        private const val MODEL_RELEASE_URL =
            "https://github.com/ivkiwi/phone-whisper/releases/tag/models-gigaam-v4"
    }
}
