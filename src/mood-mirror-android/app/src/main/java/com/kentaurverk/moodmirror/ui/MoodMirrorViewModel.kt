package com.kentaurverk.moodmirror.ui

import android.app.Application
import android.os.SystemClock
import android.view.TextureView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kentaurverk.moodmirror.camera.CameraController
import com.kentaurverk.moodmirror.domain.ExpressionLabel
import com.kentaurverk.moodmirror.domain.ExpressionResult
import com.kentaurverk.moodmirror.domain.ExpressionScores
import com.kentaurverk.moodmirror.domain.CameraStatus
import com.kentaurverk.moodmirror.domain.ErrorKind
import com.kentaurverk.moodmirror.domain.MoodMirrorUiState
import com.kentaurverk.moodmirror.domain.MoodSample
import com.kentaurverk.moodmirror.domain.MoodTonePlayer
import com.kentaurverk.moodmirror.domain.SessionMode
import com.kentaurverk.moodmirror.ml.ExpressionEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MoodMirrorViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences(PREFERENCES, 0)
    private val _uiState = MutableStateFlow(
        MoodMirrorUiState(languageTag = preferences.getString(KEY_LANGUAGE, "en") ?: "en"),
    )
    val uiState: StateFlow<MoodMirrorUiState> = _uiState.asStateFlow()

    private val tonePlayer = MoodTonePlayer()
    private var cameraController: CameraController? = null
    private var expressionEngine: ExpressionEngine? = null
    private var demoJob: Job? = null
    private var noFaceJob: Job? = null
    private var lastLabel: ExpressionLabel? = null
    private var pendingDogLabel: ExpressionLabel? = null
    private var pendingDogCount = 0
    private var lastFaceAt = 0L
    private var liveSessionActive = false
    private val initializing = AtomicBoolean(false)

    fun attachPreview(view: TextureView) {
        cameraController?.close()
        cameraController = CameraController(
            textureView = view,
            onFrame = { bitmap, timestamp -> expressionEngine?.submitFrame(bitmap, timestamp) ?: bitmap.recycle() },
            onError = { message -> _uiState.update { it.copy(errorMessage = message, errorKind = ErrorKind.CAMERA, cameraStatus = CameraStatus.ERROR, cameraRunning = false) } },
            onUnsupported = { _uiState.update { it.copy(cameraStatus = CameraStatus.UNSUPPORTED, cameraRunning = false) } },
        )
        // The live TextureView is created by Compose after startCamera() flips
        // cameraRunning. Start the newly attached controller immediately.
        if (_uiState.value.cameraRunning && _uiState.value.mode == SessionMode.CAMERA) {
            cameraController?.start()
        }
    }

    fun setCameraPermission(granted: Boolean, canRequestAgain: Boolean = true) {
        _uiState.update {
            it.copy(
                cameraPermissionGranted = granted,
                permissionPermanentlyDenied = !granted && !canRequestAgain,
                cameraStatus = if (granted) CameraStatus.READY else if (canRequestAgain) CameraStatus.PERMISSION_REQUIRED else CameraStatus.PERMISSION_DENIED,
            )
        }
    }

    fun markCameraPermissionRequest() {
        preferences.edit().putBoolean(KEY_PERMISSION_ATTEMPTED, true).apply()
    }

    fun cameraPermissionWasAttempted(): Boolean = preferences.getBoolean(KEY_PERMISSION_ATTEMPTED, false)

    fun openAbout() = _uiState.update { it.copy(showAbout = true) }

    fun closeAbout() = _uiState.update { it.copy(showAbout = false) }

    fun initializeModel() {
        if (!initializing.compareAndSet(false, true) || expressionEngine != null) return
        viewModelScope.launch {
            try {
                val engine = ExpressionEngine(getApplication()) { result -> handleResult(result, isDemo = false) }
                engine.initialize()
                expressionEngine = engine
                _uiState.update { it.copy(modelLoading = false, errorMessage = null, cameraStatus = if (it.cameraPermissionGranted) CameraStatus.READY else it.cameraStatus) }
                if (_uiState.value.cameraPermissionGranted && _uiState.value.mode == SessionMode.CAMERA && _uiState.value.cameraRunning) cameraController?.start()
            } catch (exception: Exception) {
                _uiState.update { it.copy(modelLoading = false, errorMessage = exception.message ?: "Unable to load offline models", errorKind = ErrorKind.MODEL, cameraStatus = CameraStatus.ERROR) }
            } finally {
                initializing.set(false)
            }
        }
    }

    fun startCamera() {
        if (!_uiState.value.cameraPermissionGranted) return
        demoJob?.cancel()
        liveSessionActive = true
        resetSession()
        _uiState.update { it.copy(mode = SessionMode.CAMERA, cameraStatus = if (it.modelLoading) CameraStatus.WARMING else CameraStatus.FINDING_FACE, cameraRunning = true, errorMessage = null) }
        cameraController?.start()
    }

    fun stopCamera() {
        cameraController?.stop()
        liveSessionActive = false
        _uiState.update { it.copy(cameraRunning = false, cameraStatus = CameraStatus.READY, faceDetected = false, confidence = 0f) }
    }

    fun onAppBackgrounded() {
        demoJob?.cancel()
        demoJob = null
        stopCamera()
        _uiState.update { it.copy(mode = SessionMode.CAMERA, cameraStatus = if (it.cameraPermissionGranted) CameraStatus.READY else it.cameraStatus) }
    }

    fun toggleDogMode() = _uiState.update { it.copy(isDogMode = !it.isDogMode) }

    fun toggleSound() = _uiState.update { it.copy(soundEnabled = !it.soundEnabled) }

    fun toggleLanguage() {
        val next = if (_uiState.value.languageTag == "en") "it" else "en"
        preferences.edit().putString(KEY_LANGUAGE, next).apply()
        _uiState.update { it.copy(languageTag = next) }
    }

    fun startDemo() {
        cameraController?.stop()
        demoJob?.cancel()
        noFaceJob?.cancel()
        liveSessionActive = false
        resetSession()
        _uiState.update { it.copy(mode = SessionMode.DEMO, cameraRunning = false, cameraStatus = CameraStatus.DEMO, faceDetected = true, errorMessage = null) }
        demoJob = viewModelScope.launch {
            val sequence = listOf(ExpressionLabel.HAPPY, ExpressionLabel.SURPRISED, ExpressionLabel.SAD, ExpressionLabel.ANGRY, ExpressionLabel.DISGUSTED, ExpressionLabel.FEARFUL, ExpressionLabel.NEUTRAL)
            var index = 0
            while (isActive) {
                val label = sequence[index++ % sequence.size]
                val values = ExpressionLabel.entries.associateWith { mood -> if (mood == label) 0.72f else 0.28f / 6f }
                handleResult(ExpressionResult(detected = true, scores = ExpressionScores(values), latencyMs = 34), isDemo = true)
                delay(1_450)
            }
        }
    }

    private fun handleResult(result: ExpressionResult, isDemo: Boolean) {
        if (!isDemo && (!_uiState.value.cameraRunning || _uiState.value.mode != SessionMode.CAMERA || !liveSessionActive)) return
        if (result.errorMessage != null) {
            _uiState.update { it.copy(errorMessage = result.errorMessage, errorKind = ErrorKind.MODEL, cameraStatus = CameraStatus.ERROR) }
            return
        }
        if (!result.detected) {
            if (_uiState.value.mode == SessionMode.CAMERA) {
                _uiState.update { it.copy(faceDetected = false) }
                _uiState.update { it.copy(cameraStatus = CameraStatus.FINDING_FACE) }
                noFaceJob?.cancel()
                noFaceJob = viewModelScope.launch {
                    delay(NO_FACE_GRACE_MS)
                    if (SystemClock.elapsedRealtime() - lastFaceAt >= NO_FACE_GRACE_MS) {
                        _uiState.update { it.copy(confidence = 0f) }
                    }
                }
            }
            return
        }
        lastFaceAt = SystemClock.elapsedRealtime()
        val previous = _uiState.value.scores
        val smoothed = previous.blend(result.scores)
        val label = smoothed.maxLabel()
        val confidence = smoothed[label]
        val sample = MoodSample(label, confidence, lastFaceAt)
        val history = (_uiState.value.history + sample).takeLast(HISTORY_SIZE)
        val previousLabel = lastLabel
        lastLabel = label
        _uiState.update {
            it.copy(
                faceDetected = true,
                cameraStatus = if (_uiState.value.mode == SessionMode.DEMO) CameraStatus.DEMO else CameraStatus.SIGNAL_LOCKED,
                currentLabel = label,
                confidence = confidence,
                scores = smoothed,
                history = history,
                sampleCount = it.sampleCount + 1,
                statusMessage = null,
                errorMessage = null,
            )
        }
        if (pendingDogLabel == label) {
            pendingDogCount += 1
        } else {
            pendingDogLabel = label
            pendingDogCount = 1
        }
        if (pendingDogCount >= DOG_CONFIRMATIONS && _uiState.value.dogLabel != label) {
            _uiState.update { it.copy(dogLabel = label) }
            pendingDogCount = 0
        }
        if (_uiState.value.soundEnabled && previousLabel != null && previousLabel != label) tonePlayer.play(label)
    }

    private fun resetSession() {
        noFaceJob?.cancel()
        lastLabel = null
        pendingDogLabel = null
        pendingDogCount = 0
        lastFaceAt = 0L
        _uiState.update {
            it.copy(
                faceDetected = false,
                cameraStatus = if (_uiState.value.mode == SessionMode.DEMO) CameraStatus.DEMO else CameraStatus.READY,
                currentLabel = ExpressionLabel.NEUTRAL,
                dogLabel = ExpressionLabel.NEUTRAL,
                confidence = 0f,
                scores = ExpressionScores(ExpressionLabel.entries.associateWith { if (it == ExpressionLabel.NEUTRAL) 1f else 0f }),
                history = emptyList(),
                sampleCount = 0,
            )
        }
    }

    override fun onCleared() {
        demoJob?.cancel()
        noFaceJob?.cancel()
        cameraController?.close()
        expressionEngine?.close()
        cameraController = null
        expressionEngine = null
        super.onCleared()
    }

    companion object {
        private const val PREFERENCES = "mood_mirror_preferences"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_PERMISSION_ATTEMPTED = "camera_permission_attempted"
        private const val NO_FACE_GRACE_MS = 1_500L
        private const val HISTORY_SIZE = 42
        private const val DOG_CONFIRMATIONS = 3
    }
}
