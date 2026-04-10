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
    private val shouldDetectActivation: () -> Boolean = { true },
    private val shouldDetectInterrupt: () -> Boolean = { false },
) {
    enum class GestureType {
        ACTIVATE,
        INTERRUPT,
    }

    sealed interface GestureState {
        data object Idle : GestureState

        data class Arming(
            val gestureType: GestureType,
            val progress: Float,
            val hint: String,
        ) : GestureState

        data class Started(val gestureType: GestureType) : GestureState

        data class Ended(val gestureType: GestureType) : GestureState
    }

    private enum class ActivationPhase {
        IDLE,
        ARMING,
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
        private const val FIST_HOLD_MS = 150L
        private const val RELEASE_PALM_TRAVEL_METERS = 0.080f
        private const val RELEASE_FIST_TRAVEL_METERS = 0.085f
        private const val POLL_INTERVAL_MS = 30L
        private const val MIN_PALM_VALID_FRAMES = 6
        private const val MIN_FIST_VALID_FRAMES = 3
        private const val MAX_PALM_TRAVEL_METERS = 0.055f
        private const val MAX_FIST_TRAVEL_METERS = 0.070f
        private const val MIN_FINGER_EXTENSION_RATIO = 1.18f
        private const val MIN_THUMB_EXTENSION_RATIO = 1.10f
        private const val MAX_FINGER_CURL_RATIO = 1.35f
        private const val MAX_THUMB_CURL_RATIO = 1.45f
        private const val MIN_FINGER_SPREAD_METERS = 0.018f
        private const val MIN_THUMB_SEPARATION_METERS = 0.040f
        private const val MAX_FINGER_CLUSTER_METERS = 0.090f
        private const val MAX_THUMB_TO_PALM_METERS = 0.135f
        private const val MAX_THUMB_TO_INDEX_METACARPAL_METERS = 0.120f
        private const val MIN_RELEASE_FRAMES = 2
        private const val ACTIVATION_COOLDOWN_MS = 450L
        private const val MAX_WRIST_HEIGHT = 0.32f
        private const val MIN_WRIST_HEIGHT = -0.68f
        private const val MAX_WRIST_LATERAL_OFFSET = 0.60f
        private const val MAX_ARMING_PALM_LATERAL_OFFSET = 0.45f
        private const val MIN_ARMING_WRIST_HEIGHT = -0.24f
        private const val MIN_ARMING_PALM_HEIGHT = -0.12f
        private const val HOLD_OPEN_PALM_HINT = "Hold palm to talk"
        private const val HOLD_FIST_HINT = "Hold fist to interrupt"
        private const val RAISE_PALM_HINT = "Raise palm to face"
    }

    private val hand: Hand? by lazy {
        when (preferredHand.lowercase()) {
            "right" -> Hand.right(session)
            else -> Hand.left(session)
        }
    }

    val gestureStates: Flow<GestureState> = flow {
        var phase = ActivationPhase.IDLE
        var activeGestureType: GestureType? = null
        var validFrameCount = 0
        var invalidFrameCount = 0
        var gestureStartTime = 0L
        var gestureAnchor: Vector3? = null
        var cooldownUntil = 0L
        var lastProgressBucket = -1
        var lastState: GestureState = GestureState.Idle

        suspend fun emitIfChanged(state: GestureState) {
            val shouldEmit =
                when {
                    state::class != lastState::class -> true
                    state is GestureState.Arming && lastState is GestureState.Arming -> {
                        val previous = lastState as GestureState.Arming
                        state.gestureType != previous.gestureType ||
                            (state.progress * 10).toInt() != (previous.progress * 10).toInt() ||
                            state.hint != previous.hint
                    }
                    state != lastState -> true
                    else -> false
                }
            if (shouldEmit) {
                emit(state)
                lastState = state
            }
        }

        fun resetGestureTracking() {
            activeGestureType = null
            validFrameCount = 0
            gestureStartTime = 0L
            gestureAnchor = null
        }

        fun resetAllTracking() {
            resetGestureTracking()
            invalidFrameCount = 0
            lastProgressBucket = -1
        }

        suspend fun transitionToIdle(ended: Boolean, reason: String? = null) {
            val wasActive = phase == ActivationPhase.ACTIVE
            val endedGestureType = activeGestureType
            phase = ActivationPhase.IDLE
            resetAllTracking()
            if (ended || wasActive) {
                cooldownUntil = System.currentTimeMillis() + ACTIVATION_COOLDOWN_MS
                reason?.let {
                    Timber.i(
                        "Voice gesture ended: type=%s reason=%s",
                        endedGestureType,
                        it,
                    )
                }
                endedGestureType?.let { emitIfChanged(GestureState.Ended(it)) }
            } else {
                emitIfChanged(GestureState.Idle)
            }
        }

        fun isGestureEnabled(type: GestureType): Boolean =
            when (type) {
                GestureType.ACTIVATE -> shouldDetectActivation()
                GestureType.INTERRUPT -> shouldDetectInterrupt()
            }

        fun isGestureShape(type: GestureType, metrics: HandMetrics, anchor: Vector3): Boolean =
            when (type) {
                GestureType.ACTIVATE -> isOpenPalm(metrics, anchor)
                GestureType.INTERRUPT -> isClosedFist(metrics, anchor)
            }

        fun holdHint(type: GestureType): String =
            when (type) {
                GestureType.ACTIVATE -> HOLD_OPEN_PALM_HINT
                GestureType.INTERRUPT -> HOLD_FIST_HINT
            }

        fun holdMs(type: GestureType): Long =
            when (type) {
                GestureType.ACTIVATE -> PALM_HOLD_MS
                GestureType.INTERRUPT -> FIST_HOLD_MS
            }

        fun minValidFrames(type: GestureType): Int =
            when (type) {
                GestureType.ACTIVATE -> MIN_PALM_VALID_FRAMES
                GestureType.INTERRUPT -> MIN_FIST_VALID_FRAMES
            }

        fun releaseTravelMeters(type: GestureType): Float =
            when (type) {
                GestureType.ACTIVATE -> RELEASE_PALM_TRAVEL_METERS
                GestureType.INTERRUPT -> RELEASE_FIST_TRAVEL_METERS
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
            val anchor = gestureAnchor ?: metrics.palm
            val hasOpenPalmShape = isOpenPalm(metrics, anchor)
            val hasClosedFistShape = isClosedFist(metrics, anchor)
            val candidateGestureType =
                when {
                    shouldDetectInterrupt() && isHandInActivationZone && hasClosedFistShape -> GestureType.INTERRUPT
                    shouldDetectActivation() && isHandInActivationZone && hasOpenPalmShape -> GestureType.ACTIVATE
                    else -> null
                }

            when (phase) {
                ActivationPhase.IDLE,
                ActivationPhase.ARMING -> {
                    val gestureType = activeGestureType ?: candidateGestureType
                    if (gestureType == null) {
                        phase = ActivationPhase.IDLE
                        resetGestureTracking()
                        emitIfChanged(GestureState.Idle)
                        continue
                    }

                    val gestureMatches =
                        isGestureEnabled(gestureType) &&
                            isHandInActivationZone &&
                            isGestureShape(gestureType, metrics, anchor)
                    if (!gestureMatches) {
                        phase = ActivationPhase.IDLE
                        resetGestureTracking()
                        emitIfChanged(GestureState.Idle)
                        continue
                    }

                    val isGestureInReadyZone =
                        when (gestureType) {
                            GestureType.ACTIVATE -> isHandInArmingZone
                            GestureType.INTERRUPT -> isHandInActivationZone
                        }
                    if (!isGestureInReadyZone) {
                        phase = ActivationPhase.IDLE
                        resetGestureTracking()
                        if (gestureType == GestureType.ACTIVATE) {
                            emitIfChanged(GestureState.Arming(gestureType, 0f, RAISE_PALM_HINT))
                        } else {
                            emitIfChanged(GestureState.Idle)
                        }
                        continue
                    }

                    if (phase != ActivationPhase.ARMING || activeGestureType != gestureType) {
                        phase = ActivationPhase.ARMING
                        activeGestureType = gestureType
                        gestureStartTime = now
                        gestureAnchor = metrics.palm
                        validFrameCount = 0
                        lastProgressBucket = -1
                    }
                    validFrameCount += 1

                    val progress = ((now - gestureStartTime).toFloat() / holdMs(gestureType)).coerceIn(0f, 1f)
                    val progressBucket = (progress * 10).toInt()
                    if (progressBucket != lastProgressBucket && progress < 1f) {
                        emitIfChanged(GestureState.Arming(gestureType, progress, holdHint(gestureType)))
                        lastProgressBucket = progressBucket
                    }
                    if (now - gestureStartTime >= holdMs(gestureType) && validFrameCount >= minValidFrames(gestureType)) {
                        phase = ActivationPhase.ACTIVE
                        invalidFrameCount = 0
                        lastProgressBucket = -1
                        Timber.i(
                            "Voice gesture started: type=%s hand=%s",
                            gestureType,
                            preferredHand.lowercase(),
                        )
                        emitIfChanged(GestureState.Started(gestureType))
                    }
                }

                ActivationPhase.ACTIVE -> {
                    val gestureType = activeGestureType ?: run {
                        transitionToIdle(ended = false, reason = "gesture type unavailable")
                        continue
                    }
                    val staysInGesture =
                        isGestureEnabled(gestureType) &&
                            (
                                when (gestureType) {
                                    GestureType.ACTIVATE -> isHandInArmingZone
                                    GestureType.INTERRUPT -> isHandInActivationZone
                                }
                            ) &&
                            isGestureShape(gestureType, metrics, gestureAnchor ?: metrics.palm)
                    val gestureTravel =
                        gestureAnchor?.let { gestureAnchor ->
                            Vector3.distance(gestureAnchor, metrics.palm)
                        } ?: 0f
                    if (staysInGesture && gestureTravel <= releaseTravelMeters(gestureType)) {
                        invalidFrameCount = 0
                    } else {
                        invalidFrameCount += 1
                        if (invalidFrameCount >= MIN_RELEASE_FRAMES) {
                            transitionToIdle(
                                ended = true,
                                reason =
                                    when (gestureType) {
                                        GestureType.ACTIVATE -> "palm lowered or moved away"
                                        GestureType.INTERRUPT -> "fist released or moved away"
                                    },
                            )
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

    private fun isClosedFist(metrics: HandMetrics, anchor: Vector3): Boolean {
        val isStable = Vector3.distance(anchor, metrics.palm) <= MAX_FIST_TRAVEL_METERS
        val curledFingers =
            listOf(
                fingerExtensionRatio(metrics.indexTip, metrics.indexProximal, metrics.palm),
                fingerExtensionRatio(metrics.middleTip, metrics.middleProximal, metrics.palm),
                fingerExtensionRatio(metrics.ringTip, metrics.ringProximal, metrics.palm),
                fingerExtensionRatio(metrics.littleTip, metrics.littleProximal, metrics.palm),
            )
        val curledCount = curledFingers.count { it <= MAX_FINGER_CURL_RATIO }
        val thumbCurled =
            fingerExtensionRatio(metrics.thumbTip, metrics.thumbProximal, metrics.palm) <= MAX_THUMB_CURL_RATIO
        val fingertipsClustered =
            Vector3.distance(metrics.indexTip, metrics.middleTip) <= MAX_FINGER_CLUSTER_METERS &&
                Vector3.distance(metrics.middleTip, metrics.ringTip) <= MAX_FINGER_CLUSTER_METERS &&
                Vector3.distance(metrics.ringTip, metrics.littleTip) <= MAX_FINGER_CLUSTER_METERS
        val thumbNearPalm =
            Vector3.distance(metrics.thumbTip, metrics.palm) <= MAX_THUMB_TO_PALM_METERS ||
                Vector3.distance(metrics.thumbTip, metrics.indexMetacarpal) <= MAX_THUMB_TO_INDEX_METACARPAL_METERS

        return isStable &&
            curledCount >= 3 &&
            fingertipsClustered &&
            (thumbCurled || thumbNearPalm)
    }

    private fun fingerExtensionRatio(tip: Vector3, proximal: Vector3, palm: Vector3): Float {
        val proximalDistance = Vector3.distance(proximal, palm).coerceAtLeast(0.001f)
        return Vector3.distance(tip, palm) / proximalDistance
    }

    private fun getVoiceHandState(): Hand.State? {
        return hand?.state?.value
    }
}
