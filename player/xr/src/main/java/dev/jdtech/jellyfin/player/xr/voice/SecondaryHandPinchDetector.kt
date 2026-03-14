package dev.jdtech.jellyfin.player.xr.voice

import android.app.Activity
import androidx.xr.arcore.Hand
import androidx.xr.arcore.HandJointType
import androidx.xr.runtime.Session
import androidx.xr.runtime.TrackingState
import androidx.xr.runtime.math.Vector3
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive

class SecondaryHandPinchDetector(
    private val session: Session,
    private val activity: Activity,
) {
    enum class PinchEvent {
        STARTED,
        ENDED,
    }

    companion object {
        private const val PINCH_THRESHOLD = 0.04f
        private const val RELEASE_THRESHOLD = 0.06f
        private const val POLL_INTERVAL_MS = 30L
        private const val MIN_HOLD_MS = 150L
    }

    val pinchEvents: Flow<PinchEvent> = flow {
        var isPinching = false
        var pinchStartTime = 0L
        var emittedStart = false

        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)

            val distance = getSecondaryHandPinchDistance() ?: continue

            if (!isPinching && distance < PINCH_THRESHOLD) {
                isPinching = true
                pinchStartTime = System.currentTimeMillis()
            } else if (isPinching && distance > RELEASE_THRESHOLD) {
                isPinching = false
                if (emittedStart) {
                    emit(PinchEvent.ENDED)
                    emittedStart = false
                }
            }

            if (isPinching && !emittedStart) {
                if (System.currentTimeMillis() - pinchStartTime >= MIN_HOLD_MS) {
                    emit(PinchEvent.STARTED)
                    emittedStart = true
                }
            }
        }
    }

    private fun getSecondaryHandPinchDistance(): Float? {
        val primarySide = Hand.getPrimaryHandSide(activity.contentResolver)
        val secondaryHand =
            if (primarySide == Hand.HandSide.LEFT) Hand.right(session) else Hand.left(session)
        val handState = secondaryHand?.state?.value ?: return null
        if (handState.trackingState != TrackingState.TRACKING) return null

        val thumbTip =
            handState.handJoints[HandJointType.HAND_JOINT_TYPE_THUMB_TIP]?.translation
                ?: return null
        val indexTip =
            handState.handJoints[HandJointType.HAND_JOINT_TYPE_INDEX_TIP]?.translation
                ?: return null

        return Vector3.distance(thumbTip, indexTip)
    }
}
