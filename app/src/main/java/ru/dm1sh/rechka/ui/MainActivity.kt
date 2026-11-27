package ru.dm1sh.rechka.ui

import android.Manifest
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textview.MaterialTextView

import ru.dm1sh.rechka.R
import ru.dm1sh.rechka.model.GigaAmModel
import ru.dm1sh.rechka.stats.StatsStore
import ru.dm1sh.rechka.service.LiteAccessibilityService
import ru.dm1sh.rechka.util.AccessibilityHelper
import ru.dm1sh.rechka.util.Prefs

/**
 * Post-onboarding home screen.
 *
 *  - AppBar with overflow menu: Настройки / Pro-версия / О программе
 *  - Headline / subline adapt: ready / needs setup / just finished
 *  - Per-problem cards (mic / service / battery) show only when their specific
 *    check fails, each with a dedicated deep-link button
 *  - Last-try memento card surfaces the text from the onboarding demo
 *  - Shortcut advisory card warns about the small accessibility button
 *  - Stats card always visible (shows empty-state hint before first use)
 *  - Promo card links to FuturePlansActivity
 */
class MainActivity : AppCompatActivity() {

    private lateinit var headline: MaterialTextView
    private lateinit var subline: MaterialTextView

    private lateinit var cardModelMissing: MaterialCardView
    private lateinit var cardModelStatus: MaterialTextView
    private lateinit var cardModelButton: MaterialButton
    private lateinit var cardMicMissing: MaterialCardView
    private lateinit var cardMicButton: MaterialButton
    private lateinit var cardServiceMissing: MaterialCardView
    private lateinit var cardServiceButton: MaterialButton
    private lateinit var cardKeyboardMissing: MaterialCardView
    private lateinit var cardKeyboardButton: MaterialButton
    private lateinit var cardBatteryMissing: MaterialCardView
    private lateinit var cardBatteryButton: MaterialButton
    private lateinit var cardWhatsNew: MaterialCardView
    private lateinit var cardWhatsNewDismissButton: MaterialButton
    private lateinit var modeOverlayCheck: MaterialCheckBox
    private lateinit var modeKeyboardCheck: MaterialCheckBox
    private lateinit var modeFilesCheck: MaterialCheckBox

    private lateinit var shortcutCard: View
    private lateinit var shortcutCardButton: MaterialButton
    private lateinit var statsCard: View
    private lateinit var statsRow: View
    private lateinit var statsWordsNumber: MaterialTextView
    private lateinit var statsMinutesNumber: MaterialTextView
    private lateinit var statsMinutesLabel: MaterialTextView
    private lateinit var statsEmpty: MaterialTextView
    private lateinit var statsTodaySection: View
    private lateinit var statsTodayNumber: MaterialTextView
    private lateinit var statsTotalsCompact: MaterialTextView
    private lateinit var promoCard: View
    private lateinit var toolbar: MaterialToolbar
    private var showJustFinished: Boolean = false

    private val modelStateHandler = Handler(Looper.getMainLooper())
    private val modelStatePoll = object : Runnable {
        override fun run() {
            refreshStatuses()
            if (GigaAmModel.installState == GigaAmModel.InstallState.CHECKING ||
                GigaAmModel.installState == GigaAmModel.InstallState.INSTALLING
            ) modelStateHandler.postDelayed(this, 500L)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, R.string.onb_try_denied, Toast.LENGTH_LONG).show()
        refreshStatuses()
    }

