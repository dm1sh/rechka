package ru.dm1sh.rechka.service

/** Interprets one IME microphone gesture; the caller owns the long-press timer. */
internal class WalkieTalkieGesture {
    enum class EndAction { NONE, CLICK, STOP_HOLD }

    var isActive = false
        private set
    var isLocked = false
        private set

    private var startedIdle = false
    private var holdTimedOut = false
    private var holdRequested = false
    private var holdStarted = false
    private var downRawY = 0f

    /** Returns whether to schedule the hold timer. */
    fun begin(recording: Boolean, rawY: Float): Boolean {
        isActive = true
        startedIdle = !recording
        holdTimedOut = false
        holdRequested = false
        holdStarted = false
        downRawY = rawY
        if (startedIdle) isLocked = false
        return startedIdle
    }

    /** Returns whether this gesture should start a hold recording. */
    fun onHoldTimeout(recording: Boolean): Boolean {
        if (!isActive || !startedIdle || holdTimedOut) return false
        holdTimedOut = true
        // Auto-start or an accessibility click may have started recording since DOWN.
        holdRequested = !recording
        return holdRequested
    }

    fun onHoldStartResult(recordingStarted: Boolean) {
        if (isActive && holdRequested) holdStarted = recordingStarted
    }

    /** Returns true only on the first upward crossing of the lock threshold. */
    fun move(rawY: Float, lockDistance: Float): Boolean {
        if (!isActive || !startedIdle || isLocked || downRawY - rawY < lockDistance) return false
        isLocked = true
        return true
    }

    fun end(cancelled: Boolean, recording: Boolean, insideButton: Boolean = true): EndAction {
        if (!isActive) return EndAction.NONE
        val action = when {
            cancelled -> {
                if (startedIdle) isLocked = false
                // An interrupted hold must not leave the mic recording, even
                // if a lock swipe had begun. Never toggle on cancellation.
                if (holdStarted) EndAction.STOP_HOLD else EndAction.NONE
            }
            !holdTimedOut -> {
                // A quick upward swipe locks too, even if it finishes before
                // the long-press timer. Don't stop a recording that started
                // independently while the user was swiping to lock it.
                when {
                    startedIdle && isLocked && recording -> EndAction.NONE
                    insideButton || (startedIdle && isLocked) -> EndAction.CLICK
                    else -> EndAction.NONE
                }
            }
            isLocked -> EndAction.NONE
            holdStarted -> EndAction.STOP_HOLD
            holdRequested -> EndAction.NONE // Start failed: do not try again on UP.
            insideButton -> EndAction.CLICK // Recording started independently during the press.
            else -> EndAction.NONE
        }
        if (holdRequested && !holdStarted) isLocked = false
        isActive = false
        return action
    }

    fun clearLock() {
        isLocked = false
    }
}
