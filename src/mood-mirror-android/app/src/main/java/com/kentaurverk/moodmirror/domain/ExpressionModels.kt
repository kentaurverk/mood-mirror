package com.kentaurverk.moodmirror.domain

import androidx.annotation.StringRes
import com.kentaurverk.moodmirror.R

enum class ExpressionLabel(
    @StringRes val labelRes: Int,
    val emoji: String,
) {
    HAPPY(R.string.mood_happy, "🙂"),
    SURPRISED(R.string.mood_surprised, "😮"),
    SAD(R.string.mood_sad, "😢"),
    ANGRY(R.string.mood_angry, "😠"),
    DISGUSTED(R.string.mood_disgusted, "🤢"),
    FEARFUL(R.string.mood_fearful, "😨"),
    NEUTRAL(R.string.mood_neutral, "😐"),
}

val expressionLabels = ExpressionLabel.entries.toList()

data class ExpressionScores(
    val values: Map<ExpressionLabel, Float> = expressionLabels.associateWith { 0f },
) {
    operator fun get(label: ExpressionLabel): Float = values[label] ?: 0f

    fun maxLabel(): ExpressionLabel = values.maxByOrNull { it.value }?.key ?: ExpressionLabel.NEUTRAL

    fun blend(other: ExpressionScores, previousWeight: Float = 0.52f): ExpressionScores {
        val nextWeight = 1f - previousWeight
        return ExpressionScores(expressionLabels.associateWith { label ->
            (this[label] * previousWeight + other[label] * nextWeight).coerceIn(0f, 1f)
        })
    }
}

data class FaceRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

data class ExpressionResult(
    val detected: Boolean,
    val scores: ExpressionScores = ExpressionScores(),
    val faceRect: FaceRect? = null,
    val latencyMs: Long? = null,
    val errorMessage: String? = null,
)

enum class SessionMode { CAMERA, DEMO }

enum class CameraStatus {
    READY,
    WARMING,
    FINDING_FACE,
    SIGNAL_LOCKED,
    DEMO,
    PERMISSION_REQUIRED,
    PERMISSION_DENIED,
    UNSUPPORTED,
    ERROR,
}

enum class ErrorKind { MODEL, CAMERA }

data class MoodSample(
    val label: ExpressionLabel,
    val confidence: Float,
    val timestampMs: Long,
)

data class MoodMirrorUiState(
    val mode: SessionMode = SessionMode.CAMERA,
    val cameraStatus: CameraStatus = CameraStatus.READY,
    val isDogMode: Boolean = false,
    val soundEnabled: Boolean = false,
    val languageTag: String = "en",
    val cameraPermissionGranted: Boolean = false,
    val permissionPermanentlyDenied: Boolean = false,
    val cameraRunning: Boolean = false,
    val modelLoading: Boolean = true,
    val faceDetected: Boolean = false,
    val currentLabel: ExpressionLabel = ExpressionLabel.NEUTRAL,
    val dogLabel: ExpressionLabel = ExpressionLabel.NEUTRAL,
    val confidence: Float = 0f,
    val scores: ExpressionScores = ExpressionScores(),
    val history: List<MoodSample> = emptyList(),
    val sampleCount: Int = 0,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val errorKind: ErrorKind = ErrorKind.MODEL,
    val showAbout: Boolean = false,
)
