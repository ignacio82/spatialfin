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

        data class Arming(val progress: Float, val hint: String) : GestureState

        data object Started : GestureState

        data object Ended : GestureState
    }

    private enum class ActivationPhase {
        IDLE,
        ARMING_PALM,
        ACTIVE,
    }

    private data class HandMetrics(
        val wrist: Vector3,
        val palm: Vector3,
        val thumbTip: Vector3,
        val thumbProximal: Vector3,
        val indexTip: Vector3,
        val indexProximal: Vector3,
        val indexMetacarpal: Vector3,
        val middleTip: Vector3,
        val middleProximal: Vector3,
        val ringTip: Vector3,
        val ringProximal: Vector3,
        val littleTip: Vector3,
        val littleProximal: Vector3,
    )

    companion object {
        private const val PALM_HOLD_MS = 240L
        private const val RELEASE_PALM_TRAVEL_METERS = 0.080f
        private const val POLL_INTERVAL_MS = 30L
        private const val MIN_PALM_VALID_FRAMES = 6
        private const val MAX_PALM_TRAVEL_METERS = 0.055f
        private const val MIN_FINGER_EXTENSION_RATIO = 1.18f
        private const val MIN_THUMB_EXTENSION_RATIO = 1.10f
        private const val MIN_FINGER_SPREAD_METERS = 0.018f
        private const val MIN_THUMB_SEPARATION_METERS = 0.040f
        private const val MIN_RELEASE_FRAMES = 2
        private const val ACTIVATION_COOLDOWN_MS = 450L
        private const val MAX_WRIST_HEIGHT = 0.32f
        private const val MIN_WRIST_HEIGHT = -0.68f
        private const val MAX_WRIST_LATERAL_OFFSET = 0.60f
        private const val MAX_ARMING_PALM_LATERAL_OFFSET = 0.45f
        private const val MIN_ARMING_WRIST_HEIGHT = -0.24f
        private const val MIN_ARMING_PALM_HEIGHT = -0.12f
        private const val HOLD_OPEN_PALM_HINT = "Hold palm near face"
    }

    val gestureStates: Flow<GestureState> = flow {
        var phase = ActivationPhase.IDLE
        var palmValidFrameCount = 0
        var invalidFrameCount = 0
        var palmStartTime = 0L
        var palmAnchor: Vector3? = null
        var cooldownUntil = 0L
        var lastProgressBucket = -1
        var lastState: GestureState = GestureState.Idle

        suspend fun emitIfChanged(state: GestureState) {
            val shouldEmit =
                when {
                    state::class != lastState::class -> true
                    state is GestureState.Arming && lastState is GestureState.Arming -> {
                        val previous = lastState as GestureState.Arming
                        (state.progress * 10).toInt() != (previous.progress * 10).toInt() ||
                            state.hint != previous.hint
                    }
                    else -> false
                }
            if (shouldEmit) {
                emit(state)
                lastState = state
            }
        }

        fun resetPalmTracking() {
            palmValidFrameCount = 0
            palmStartTime = 0L
            palmAnchor = null
        }

        fun resetAllTracking() {
            resetPalmTracking()
            invalidFrameCount = 0
            lastProgressBucket = -1
        }

        suspend fun transitionToIdle(ended: Boolean, reason: String? = null) {
            val wasActive = phase == ActivationPhase.ACTIVE
            phase = ActivationPhase.IDLE
            resetAllTracking()
            if (ended || wasActive) {
                cooldownUntil = System.currentTimeMillis() + ACTIVATION_COOLDOWN_MS
                reason?.let { Timber.i("Voice gesture ended: %s", it) }
                emitIfChanged(GestureState.Ended)
            } else {
                emitIfChanged(GestureState.Idle)
            }
        }

        while (currentCoroutineContext().isActive) {
            delay(POLL_INTERVAL_MS)

            val now = System.currentTimeMillis()
            if (now < cooldownUntil && phase == ActivationPhase.IDLE) {
                resetAllTracking()
                emitIfChanged(GestureState.Idle)
                continue
            }

            val handState = getVoiceHandState() ?: run {
                transitionToIdle(
                    ended = phase == ActivationPhase.ACTIVE,
                    reason = "hand state unavailable",
                )
                continue
            }

            if (handState.trackingState != TrackingState.TRACKING) {
                transitionToIdle(
                    ended = phase == ActivationPhase.ACTIVE,
                    reason = "tracking lost",
                )
                continue
            }

            val metrics = getHandMetrics(handState) ?: run {
                transitionToIdle(
                    ended = phase == ActivationPhase.ACTIVE,
                    reason = "joint data unavailable",
                )
                continue
            }

            val isHandInActivationZone =
                abs(metrics.wrist.x) < MAX_WRIST_LATERAL_OFFSET &&
                    metrics.wrist.y in MIN_WRIST_HEIGHT..MAX_WRIST_HEIGHT
            val isHandInArmingZone =
                isHandInActivationZone &&
                    abs(metrics.palm.x) < MAX_ARMING_PALM_LATERAL_OFFSET &&
                    metrics.wrist.y >= MIN_ARMING_WRIST_HEIGHT &&
                    metrics.palm.y >= MIN_ARMING_PALM_HEIGHT
            val isOpenPalm =
                isHandInArmingZone &&
                    isOpenPalm(metrics, palmAnchor ?: metrics.palm)

            when (phase) {
                ActivationPhase.IDLE,
                ActivationPhase.ARMING_PALM -> {
                    if (!isOpenPalm) {
                        phase = ActivationPhase.IDLE
                        resetPalmTracking()
                        emitIfChanged(GestureState.Idle)
                        continue
                    }

                    if (phase != ActivationPhase.ARMING_PALM) {
                        phase = ActivationPhase.ARMING_PALM
                        palmStartTime = now
                        palmAnchor = metrics.palm
                        palmValidFrameCount = 0
                        lastProgressBucket = -1
                    }
                    palmValidFrameCount += 1

                    val progress = ((now - palmStartTime).toFloat() / PALM_HOLD_MS).coerceIn(0f, 1f)
                    val progressBucket = (progress * 10).toInt()
                    if (progressBucket != lastProgressBucket && progress < 1f) {
                        emitIfChanged(GestureState.Arming(progress, HOLD_OPEN_PALM_HINT))
                        lastProgressBucket = progressBucket
                    }
                    if (now - palmStartTime >= PALM_HOLD_MS && palmValidFrameCount >= MIN_PALM_VALID_FRAMES) {
                        phase = ActivationPhase.ACTIVE
                        invalidFrameCount = 0
                        lastProgressBucket = -1
                        Timber.i("Voice gesture started for %s hand", preferredHand.lowercase())
                        emitIfChanged(GestureState.Started)
                    }
                }

                ActivationPhase.ACTIVE -> {
                    val staysOpenPalm =
                        isHandInArmingZone &&
                            isOpenPalm(metrics, palmAnchor ?: metrics.palm)
                    val palmTravel =
                        palmAnchor?.let { anchor -> Vector3.distance(anchor, metrics.palm) } ?: 0f
                    if (staysOpenPalm && palmTravel <= RELEASE_PALM_TRAVEL_METERS) {
                        invalidFrameCount = 0
                    } else {
                        invalidFrameCount += 1
                        if (invalidFrameCount >= MIN_RELEASE_FRAMES) {
                            transitionToIdle(ended = true, reason = "palm lowered or moved away")
                        }
                    }
                }
            }
        }
    }

    private fun getHandMetrics(handState: Hand.State): HandMetrics? {
        fun joint(type: HandJointType): Vector3? = handState.handJoints[type]?.translation

        return HandMetrics(
            wrist = joint(HandJointType.HAND_JOINT_TYPE_WRIST) ?: return null,
            palm = joint(HandJointType.HAND_JOINT_TYPE_PALM) ?: return null,
            thumbTip = joint(HandJointType.HAND_JOINT_TYPE_THUMB_TIP) ?: return null,
            thumbProximal = joint(HandJointType.HAND_JOINT_TYPE_THUMB_PROXIMAL) ?: return null,
            indexTip = joint(HandJointType.HAND_JOINT_TYPE_INDEX_TIP) ?: return null,
            indexProximal = joint(HandJointType.HAND_JOINT_TYPE_INDEX_PROXIMAL) ?: return null,
            indexMetacarpal = joint(HandJointType.HAND_JOINT_TYPE_INDEX_METACARPAL) ?: return null,
            middleTip = joint(HandJointType.HAND_JOINT_TYPE_MIDDLE_TIP) ?: return null,
            middleProximal = joint(HandJointType.HAND_JOINT_TYPE_MIDDLE_PROXIMAL) ?: return null,
            ringTip = joint(HandJointType.HAND_JOINT_TYPE_RING_TIP) ?: return null,
            ringProximal = joint(HandJointType.HAND_JOINT_TYPE_RING_PROXIMAL) ?: return null,
            littleTip = joint(HandJointType.HAND_JOINT_TYPE_LITTLE_TIP) ?: return null,
            littleProximal = joint(HandJointType.HAND_JOINT_TYPE_LITTLE_PROXIMAL) ?: return null,
        )
    }

    private fun isOpenPalm(metrics: HandMetrics, anchor: Vector3): Boolean {
        val isStable = Vector3.distance(anchor, metrics.palm) <= MAX_PALM_TRAVEL_METERS
        val extendedFingers =
            listOf(
                fingerExtensionRatio(metrics.indexTip, metrics.indexProximal, metrics.palm),
                fingerExtensionRatio(metrics.middleTip, metrics.middleProximal, metrics.palm),
                fingerExtensionRatio(metrics.ringTip, metrics.ringProximal, metrics.palm),
                fingerExtensionRatio(metrics.littleTip, metrics.littleProximal, metrics.palm),
            )
        val extendedCount = extendedFingers.count { it >= MIN_FINGER_EXTENSION_RATIO }
        val thumbExtended =
            fingerExtensionRatio(metrics.thumbTip, metrics.thumbProximal, metrics.palm) >= MIN_THUMB_EXTENSION_RATIO
        val spreadSatisfied =
            Vector3.distance(metrics.indexTip, metrics.middleTip) >= MIN_FINGER_SPREAD_METERS &&
                Vector3.distance(metrics.middleTip, metrics.ringTip) >= MIN_FINGER_SPREAD_METERS &&
                Vector3.distance(metrics.ringTip, metrics.littleTip) >= MIN_FINGER_SPREAD_METERS
        val thumbSeparated =
            Vector3.distance(metrics.thumbTip, metrics.indexTip) >= MIN_THUMB_SEPARATION_METERS &&
                Vector3.distance(metrics.thumbTip, metrics.indexMetacarpal) >= MIN_THUMB_SEPARATION_METERS

        return isStable && extendedCount >= 4 && thumbExtended && spreadSatisfied && thumbSeparated
    }

    private fun fingerExtensionRatio(tip: Vector3, proximal: Vector3, palm: Vector3): Float {
        val proximalDistance = Vector3.distance(proximal, palm).coerceAtLeast(0.001f)
        return Vector3.distance(tip, palm) / proximalDistance
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
