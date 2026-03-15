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
    @Suppress("UNUSED_PARAMETER") private val activity: Activity,
) {
    enum class PinchEvent {
        STARTED,
        ENDED,
    }

    companion object {
        private const val PINCH_THRESHOLD = 0.015f
        private const val RELEASE_THRESHOLD = 0.028f
        private const val POLL_INTERVAL_MS = 30L
        private const val MIN_HOLD_MS = 650L
        private const val MAX_PINCH_DISTANCE_FROM_CENTER = 0.22f // meters from center line
        private const val MAX_PINCH_TRAVEL_METERS = 0.025f
        private const val MIN_WRIST_FORWARD_DISTANCE = 0.12f
        private const val MIN_INDEX_EXTENSION_RATIO = 1.35f
    }

    val pinchEvents: Flow<PinchEvent> = flow {
        var isPinching = false
        var pinchStartTime = 0L
        var emittedStart = false
        var pinchAnchor: Vector3? = null

        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)

            val handState = getVoiceHandState() ?: continue
            if (handState.trackingState != TrackingState.TRACKING) {
                if (isPinching) {
                    isPinching = false
                    pinchAnchor = null
                    if (emittedStart) {
                        emit(PinchEvent.ENDED)
                        emittedStart = false
                    }
                }
                continue
            }

            val thumbTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_THUMB_TIP]?.translation ?: continue
            val indexTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_INDEX_TIP]?.translation ?: continue
            val middleTip = handState.handJoints[HandJointType.HAND_JOINT_TYPE_MIDDLE_TIP]?.translation ?: continue
            val wrist = handState.handJoints[HandJointType.HAND_JOINT_TYPE_WRIST]?.translation ?: continue
            val distance = Vector3.distance(thumbTip, indexTip)

            // Require a deliberate secondary-hand pinch roughly in front of the user,
            // not a casual hand closure off to the side.
            val pinchCenter = Vector3(
                (thumbTip.x + indexTip.x) / 2f,
                (thumbTip.y + indexTip.y) / 2f,
                (thumbTip.z + indexTip.z) / 2f,
            )
            val isCentered = abs(wrist.x) < MAX_PINCH_DISTANCE_FROM_CENTER
            val isInFront = wrist.z < -MIN_WRIST_FORWARD_DISTANCE
            val isIndexIsolated = Vector3.distance(thumbTip, middleTip) > distance * MIN_INDEX_EXTENSION_RATIO
            val isStable = pinchAnchor?.let { anchor ->
                Vector3.distance(anchor, pinchCenter) <= MAX_PINCH_TRAVEL_METERS
            } ?: true

            val isDeliberatePinch = distance < PINCH_THRESHOLD && isCentered && isInFront && isIndexIsolated && isStable

            if (!isPinching && isDeliberatePinch) {
                isPinching = true
                pinchStartTime = System.currentTimeMillis()
                pinchAnchor = pinchCenter
            } else if (isPinching && (!isDeliberatePinch || distance > RELEASE_THRESHOLD)) {
                isPinching = false
                pinchAnchor = null
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

    private fun getVoiceHandState(): Hand.State? {
        // Reserve the physical left hand for voice activation. Relying on the platform's
        // "primary hand" report proved unreliable on-device and caused the right hand to be
        // treated as both the interaction hand and the voice hand.
        return Hand.left(session)?.state?.value
    }
}