    // Observes accessibility-related Secure settings so the per-problem cards
    // refresh the instant the user toggles the service OR taps the little
    // shortcut button on top of our bubble — no need to leave MainActivity.
    private val accessibilityObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refreshStatuses()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        DynamicColors.applyToActivityIfAvailable(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.topAppBar)
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_about -> {
                    startActivity(Intent(this, AboutActivity::class.java))
                    true
                }
                else -> false
            }
        }

        val scroll = findViewById<View>(R.id.scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(bottom = bars.bottom)
            insets
        }

        headline = findViewById(R.id.mainHeadline)
        subline = findViewById(R.id.mainSubline)

        cardModelMissing = findViewById(R.id.cardModelMissing)
        cardModelStatus = findViewById(R.id.cardModelStatus)
        cardModelButton = findViewById(R.id.cardModelButton)
        cardModelButton.setOnClickListener {
            startActivity(Intent(this, ModelSetupActivity::class.java))
        }

        cardMicMissing = findViewById(R.id.cardMicMissing)
        cardMicButton = findViewById(R.id.cardMicButton)
        cardMicButton.setOnClickListener { requestMicrophonePermission() }

        if (intent.getBooleanExtra(EXTRA_REQUEST_MIC, false)) {
            window.decorView.post { requestMicrophonePermission() }
            intent.removeExtra(EXTRA_REQUEST_MIC)
        }

        cardServiceMissing = findViewById(R.id.cardServiceMissing)
        cardServiceButton = findViewById(R.id.cardServiceButton)
        cardServiceButton.setOnClickListener {
            AccessibilityHelper.openAccessibilitySettings(this)
        }

        cardKeyboardMissing = findViewById(R.id.cardKeyboardMissing)
        cardKeyboardButton = findViewById(R.id.cardKeyboardButton)
        cardKeyboardButton.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } catch (_: Exception) { }
        }

        cardBatteryMissing = findViewById(R.id.cardBatteryMissing)
        cardBatteryButton = findViewById(R.id.cardBatteryButton)
        cardBatteryButton.setOnClickListener { requestIgnoreBatteryOptimizations() }

        modeOverlayCheck = findViewById(R.id.modeOverlayCheck)
        modeKeyboardCheck = findViewById(R.id.modeKeyboardCheck)
        modeFilesCheck = findViewById(R.id.modeFilesCheck)
        modeFilesCheck.isChecked = true
        modeFilesCheck.isEnabled = false
        modeOverlayCheck.setOnClickListener {
            val requested = modeOverlayCheck.isChecked
            setModeSelected(KEY_MODE_OVERLAY, requested)
            if (requested) {
                LiteAccessibilityService.instance?.setOverlayModeEnabled(true)
                if (!AccessibilityHelper.isLiteServiceEnabled(this)) {
                    AccessibilityHelper.openAccessibilitySettings(this)
                }
            } else {
                LiteAccessibilityService.instance?.setOverlayModeEnabled(false)
                LiteAccessibilityService.instance?.disableSelf()
                modeOverlayCheck.isChecked = false
                window.decorView.postDelayed({ refreshStatuses() }, 500)
            }
        }
        modeKeyboardCheck.setOnClickListener {
            setModeSelected(KEY_MODE_KEYBOARD, modeKeyboardCheck.isChecked)
            if (modeKeyboardCheck.isChecked && !isVoiceKeyboardEnabled()) {
                try { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } catch (_: Exception) { }
            }
            refreshStatuses()
        }

        cardWhatsNew = findViewById(R.id.cardWhatsNew)
        cardWhatsNewDismissButton = findViewById(R.id.cardWhatsNewDismissButton)
        cardWhatsNewDismissButton.setOnClickListener {
            Prefs.setWhatsNewHintDismissed(this, true)
            cardWhatsNew.visibility = View.GONE
        }

        shortcutCard = findViewById(R.id.shortcutCard)
        shortcutCardButton = findViewById(R.id.shortcutCardButton)
        shortcutCardButton.setOnClickListener {
            AccessibilityHelper.openAccessibilitySettings(this)
        }
        statsCard = findViewById(R.id.statsCard)
        statsRow = findViewById(R.id.statsRow)
        statsWordsNumber = findViewById(R.id.statsWordsNumber)
        statsMinutesNumber = findViewById(R.id.statsMinutesNumber)
        statsMinutesLabel = findViewById(R.id.statsMinutesLabel)
        statsEmpty = findViewById(R.id.statsEmpty)
        statsTodaySection = findViewById(R.id.statsTodaySection)
        statsTodayNumber = findViewById(R.id.statsTodayNumber)
        statsTotalsCompact = findViewById(R.id.statsTotalsCompact)
        // Long-press to reset the running counter. Hidden on purpose —
        // there's no visible button because the action is destructive
        // and rarely needed; the gesture + confirmation dialog is enough
        // for the few users who actually want a clean slate (e.g. after
        // the 1.0.9 counter-accuracy change made historical numbers a
        // mix of session-time and speech-time).
        statsCard.setOnLongClickListener {
            confirmStatsReset()
            true
        }

        promoCard = findViewById(R.id.promoCard)
        promoCard.setOnClickListener {
            startActivity(Intent(this, FuturePlansActivity::class.java))
        }

        showJustFinished = intent.getBooleanExtra(EXTRA_JUST_FINISHED, false)
        if (showJustFinished) {
            // Consume the flag so orientation changes / re-launches show the
            // standard headline.
            intent.removeExtra(EXTRA_JUST_FINISHED)
        }
    }

    override fun onResume() {
        super.onResume()
        modelStateHandler.removeCallbacks(modelStatePoll)
        modelStatePoll.run()
        refreshStats()
        val cr = contentResolver
        cr.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            false, accessibilityObserver,
        )
        cr.registerContentObserver(
            Settings.Secure.getUriFor("accessibility_button_targets"),
            false, accessibilityObserver,
        )
        cr.registerContentObserver(
            Settings.Secure.getUriFor("accessibility_shortcut_target_service"),
            false, accessibilityObserver,
        )
        cr.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_INPUT_METHODS),
            false, accessibilityObserver,
        )
    }

    override fun onPause() {
        modelStateHandler.removeCallbacks(modelStatePoll)
        contentResolver.unregisterContentObserver(accessibilityObserver)
        super.onPause()
    }

    private fun refreshStats() {
        val words = StatsStore.getWords(this)
        val voiceSeconds = StatsStore.getSeconds(this)
        val voiceMinutes = voiceSeconds / 60L

        if (words <= 0L) {
            statsTodaySection.visibility = View.GONE
            statsRow.visibility = View.GONE
            statsTotalsCompact.visibility = View.GONE
            statsEmpty.visibility = View.VISIBLE
            return
        }
        statsEmpty.visibility = View.GONE

        val wordsToday = StatsStore.getWordsToday(this)
        // Always show today as the hero — even 0 is valid (new day, haven't spoken yet).
        statsTodayNumber.text = formatThousands(wordsToday)
        statsTodaySection.visibility = View.VISIBLE
        statsRow.visibility = View.GONE
        statsTotalsCompact.text = getString(
            R.string.main_stats_totals_compact_fmt,
            formatThousands(words),
            formatThousands(voiceMinutes),
        )
        statsTotalsCompact.visibility = View.VISIBLE
    }

    // Russian plural rule branches on last-digit AND last-two-digits, both
    // well-defined for any Long. Cast to Int is safe because we only need
    // the low-order digits to select the bucket — the formatted number is
    // passed separately as a string.
    private fun Long.toPluralSelector(): Int =
        (this % 100L).toInt().let { if (it < 0) -it else it }

    /**
     * Long-press on the stats card → confirm + wipe all counters. Destructive
     * but trivial to recover from (counters just start at zero again), so a
     * single confirm dialog is enough — we don't need a multi-step flow.
     * The Reset button is tinted colorError to match the existing pattern
     * used by «Выключить Речку» in Settings.
     */
    private fun confirmStatsReset() {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.stats_reset_title)
            .setMessage(R.string.stats_reset_message)
            .setPositiveButton(R.string.stats_reset_action) { _, _ ->
                StatsStore.reset(this)
                refreshStats()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setTextColor(
            MaterialColors.getColor(statsCard, com.google.android.material.R.attr.colorError)
        )
    }

    private fun formatThousands(value: Long): String {
        // Use a non-breaking space as the thousands separator so "1 234" never
        // wraps mid-number in the narrow two-column layout.
        return if (value < 1000L) value.toString()
        else value.toString().reversed().chunked(3).joinToString("\u00A0").reversed()
    }

    private fun refreshStatuses() {
        val modelOk = GigaAmModel.isInstalled(this)
        val modelState = GigaAmModel.installState
        val modelBusy = modelState == GigaAmModel.InstallState.CHECKING ||
            modelState == GigaAmModel.InstallState.INSTALLING
        cardModelMissing.visibility = if (modelOk) View.GONE else View.VISIBLE
        cardModelButton.visibility = if (modelBusy) View.GONE else View.VISIBLE
        cardModelStatus.setText(when (modelState) {
            GigaAmModel.InstallState.CHECKING, GigaAmModel.InstallState.INSTALLING -> R.string.main_card_model_loading
            GigaAmModel.InstallState.OUTDATED -> R.string.main_card_model_outdated
            GigaAmModel.InstallState.FAILED -> R.string.main_card_model_failed
            else -> R.string.main_card_model_missing
        })

        val micOk = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val serviceOk = AccessibilityHelper.isLiteServiceEnabled(this)
        val keyboardOk = isVoiceKeyboardEnabled()
        val batteryOk = run {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(packageName) == true
        }
        // The accessibility shortcut places an extra small floating button next
        // to our bubble that disables the service when tapped — count it as a
        // setup problem so the headline and promo card honestly reflect state.
        val shortcutOn = AccessibilityHelper.isLiteShortcutEnabled(this)
        val overlayRequested = isModeSelected(KEY_MODE_OVERLAY)
        val keyboardSelected = isModeSelected(KEY_MODE_KEYBOARD)
        // All input modes, including files, require an installed model.
        val criticalOk = modelOk && (!overlayRequested && !keyboardSelected ||
            micOk && ((keyboardSelected && keyboardOk) || (overlayRequested && serviceOk && !shortcutOn)))

        // Only surface the shortcut card when the service is on — otherwise
        // the user is still in "enable me first" mode and the extra advisory
        // would be noise.
        shortcutCard.visibility = if (overlayRequested && serviceOk && shortcutOn) View.VISIBLE else View.GONE

        when {
            showJustFinished && criticalOk -> {
                headline.setText(R.string.main_just_finished)
                subline.setText(R.string.main_just_finished_sub)
            }
            criticalOk -> {
                headline.setText(R.string.main_ready)
                subline.setText(R.string.main_hint)
            }
            else -> {
                headline.setText(R.string.main_needs_setup)
                subline.setText(R.string.main_needs_setup_sub)
            }
        }

        // The checkbox represents the user's selected method, not whether
        // Android has completed its setup. Keep it checked while the
        // accessibility service is still missing so the warning and method
        // selection cannot contradict each other.
        modeOverlayCheck.isChecked = overlayRequested
        modeKeyboardCheck.isChecked = keyboardSelected
        modeFilesCheck.isChecked = true

        // Permission cards belong above the mode/status and statistics widgets,
        // and only appear for modes the user selected.
        cardMicMissing.visibility = if (micOk || (!overlayRequested && !keyboardSelected)) View.GONE else View.VISIBLE
        cardServiceMissing.visibility = if (!overlayRequested || serviceOk) View.GONE else View.VISIBLE
        cardKeyboardMissing.visibility = if (!keyboardSelected || keyboardOk) View.GONE else View.VISIBLE
        // Battery card is independent of criticalOk — it co-exists with stats
        // and promo as a soft "recommended" hint, not a setup blocker.
        cardBatteryMissing.visibility = if (overlayRequested && serviceOk && !batteryOk) View.VISIBLE else View.GONE

        // "What's new" FYI card — gated only on critical readiness + dismissal.
        // A user without battery exemption can still see new-feature highlights;
        // they're set up enough to enjoy the app.
        val showWhatsNew = criticalOk && !Prefs.isWhatsNewHintDismissed(this)
        cardWhatsNew.visibility = if (showWhatsNew) View.VISIBLE else View.GONE
        // Stats and promo hide only while there are *critical* problems —
        // the screen shouldn't split attention between "fix this NOW" cards
        // and nice-to-have surfaces. Battery being unset doesn't qualify.
        statsCard.visibility = View.VISIBLE
        promoCard.visibility = View.VISIBLE
    }

    private fun isModeSelected(key: String): Boolean =
        getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(key, false)

    private fun setModeSelected(key: String, selected: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(key, selected).apply()
    }

    private fun isVoiceKeyboardEnabled(): Boolean {
        val imm = getSystemService(InputMethodManager::class.java) ?: return false
        val serviceName = ru.dm1sh.rechka.service.RechkaInputMethodService::class.java.name
        return imm.enabledInputMethodList.any { info ->
            // Compare the parsed component fields instead of only InputMethodInfo.id.
            // Some Android builds report the same enabled IME with a normalized
            // component string, which made the old exact-id check return false.
            info.packageName == packageName && info.serviceName == serviceName
        }
    }

    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            refreshStatuses()
            return
        }
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun openAppDetails() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
        }
    }

    @SuppressWarnings("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.fromParts("package", packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // Fallback to plain battery settings if the direct dialog isn't handled.
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val EXTRA_JUST_FINISHED = "just_finished"
        const val EXTRA_REQUEST_MIC = "request_microphone"
        private const val PREFS = "rechka_lite_prefs"
        private const val KEY_MODE_OVERLAY = "onboarding_mode_overlay"
        private const val KEY_MODE_KEYBOARD = "onboarding_mode_keyboard"
    }
}
