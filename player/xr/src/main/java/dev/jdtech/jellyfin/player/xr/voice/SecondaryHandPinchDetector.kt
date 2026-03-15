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

import kotlin.math.abs

class SecondaryHandPinchDetector(
    private val session: Session,
    private val activity: Activity,
) {
    enum class PinchEvent {
        STARTED,
        ENDED,
    }

    companion object {
        private const val PINCH_THRESHOLD = 0.02f
        private const val RELEASE_THRESHOLD = 0.04f
        private const val POLL_INTERVAL_MS = 30L
        private const val MIN_HOLD_MS = 400L
        private const val MAX_PINCH_DISTANCE_FROM_CENTER = 0.6f // meters from center line
    }

    val pinchEvents: Flow<PinchEvent> = flow {
        var isPinching = false
        var pinchStartTime = 0L
        var emittedStart = false

        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)

            val handState = getSecondaryHandState() ?: continue
            if (handState.trackingState != TrackingState.TRACKING) {
                if (isPinching) {
                    isPinching = false
                    if (emittedStart) {
                        emit(PinchEvent.ENDED)
                        emittedStart = false
                    }
                }
                continue
            }

            val thumbTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_THUMB_TIP]?.translation ?: continue
            val indexTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_INDEX_TIP]?.translation ?: continue
            val distance = Vector3.distance(thumbTip, indexTip)

            // Basic "in front of nose" check: Hand should be somewhat centered horizontally
            // and in front of the user (assuming session origin is user).
            // This is a heuristic as we don't have the exact head pose here without more effort.
            val isCentered = abs(thumbTip.x) < MAX_PINCH_DISTANCE_FROM_CENTER

            if (!isPinching && distance < PINCH_THRESHOLD && isCentered) {
                isPinching = true
                pinchStartTime = System.currentTimeMillis()
            } else if (isPinching && (distance > RELEASE_THRESHOLD || !isCentered)) {
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

    private fun getSecondaryHandState(): Hand.State? {
        val primarySide = Hand.getPrimaryHandSide(activity.contentResolver)
        val secondaryHand =
            if (primarySide == Hand.HandSide.LEFT) Hand.right(session) else Hand.left(session)
        return secondaryHand?.state?.value
    }
}


