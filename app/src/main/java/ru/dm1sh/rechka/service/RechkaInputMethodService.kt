package ru.dm1sh.rechka.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.inputmethodservice.InputMethodService
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import ru.dm1sh.rechka.overlay.BubbleView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import ru.dm1sh.rechka.R
import ru.dm1sh.rechka.model.GigaAmModel
import ru.dm1sh.rechka.stats.StatsStore
import ru.dm1sh.rechka.transcriber.Dictionary
import ru.dm1sh.rechka.transcriber.OfflineTranscriber
import ru.dm1sh.rechka.ui.MainActivity
import ru.dm1sh.rechka.util.Prefs
import ru.dm1sh.rechka.util.Haptics
import ru.dm1sh.rechka.transcriber.VadRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Voice-first auxiliary IME with dictation and lightweight editing controls. */
class RechkaInputMethodService : InputMethodService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var recorder: VadRecorder? = null
    private var recording = false
    private var processing = false
    private val touchHandler = Handler(Looper.getMainLooper())
    private var walkieLongPressTriggered = false
    private var walkieLocked = false
    private var walkieDownRawY = 0f
    private val walkieStartRunnable = Runnable {
        if (!recording && walkieLongPressTriggered) startRecording()
    }

    private lateinit var recordButton: BubbleView
    private lateinit var themedContext: Context
    private var currentEditorInfo: EditorInfo? = null

    private lateinit var undoButton: MaterialButton
    private val sessionText = StringBuilder()

    override fun onCreateInputView(): View {
        // InputMethodService does not inherit the Activity theme. Wrapping the
        // context is required for MaterialButton's theme enforcement and also
        // lets Material resolve the device's Monet/dynamic colors.
        themedContext = DynamicColors.wrapContextIfAvailable(
            ContextThemeWrapper(this, R.style.Theme_RechkaIme)
        )
        val root = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(
                themedContext,
                com.google.android.material.R.attr.colorSurface,
                Color.TRANSPARENT,
            ))
            setPadding(dp(16), dp(8), dp(16), dp(16))
        }

        val topRow = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.START
        }
        val exitButton = makeIconButton(
            R.drawable.ic_arrow_back_24,
            R.string.ime_exit,
        ) { exitToPreviousInputMethod() }
        topRow.addView(exitButton, LinearLayout.LayoutParams(dp(56), dp(48)))
        topRow.addView(LinearLayout(themedContext), LinearLayout.LayoutParams(0, 1, 1f))
        undoButton = makeIconButton(R.drawable.ic_undo_24, R.string.ime_undo) {
            undoLastEdit()
        }
        updateUndoButton()
        topRow.addView(undoButton, LinearLayout.LayoutParams(dp(56), dp(48)))
        val redoButton = makeIconButton(R.drawable.ic_redo_24, R.string.ime_redo) {
            redoLastEdit()
        }
        topRow.addView(redoButton, LinearLayout.LayoutParams(dp(56), dp(48)))
        root.addView(topRow, LinearLayout.LayoutParams(-1, -2))

        // Fixed order: clear, whole-word backspace, start/stop, character
        // backspace, newline.
        val controls = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            // Four flexible edit buttons plus one fixed circular record button.
            weightSum = 4f
        }
        val clearButton = makeIconButton(R.drawable.ic_clear_all_24, R.string.ime_remove_session) {
            removeSessionText()
        }
        configureClearTouch(clearButton)
        controls.addView(clearButton, weightedButtonParams())
        val wordBackspace = makeIconButton(R.drawable.ic_backspace_word_24, R.string.ime_backspace_word) {
            deletePreviousWord()
        }
        configureDeletionTouch(wordBackspace, wholeWord = true)
        controls.addView(wordBackspace, weightedButtonParams())
        recordButton = makeRecordButton()
        configureRecordTouch()
        controls.addView(recordButton, recordButtonParams())
        val backspace = makeIconButton(R.drawable.ic_backspace_24, R.string.ime_backspace) {
            deletePreviousCodePoint()
        }
        configureDeletionTouch(backspace, wholeWord = false)
        controls.addView(backspace, weightedButtonParams())
        val enterButton = makeIconButton(R.drawable.ic_keyboard_return_24, R.string.ime_newline) {
            if (!performEditorActionIfSupported()) commitInserted("\n")
            Haptics.tap(this)
        }
        // Long-press is intentionally different from a tap: it always inserts
        // a literal newline, even for search/go/done editor actions.
        enterButton.setOnLongClickListener {
            commitInserted("\n")
            Haptics.tap(this)
            true
        }
        controls.addView(enterButton, weightedButtonParams())
        root.addView(controls, LinearLayout.LayoutParams(-1, dp(96)))

        // Keep the entire system hide/switcher touch target below our content.
        // Android 17 can place those controls over the bottom of an IME view.
        root.setOnApplyWindowInsetsListener { view, insets ->
            val nav = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
            // The inset is exactly the system-owned touch area. Do not add
            // an extra fixed spacer: it makes the voice panel unnecessarily tall.
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, maxOf(nav, ime) + dp(12))
            insets
        }
        root.requestApplyInsets()
        return root
    }

    private fun makeIconButton(iconRes: Int, descriptionRes: Int, action: () -> Unit): MaterialButton =
        MaterialButton(themedContext).apply {
            contentDescription = getString(descriptionRes)
            tooltipText = getString(descriptionRes)
            icon = ContextCompat.getDrawable(themedContext, iconRes)
            iconTint = ColorStateList.valueOf(onSurfaceVariantColor())
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            iconSize = dp(28)
            gravity = android.view.Gravity.CENTER
            text = ""
            minWidth = 0
            minHeight = 0
            insetTop = 0
            insetBottom = 0
            elevation = 0f
            strokeWidth = 0
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            setOnClickListener { action() }
        }

    private fun makeRecordButton(): BubbleView = BubbleView(themedContext).apply {
        contentDescription = getString(R.string.ime_start)
        tooltipText = getString(R.string.ime_start)
        setIdlePulse(false)
    }

    private fun updateUndoButton() {
        if (!::undoButton.isInitialized) return
        // Availability is controlled by the target editor's own undo stack.
        undoButton.isEnabled = true
        undoButton.alpha = 1f
    }

    private fun undoLastEdit() {
        if (currentInputConnection?.performContextMenuAction(android.R.id.undo) == true) {
            Haptics.tap(this)
        }
    }

    private fun redoLastEdit() {
        if (currentInputConnection?.performContextMenuAction(android.R.id.redo) == true) {
            Haptics.tap(this)
        }
    }

    private fun configureClearTouch(button: MaterialButton) {
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            clearEntireField()
            Haptics.tap(this)
        }
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longPressed = false
                    touchHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchHandler.removeCallbacks(longPress)
                    if (!longPressed && event.actionMasked == MotionEvent.ACTION_UP) {
                        removeSessionText()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun clearEntireField() {
        val connection = currentInputConnection ?: return
        val extracted = extractedText() ?: return
        val text = extracted.text ?: return
        val start = extracted.startOffset
        val end = start + text.length
        connection.setSelection(start, end)
        connection.commitText("", 1)
        sessionText.clear()
    }

    private fun deleteSelectedTextIfAny(): Boolean {
        val connection = currentInputConnection ?: return false
        val selected = connection.getSelectedText(0)?.toString() ?: return false
        if (selected.isEmpty()) return false
        connection.commitText("", 1)
        return true
    }

    private fun configureDeletionTouch(button: MaterialButton, wholeWord: Boolean) {
        var selecting = false
        var anchor = 0
        var target = 0
        var lastDirection = 0
        var repeatStarted = false
        var downRawX = 0f
        var lastRawX = 0f
        var accumulatedPixels = 0f
        var continuationActive = false
        var continuationDirection = 0
        val edgeZone = dp(64)
        val screenWidth = resources.displayMetrics.widthPixels
        val selectThreshold = dp(24)
        val stepPixels = if (wholeWord) dp(48).toFloat() else dp(24).toFloat()

        val repeat = object : Runnable {
            override fun run() {
                if (repeatStarted && !selecting) {
                    if (wholeWord) deletePreviousWord() else deletePreviousCodePoint()
                    touchHandler.postDelayed(this, if (wholeWord) 220L else 100L)
                }
            }
        }
        val edgeRepeat = object : Runnable {
            override fun run() {
                if (!selecting || lastDirection == 0) return
                val text = extractedText()?.text ?: return
                val proposed = moveSelectionTarget(text, target, lastDirection, wholeWord)
                val next = when {
                    lastDirection < 0 && target > anchor -> maxOf(anchor, proposed)
                    lastDirection > 0 && target < anchor -> minOf(anchor, proposed)
                    else -> proposed
                }
                if (next != target) {
                    target = next
                    setSelection(anchor, target)
                    // Continuous whole-word movement is deliberately slower
                    // than normal finger movement so a word is not skipped
                    // before the user can reverse direction.
                    touchHandler.postDelayed(this, if (wholeWord) 900L else 220L)
                }
            }
        }
        lateinit var longPress: Runnable
        longPress = Runnable {
            if (!selecting && Prefs.isImeRepeatDeleteEnabled(this)) {
                repeatStarted = true
                if (wholeWord) deletePreviousWord() else deletePreviousCodePoint()
                touchHandler.postDelayed(repeat, if (wholeWord) 300L else 260L)
            }
        }

        fun stopRunnables() {
            repeatStarted = false
            touchHandler.removeCallbacks(longPress)
            touchHandler.removeCallbacks(repeat)
            touchHandler.removeCallbacks(edgeRepeat)
        }

        fun selectionStepPixels(
            text: CharSequence,
            from: Int,
            direction: Int,
            wholeWord: Boolean,
        ): Float {
            if (!wholeWord) return dp(24).toFloat()
            var p = from.coerceIn(0, text.length)
            if (direction < 0) {
                while (p > 0 && text[p - 1].isWhitespace()) p--
                val end = p
                while (p > 0 && !text[p - 1].isWhitespace()) p--
                val chars = (end - p).coerceAtLeast(1)
                return maxOf(dp(42).toFloat(), chars * dp(11).toFloat())
            }
            while (p < text.length && text[p].isWhitespace()) p++
            val start = p
            while (p < text.length && !text[p].isWhitespace()) p++
            val chars = (p - start).coerceAtLeast(1)
            return maxOf(dp(42).toFloat(), chars * dp(11).toFloat())
        }

        fun moveByPixels(delta: Float) {
            if (delta != 0f) lastDirection = if (delta < 0f) -1 else 1
            accumulatedPixels += delta
            while (kotlin.math.abs(accumulatedPixels) >= selectionStepPixels(
                    extractedText()?.text ?: return,
                    target,
                    if (accumulatedPixels < 0f) -1 else 1,
                    wholeWord,
                )
            ) {
                val text = extractedText()?.text ?: return
                val direction = if (accumulatedPixels < 0f) -1 else 1
                val threshold = selectionStepPixels(text, target, direction, wholeWord)
                val before = target
                val proposed = moveSelectionTarget(text, target, direction, wholeWord)

                // Moving back toward the anchor shrinks the selection instead
                // of clearing it immediately. The endpoint cannot cross the
                // anchor during this step, so a small reverse movement only
                // adjusts the already selected range.
                target = when {
                    direction < 0 && target > anchor -> maxOf(anchor, proposed)
                    direction > 0 && target < anchor -> minOf(anchor, proposed)
                    else -> proposed
                }
                accumulatedPixels -= direction * threshold
                if (target == before) break
            }
            setSelection(anchor, target)
        }



        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    lastRawX = event.rawX
                    accumulatedPixels = 0f
                    selecting = false
                    continuationActive = false
                    repeatStarted = false
                    lastDirection = 0
                    touchHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val totalDelta = event.rawX - downRawX
                    if (!selecting && kotlin.math.abs(totalDelta) >= selectThreshold) {
                        stopRunnables()
                        val extracted = extractedText()
                        if (extracted != null) {
                            selecting = true
                            anchor = extracted.selectionEnd
                            target = anchor
                            lastRawX = downRawX
                            accumulatedPixels = 0f
                            setSelection(anchor, anchor)
                            moveByPixels(totalDelta)
                        }
                    } else if (selecting) {
                        val atEdge = event.rawX <= edgeZone || event.rawX >= screenWidth - edgeZone
                        // While the finger remains in the edge zone, keep the
                        // original continuation direction. Tiny reverse
                        // movements at the corner must not undo selection.
                        // Reverse adjustment becomes active only after the
                        // finger leaves the edge zone.
                        if (!(continuationActive && atEdge)) {
                            if (!atEdge) continuationActive = false
                            moveByPixels(event.rawX - lastRawX)
                        }
                        lastRawX = event.rawX
                    }
                    if (selecting) {
                        val atEdge = event.rawX <= edgeZone || event.rawX >= screenWidth - edgeZone
                        if (atEdge) {
                            if (!continuationActive) {
                                continuationActive = true
                                continuationDirection = lastDirection
                            }
                            lastDirection = continuationDirection
                            touchHandler.removeCallbacks(edgeRepeat)
                            touchHandler.post(edgeRepeat)
                        } else {
                            continuationActive = false
                            touchHandler.removeCallbacks(edgeRepeat)
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasRepeating = repeatStarted
                    stopRunnables()
                    if (selecting && event.actionMasked == MotionEvent.ACTION_UP) {
                        deleteSelectedTextIfAny()
                        Haptics.tap(this)
                    } else if (!wasRepeating && !selecting && event.actionMasked == MotionEvent.ACTION_UP) {
                        if (wholeWord) deletePreviousWord() else deletePreviousCodePoint()
                        Haptics.tap(this)
                    } else if (wasRepeating && event.actionMasked == MotionEvent.ACTION_UP) {
                        Haptics.tap(this)
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun extractedText(): android.view.inputmethod.ExtractedText? =
        currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)

    private fun moveFromAnchor(
        text: CharSequence,
        anchor: Int,
        direction: Int,
        steps: Int,
        wholeWord: Boolean,
    ): Int {
        var position = anchor.coerceIn(0, text.length)
        repeat(steps) {
            position = moveSelectionTarget(text, position, direction, wholeWord)
        }
        return position
    }

    private fun moveSelectionTarget(
        text: CharSequence,
        from: Int,
        direction: Int,
        wholeWord: Boolean,
    ): Int {
        if (direction < 0) {
            if (from <= 0) return 0
            var p = from
            if (wholeWord) {
                while (p > 0 && text[p - 1].isWhitespace()) p--
                while (p > 0 && !text[p - 1].isWhitespace()) p--
                return p
            }
            return text.toString().offsetByCodePoints(p, -1)
        }
        if (from >= text.length) return text.length
        var p = from
        if (wholeWord) {
            while (p < text.length && text[p].isWhitespace()) p++
            while (p < text.length && !text[p].isWhitespace()) p++
            return p
        }
        return text.toString().offsetByCodePoints(p, 1)
    }

    private fun extractedLength(): Int =
        currentInputConnection?.getExtractedText(ExtractedTextRequest(), 0)?.text?.length ?: 0

    private fun setSelection(anchor: Int, target: Int) {
        val connection = currentInputConnection ?: return
        val start = minOf(anchor, target)
        val end = maxOf(anchor, target)
        connection.setSelection(start, end)
        // Well-behaved multiline editors normally scroll the active selection
        // into view after setSelection(). Requesting cursor updates also gives
        // editors that support CursorAnchorInfo a chance to reposition their
        // viewport while the endpoint is being advanced continuously.
        connection.requestCursorUpdates(
            android.view.inputmethod.InputConnection.CURSOR_UPDATE_IMMEDIATE,
        )
    }

    private fun configureRecordTouch() {
        recordButton.setOnClickListener {
            if (Prefs.isImeWalkieTalkieEnabled(this)) return@setOnClickListener
            if (recording) stopRecording() else startRecording()
        }
        recordButton.setOnTouchListener { _, event ->
            if (!Prefs.isImeWalkieTalkieEnabled(this)) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    walkieLongPressTriggered = !recording
                    walkieLocked = false
                    walkieDownRawY = event.rawY
                    if (walkieLongPressTriggered) {
                        touchHandler.postDelayed(
                            walkieStartRunnable,
                            ViewConfiguration.getLongPressTimeout().toLong(),
                        )
                    }
                    recordButton.isPressed = true
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (walkieLongPressTriggered && !walkieLocked &&
                        walkieDownRawY - event.rawY >= dp(48)
                    ) {
                        // Slide upward to lock recording after release.
                        walkieLocked = true
                        Haptics.doubleTap(this)
                        updateRecordButton()
                        recordButton.isPressed = false
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touchHandler.removeCallbacks(walkieStartRunnable)
                    recordButton.isPressed = false
                    if (walkieLongPressTriggered) {
                        if (!walkieLocked && recording) stopRecording()
                    } else if (recording) {
                        // A tap always stops an active recording, including an
                        // automatically started or previously locked session.
                        stopRecording()
                    }
                    walkieLongPressTriggered = false
                    true
                }
                else -> true
            }
        }
    }

    private fun weightedButtonParams() = LinearLayout.LayoutParams(0, dp(56), 1f).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun recordButtonParams() = LinearLayout.LayoutParams(dp(90), dp(90)).apply {
        marginStart = dp(4)
        marginEnd = dp(4)
    }

    private fun primaryColor(): Int = MaterialColors.getColor(
        themedContext, com.google.android.material.R.attr.colorPrimary, Color.rgb(70, 90, 150)
    )

    private fun errorColor(): Int = MaterialColors.getColor(
        themedContext, com.google.android.material.R.attr.colorError, Color.rgb(186, 26, 26)
    )

    private fun onSurfaceVariantColor(): Int = MaterialColors.getColor(
        themedContext, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY
    )

    private fun onPrimaryColor(): Int = MaterialColors.getColor(
        themedContext, com.google.android.material.R.attr.colorOnPrimary, Color.WHITE
    )

    private fun onErrorColor(): Int = MaterialColors.getColor(
        themedContext, com.google.android.material.R.attr.colorOnError, Color.WHITE
    )

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        currentEditorInfo = info
        if (::recordButton.isInitialized) {
            updateRecordButton()
            if (Prefs.isImeAutoStartEnabled(this) && !recording) {
                recordButton.post { if (!recording) startRecording() }
            }
        }
    }

    private fun startRecording() {
        if (recording) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            recordButton.tooltipText = getString(R.string.ime_mic_permission)
            Toast.makeText(this, R.string.ime_mic_permission, Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra(MainActivity.EXTRA_REQUEST_MIC, true)
                })
            } catch (_: Exception) { }
            return
        }
        if (!GigaAmModel.isInstalled(this)) {
            recordButton.tooltipText = getString(R.string.ime_model_not_ready)
            return
        }
        sessionText.clear()
        processing = false
        recording = true
        Haptics.longPress(this)
        updateRecordButton()
        recordButton.tooltipText = getString(R.string.ime_listening)
        val currentRecorder = VadRecorder(this)
        recorder = currentRecorder
        currentRecorder.start(
            scope = scope,
            transcriberProvider = { OfflineTranscriber.getInstance(this@RechkaInputMethodService) },
            onSegment = { text ->
                if (text.isNotBlank()) {
                    val inserted = commitInserted(text)
                    StatsStore.addWords(applicationContext, StatsStore.countWords(inserted))
                }
            },
            onDone = {
                recording = false
                processing = false
                walkieLocked = false
                recorder = null
                if (sessionText.isNotEmpty() && Prefs.isVoiceSuffixEnabled(this@RechkaInputMethodService)) {
                    currentInputConnection?.commitText("\n\n[текст распознан с голоса]", 1)
                }
                updateRecordButton()
                recordButton.tooltipText = getString(R.string.ime_start)
            },
            // Feed the "minutes of dictation" counter with pure speech time,
            // exactly like the overlay bubble does. Without this callback the
            // keyboard only ever contributed words, never seconds.
            onSpeechMillis = { millis ->
                StatsStore.addSpeechMillis(applicationContext, millis)
            },
            useVad = true,
        )
    }

    private fun stopRecording() {
        if (!recording) return
        Haptics.tap(this)
        processing = true
        updateRecordButton()
        recordButton.tooltipText = getString(R.string.ime_processing)
        recorder?.stop()
    }

    private fun updateRecordButton() {
        if (!::recordButton.isInitialized) return
        if (processing) {
            // BubbleView states are mutually exclusive. Do not call
            // setProcessing(false) after setRecording(true): that would
            // immediately clear the recording animation.
            recordButton.setProcessing(true)
        } else {
            recordButton.setRecording(recording)
        }
        val label = when {
            processing -> R.string.ime_processing
            recording && walkieLocked -> R.string.ime_locked
            recording -> R.string.ime_stop
            else -> R.string.ime_start
        }
        recordButton.contentDescription = getString(label)
        recordButton.tooltipText = getString(label)
    }

    private fun performEditorActionIfSupported(): Boolean {
        val info = currentEditorInfo ?: return false
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return false
        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) return false
        return currentInputConnection?.performEditorAction(action) == true
    }

    private fun commitInserted(text: String): String {
        if (text.isEmpty()) return ""
        val replaced = Dictionary.applyReplacements(this, text)
        val connection = currentInputConnection ?: return ""
        val before = connection.getTextBeforeCursor(2, 0)?.toString().orEmpty()
        val previous = before.lastOrNull()
        val first = replaced.firstOrNull()
        val needsSpace = previous != null &&
            !previous.isWhitespace() &&
            first != null &&
            first.isLetterOrDigit() &&
            previous !in "([\\{\\\"'«"
        val inserted = if (needsSpace) " $replaced" else replaced
        connection.commitText(inserted, 1)
        sessionText.append(inserted)
        return inserted
    }

    private fun deletePreviousCodePoint() {
        if (deleteSelectedTextIfAny()) return
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(4, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        connection.deleteSurroundingTextInCodePoints(1, 0)
    }

    /** Attached punctuation stays part of the preceding word/token. */
    private fun deletePreviousWord() {
        if (deleteSelectedTextIfAny()) return
        val connection = currentInputConnection ?: return
        val before = connection.getTextBeforeCursor(512, 0)?.toString() ?: return
        if (before.isEmpty()) return
        var end = before.length
        while (end > 0 && before[end - 1].isWhitespace()) end--
        var start = end
        while (start > 0 && !before[start - 1].isWhitespace()) start--
        if (start == end) return
        val token = before.substring(start)
        connection.deleteSurroundingTextInCodePoints(token.codePointCount(0, token.length), 0)
    }

    private fun removeSessionText() {
        val connection = currentInputConnection ?: return
        val text = sessionText.toString()
        if (text.isEmpty()) return
        val before = connection.getTextBeforeCursor(text.length + 8, 0)?.toString() ?: return
        if (!before.endsWith(text)) return
        connection.deleteSurroundingTextInCodePoints(text.codePointCount(0, text.length), 0)
        sessionText.clear()
        Haptics.tap(this)
    }

    private fun exitToPreviousInputMethod() {
        stopRecording()
        if (!switchToPreviousInputMethod()) requestHideSelf(0)
    }

    override fun onFinishInput() {
        stopRecording()
        super.onFinishInput()
    }

    override fun onDestroy() {
        recorder?.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
