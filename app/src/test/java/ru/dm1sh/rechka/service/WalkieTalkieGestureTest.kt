package ru.dm1sh.rechka.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.dm1sh.rechka.service.WalkieTalkieGesture.EndAction

class WalkieTalkieGestureTest {
    @Test fun shortTapsToggleFromIdleAndFromRecording() {
        val gesture = WalkieTalkieGesture()
        var recording = false

        fun tap() {
            assertEquals(!recording, gesture.begin(recording, rawY = 200f))
            assertEquals(EndAction.CLICK, gesture.end(cancelled = false, recording = recording))
            recording = !recording // The button's click listener performs the toggle.
        }

        tap()
        assertTrue(recording)
        tap()
        assertFalse(recording)
        assertFalse(gesture.isActive)
        assertFalse(gesture.onHoldTimeout(recording = false)) // A late timer cannot start the mic.
    }

    @Test fun holdStartsOnceAndStopsOnRelease() {
        val gesture = WalkieTalkieGesture()
        assertTrue(gesture.begin(recording = false, rawY = 200f))
        assertTrue(gesture.onHoldTimeout(recording = false))
        gesture.onHoldStartResult(recordingStarted = true)
        assertFalse(gesture.onHoldTimeout(recording = true))
        assertEquals(EndAction.STOP_HOLD, gesture.end(cancelled = false, recording = true, insideButton = false))
        assertFalse(gesture.isActive)
        assertFalse(gesture.isLocked)
    }

    @Test fun upwardSwipeLocksHeldRecordingUntilNextTap() {
        val gesture = WalkieTalkieGesture()
        gesture.begin(recording = false, rawY = 200f)
        assertTrue(gesture.onHoldTimeout(recording = false))
        gesture.onHoldStartResult(recordingStarted = true)
        assertFalse(gesture.move(rawY = 153f, lockDistance = 48f))
        assertTrue(gesture.move(rawY = 152f, lockDistance = 48f))
        assertFalse(gesture.move(rawY = 140f, lockDistance = 48f))
        assertEquals(EndAction.NONE, gesture.end(cancelled = false, recording = true, insideButton = false))
        assertTrue(gesture.isLocked)

        assertFalse(gesture.begin(recording = true, rawY = 200f))
        assertEquals(EndAction.CLICK, gesture.end(cancelled = false, recording = true))
        gesture.clearLock() // The recording's completion callback clears the lock.
        assertFalse(gesture.isLocked)
    }

    @Test fun quickUpwardSwipeCanStartLockedBeforeTheTimer() {
        val gesture = WalkieTalkieGesture()
        gesture.begin(recording = false, rawY = 200f)
        assertTrue(gesture.move(rawY = 150f, lockDistance = 48f))
        assertEquals(EndAction.CLICK, gesture.end(cancelled = false, recording = false, insideButton = false))
        assertTrue(gesture.isLocked)
        assertFalse(gesture.onHoldTimeout(recording = false))
    }

    @Test fun ordinaryReleaseOutsideDoesNotClick() {
        val gesture = WalkieTalkieGesture()
        gesture.begin(recording = false, rawY = 200f)
        assertEquals(EndAction.NONE, gesture.end(cancelled = false, recording = false, insideButton = false))
        gesture.begin(recording = true, rawY = 200f)
        assertEquals(EndAction.NONE, gesture.end(cancelled = false, recording = true, insideButton = false))
    }

    @Test fun cancellationBeforeTimeoutDoesNotToggleOrStartLater() {
        val gesture = WalkieTalkieGesture()
        gesture.begin(recording = false, rawY = 200f)
        assertEquals(EndAction.NONE, gesture.end(cancelled = true, recording = false))
        assertFalse(gesture.onHoldTimeout(recording = false))
        assertFalse(gesture.move(rawY = 100f, lockDistance = 48f))
        assertEquals(EndAction.NONE, gesture.end(cancelled = false, recording = false))
    }

    @Test fun cancellingAnActiveRecordingDoesNotStopOrUnlockIt() {
        val gesture = WalkieTalkieGesture()
        gesture.begin(recording = false, rawY = 200f)
        assertTrue(gesture.onHoldTimeout(recording = false))
        gesture.onHoldStartResult(recordingStarted = true)
        gesture.move(rawY = 150f, lockDistance = 48f)
        assertEquals(EndAction.NONE, gesture.end(cancelled = false, recording = true))

        assertFalse(gesture.begin(recording = true, rawY = 200f))
        assertEquals(EndAction.NONE, gesture.end(cancelled = true, recording = true))
        assertTrue(gesture.isLocked)
    }

    @Test fun cancellingAStartedHoldStopsItEvenAfterLockSwipe() {
        for (lock in listOf(false, true)) {
            val gesture = WalkieTalkieGesture()
            gesture.begin(recording = false, rawY = 200f)
            assertTrue(gesture.onHoldTimeout(recording = false))
            gesture.onHoldStartResult(recordingStarted = true)
            if (lock) assertTrue(gesture.move(rawY = 150f, lockDistance = 48f))
            assertEquals(EndAction.STOP_HOLD, gesture.end(cancelled = true, recording = true))
            assertFalse(gesture.isLocked)
        }
    }

    @Test fun failedHoldStartIsNotRetriedOnRelease() {
        for (lock in listOf(false, true)) {
            val gesture = WalkieTalkieGesture()
            gesture.begin(recording = false, rawY = 200f)
            assertTrue(gesture.onHoldTimeout(recording = false))
            gesture.onHoldStartResult(recordingStarted = false)
            if (lock) gesture.move(rawY = 150f, lockDistance = 48f)
            assertEquals(EndAction.NONE, gesture.end(cancelled = false, recording = false))
            assertFalse(gesture.isLocked)
        }
    }

    @Test fun recordingStartedIndependentlyIsNotStoppedByCancellation() {
        val gesture = WalkieTalkieGesture()
        gesture.begin(recording = false, rawY = 200f)
        assertFalse(gesture.onHoldTimeout(recording = true))
        assertEquals(EndAction.NONE, gesture.end(cancelled = true, recording = true))

        gesture.begin(recording = false, rawY = 200f)
        assertFalse(gesture.onHoldTimeout(recording = true))
        assertEquals(EndAction.CLICK, gesture.end(cancelled = false, recording = true))
    }

    @Test fun independentlyStartedRecordingCanStillBeLockedBySwipe() {
        val gesture = WalkieTalkieGesture()
        gesture.begin(recording = false, rawY = 200f)
        assertTrue(gesture.move(rawY = 150f, lockDistance = 48f))
        assertFalse(gesture.onHoldTimeout(recording = true))
        assertEquals(EndAction.NONE, gesture.end(cancelled = false, recording = true))
        assertTrue(gesture.isLocked)
    }

    @Test fun quickLockDoesNotToggleAnIndependentlyStartedRecordingOff() {
        val gesture = WalkieTalkieGesture()
        gesture.begin(recording = false, rawY = 200f)
        assertTrue(gesture.move(rawY = 150f, lockDistance = 48f))
        assertEquals(EndAction.NONE, gesture.end(cancelled = false, recording = true, insideButton = false))
        assertTrue(gesture.isLocked)
        assertFalse(gesture.onHoldTimeout(recording = true))
    }
}
