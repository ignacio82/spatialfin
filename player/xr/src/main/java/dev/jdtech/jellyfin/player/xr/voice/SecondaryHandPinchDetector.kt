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
import timber.log.Timber
import kotlin.math.abs

class SecondaryHandPinchDetector(
    private val session: Session,
    @Suppress("UNUSED_PARAMETER") private val activity: Activity,
    private val preferredHand: String,
) {
    sealed interface GestureState {
        data object Idle : GestureState

        data class Arming(val progress: Float) : GestureState

        data object Started : GestureState

        data object Ended : GestureState
    }

    companion object {
        private const val PINCH_THRESHOLD = 0.050f // significantly increased from 0.030f
        private const val RELEASE_THRESHOLD = 0.070f // significantly increased from 0.050f
        private const val POLL_INTERVAL_MS = 30L
        private const val MIN_HOLD_MS = 320L
        private const val MAX_PINCH_DISTANCE_FROM_CENTER = 0.34f
        private const val MAX_PINCH_TRAVEL_METERS = 0.045f
        private const val MIN_WRIST_FORWARD_DISTANCE = 0.05f
        private const val MIN_INDEX_EXTENSION_RATIO = 1.05f
        private const val MIN_VALID_FRAMES = 1
        private const val MIN_RELEASE_FRAMES = 2
        private const val ACTIVATION_COOLDOWN_MS = 400L
        private const val MAX_WRIST_HEIGHT = 0.32f
        private const val MIN_WRIST_HEIGHT = -0.68f
    }

    val gestureStates: Flow<GestureState> = flow {
        var isActive = false
        var validFrameCount = 0
        var invalidFrameCount = 0
        var pinchStartTime = 0L
        var pinchAnchor: Vector3? = null
        var cooldownUntil = 0L
        var lastProgressBucket = -1
        var lastState: GestureState = GestureState.Idle

        suspend fun emitIfChanged(state: GestureState) {
            val shouldEmit =
                when {
                    state::class != lastState::class -> true
                    state is GestureState.Arming && lastState is GestureState.Arming ->
                        (state.progress * 10).toInt() != ((lastState as GestureState.Arming).progress * 10).toInt()
                    else -> false
                }
            if (shouldEmit) {
                emit(state)
                lastState = state
            }
        }

        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)

            val now = System.currentTimeMillis()
            if (now < cooldownUntil && !isActive) {
                validFrameCount = 0
                invalidFrameCount = 0
                pinchAnchor = null
                pinchStartTime = 0L
                lastProgressBucket = -1
                emitIfChanged(GestureState.Idle)
                continue
            }

            val handState = getVoiceHandState() ?: run {
                if (isActive) {
                    isActive = false
                    cooldownUntil = now + ACTIVATION_COOLDOWN_MS
                    Timber.i("Voice gesture ended: hand state unavailable")
                    emitIfChanged(GestureState.Ended)
                } else {
                    emitIfChanged(GestureState.Idle)
                }
                validFrameCount = 0
                invalidFrameCount = 0
                pinchAnchor = null
                pinchStartTime = 0L
                lastProgressBucket = -1
                continue
            }

            if (handState.trackingState != TrackingState.TRACKING) {
                if (isActive) {
                    isActive = false
                    cooldownUntil = now + ACTIVATION_COOLDOWN_MS
                    Timber.i("Voice gesture ended: tracking lost")
                    emitIfChanged(GestureState.Ended)
                } else {
                    emitIfChanged(GestureState.Idle)
                }
                validFrameCount = 0
                invalidFrameCount = 0
                pinchAnchor = null
                pinchStartTime = 0L
                lastProgressBucket = -1
                continue
            }

            val thumbTip =
                handState.handJoints[HandJointType.HAND_JOINT_TYPE_THUMB_TIP]?.translation ?: continue
            val indexTip =
                handState.handJoints[HandJointType.HAND_JOINT_TYPE_INDEX_TIP]?.translation ?: continue
            val middleTip =
                handState.handJoints[HandJointType.HAND_JOINT_TYPE_MIDDLE_TIP]?.translation ?: continue
            val wrist =
                handState.handJoints[HandJointType.HAND_JOINT_TYPE_WRIST]?.translation ?: continue
            val distance = Vector3.distance(thumbTip, middleTip)

            val pinchCenter =
                Vector3(
                    (thumbTip.x + middleTip.x) / 2f,
                    (thumbTip.y + middleTip.y) / 2f,
                    (thumbTip.z + middleTip.z) / 2f,
                )
            val isCentered = abs(wrist.x) < 0.6f // Relaxed to allow lap resting
            val isIndexOut = Vector3.distance(thumbTip, indexTip) > 0.015f // Ensure OS doesn't think it's an index pinch
            val isStable =
                pinchAnchor?.let { anchor ->
                    Vector3.distance(anchor, pinchCenter) <= MAX_PINCH_TRAVEL_METERS
                } ?: true

            val isDeliberatePinch =
                distance < PINCH_THRESHOLD &&
                    isCentered &&
                    isIndexOut &&
                    isStable

            if (!isDeliberatePinch && distance < 0.06f) {
                Timber.d(
                    "Pinch attempt failed: distance=%.4f (th=%.4f), center=%b, indexOut=%b, stable=%b",
                    distance, PINCH_THRESHOLD, isCentered, isIndexOut, isStable
                )
            }

            if (isDeliberatePinch) {
                invalidFrameCount = 0
                if (validFrameCount == 0) {
                    pinchStartTime = now
                    pinchAnchor = pinchCenter
                }
                validFrameCount += 1

                if (!isActive && validFrameCount >= MIN_VALID_FRAMES) {
                    val progress = ((now - pinchStartTime).toFloat() / MIN_HOLD_MS).coerceIn(0f, 1f)
                    val progressBucket = (progress * 10).toInt()
                    if (progressBucket != lastProgressBucket && progress < 1f) {
                        emitIfChanged(GestureState.Arming(progress))
                        lastProgressBucket = progressBucket
                    }
                    if (now - pinchStartTime >= MIN_HOLD_MS) {
                        isActive = true
                        lastProgressBucket = -1
                        Timber.i("Voice gesture started for %s hand", preferredHand.lowercase())
                        emitIfChanged(GestureState.Started)
                    }
                }
            } else {
                validFrameCount = 0
                pinchAnchor = null
                pinchStartTime = 0L
                lastProgressBucket = -1
                invalidFrameCount += 1

                if (isActive && (distance > RELEASE_THRESHOLD || invalidFrameCount >= MIN_RELEASE_FRAMES)) {
                    isActive = false
                    invalidFrameCount = 0
                    cooldownUntil = now + ACTIVATION_COOLDOWN_MS
                    Timber.i("Voice gesture ended: pinch released")
                    emitIfChanged(GestureState.Ended)
                } else if (!isActive && invalidFrameCount >= MIN_RELEASE_FRAMES) {
                    emitIfChanged(GestureState.Idle)
                }
            }
        }
    }

    private fun getVoiceHandState(): Hand.State? {
        val hand =
            when (preferredHand.lowercase()) {
                "right" -> Hand.right(session)
                else -> Hand.left(session)
            }
        return hand?.state?.value
    }
}
