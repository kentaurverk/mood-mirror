package com.kentaurverk.moodmirror.ml

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import com.kentaurverk.moodmirror.domain.ExpressionLabel
import com.kentaurverk.moodmirror.domain.ExpressionResult
import com.kentaurverk.moodmirror.domain.ExpressionScores
import com.kentaurverk.moodmirror.domain.FaceRect
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession

/** Offline expression pipeline: MediaPipe landmarks + the bundled ONNX classifier. */
class ExpressionEngine(
    private val context: Context,
    private val onResult: (ExpressionResult) -> Unit,
) : AutoCloseable {
    private var faceLandmarker: FaceLandmarker? = null
    private var ortEnvironment: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private val pendingFrames = ConcurrentHashMap<Long, Bitmap>()
    private val processing = AtomicBoolean(false)

    suspend fun initialize() = withContext(Dispatchers.Default) {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(FACE_LANDMARKER_ASSET)
            .build()
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.55f)
            .setMinFacePresenceConfidence(0.55f)
            .setMinTrackingConfidence(0.55f)
            .setResultListener(this@ExpressionEngine::onLandmarkerResult)
            .setErrorListener { error ->
                pendingFrames.values.forEach { it.recycle() }
                pendingFrames.clear()
                onResult(ExpressionResult(detected = false, errorMessage = error.message ?: "MediaPipe error"))
                processing.set(false)
            }
            .build()

        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
        ortEnvironment = OrtEnvironment.getEnvironment()
        val modelBytes = context.assets.open(EXPRESSION_MODEL_ASSET).use { it.readBytes() }
        ortSession = ortEnvironment?.createSession(modelBytes, OrtSession.SessionOptions())
    }

    fun submitFrame(bitmap: Bitmap, timestampMs: Long) {
        val landmarker = faceLandmarker ?: run {
            bitmap.recycle()
            return
        }
        if (!processing.compareAndSet(false, true)) {
            bitmap.recycle()
            return
        }
        pendingFrames[timestampMs] = bitmap
        try {
            val image = BitmapImageBuilder(bitmap).build()
            landmarker.detectAsync(image, timestampMs)
        } catch (exception: Exception) {
            pendingFrames.remove(timestampMs)?.recycle()
            processing.set(false)
            onResult(ExpressionResult(detected = false, errorMessage = exception.message ?: "Frame rejected"))
        }
    }

    private fun onLandmarkerResult(result: FaceLandmarkerResult, input: com.google.mediapipe.framework.image.MPImage) {
        val timestamp = result.timestampMs()
        val source = pendingFrames.remove(timestamp)
        try {
            val landmarks = result.faceLandmarks().firstOrNull()
            if (landmarks == null || source == null) {
                onResult(ExpressionResult(detected = false, latencyMs = SystemClock.elapsedRealtime() - timestamp))
                return
            }
            val xValues = landmarks.map { it.x() }
            val yValues = landmarks.map { it.y() }
            val cropRect = FaceRect(
                left = (xValues.minOrNull() ?: 0f).coerceIn(0f, 1f),
                top = (yValues.minOrNull() ?: 0f).coerceIn(0f, 1f),
                right = (xValues.maxOrNull() ?: 1f).coerceIn(0f, 1f),
                bottom = (yValues.maxOrNull() ?: 1f).coerceIn(0f, 1f),
            )
            val faceBitmap = cropFace(source, cropRect)
            val scores = runClassifier(faceBitmap)
            faceBitmap.recycle()
            onResult(
                ExpressionResult(
                    detected = true,
                    scores = scores,
                    faceRect = cropRect,
                    latencyMs = SystemClock.elapsedRealtime() - timestamp,
                ),
            )
        } catch (exception: Exception) {
            onResult(ExpressionResult(detected = false, errorMessage = exception.message ?: "Expression analysis failed"))
        } finally {
            source?.recycle()
            processing.set(false)
        }
    }

    private fun cropFace(bitmap: Bitmap, normalized: FaceRect): Bitmap {
        val width = normalized.right - normalized.left
        val height = normalized.bottom - normalized.top
        val left = (normalized.left - width * HORIZONTAL_PADDING).coerceIn(0f, 1f)
        val top = (normalized.top - height * TOP_PADDING).coerceIn(0f, 1f)
        val right = (normalized.right + width * HORIZONTAL_PADDING).coerceIn(0f, 1f)
        val bottom = (normalized.bottom + height * BOTTOM_PADDING).coerceIn(0f, 1f)
        val x = (left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val y = (top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        val cropWidth = ((right - left) * bitmap.width).toInt().coerceAtLeast(1).coerceAtMost(bitmap.width - x)
        val cropHeight = ((bottom - top) * bitmap.height).toInt().coerceAtLeast(1).coerceAtMost(bitmap.height - y)
        return Bitmap.createBitmap(bitmap, x, y, cropWidth, cropHeight)
    }

    private fun runClassifier(face: Bitmap): ExpressionScores {
        val environment = ortEnvironment ?: error("ONNX environment is not ready")
        val session = ortSession ?: error("ONNX session is not ready")
        val resized = Bitmap.createScaledBitmap(face, MODEL_SIZE, MODEL_SIZE, true)
        val pixels = IntArray(MODEL_SIZE * MODEL_SIZE)
        resized.getPixels(pixels, 0, MODEL_SIZE, 0, 0, MODEL_SIZE, MODEL_SIZE)
        if (resized !== face) resized.recycle()
        val input = FloatArray(3 * MODEL_SIZE * MODEL_SIZE)
        val planeSize = MODEL_SIZE * MODEL_SIZE
        for (index in pixels.indices) {
            val pixel = pixels[index]
            input[index] = ((pixel shr 16 and 0xff) / 255f - MEAN_RED) / STD_RED
            input[planeSize + index] = ((pixel shr 8 and 0xff) / 255f - MEAN_GREEN) / STD_GREEN
            input[planeSize * 2 + index] = ((pixel and 0xff) / 255f - MEAN_BLUE) / STD_BLUE
        }
        val tensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(input), longArrayOf(1, 3, MODEL_SIZE.toLong(), MODEL_SIZE.toLong()))
        tensor.use {
            session.run(mapOf(session.inputNames.first() to tensor)).use { outputs ->
                val raw = outputs[0].value
                val row = when (raw) {
                    is Array<*> -> raw.firstOrNull() as? FloatArray
                    is FloatArray -> raw
                    else -> null
                } ?: error("Unexpected classifier output")
                val probabilities = softmax(row)
                val modelOrder = listOf(
                    ExpressionLabel.ANGRY,
                    ExpressionLabel.DISGUSTED,
                    ExpressionLabel.FEARFUL,
                    ExpressionLabel.HAPPY,
                    ExpressionLabel.NEUTRAL,
                    ExpressionLabel.SAD,
                    ExpressionLabel.SURPRISED,
                )
                return ExpressionScores(modelOrder.mapIndexed { index, label -> label to probabilities.getOrElse(index) { 0f } }.toMap())
            }
        }
    }

    private fun softmax(values: FloatArray): FloatArray {
        val max = values.maxOrNull() ?: 0f
        val exponentials = values.map { kotlin.math.exp((it - max).toDouble()).toFloat() }
        val total = exponentials.sum().coerceAtLeast(1e-8f)
        return exponentials.map { it / total }.toFloatArray()
    }

    override fun close() {
        pendingFrames.values.forEach { it.recycle() }
        pendingFrames.clear()
        faceLandmarker?.close()
        ortSession?.close()
        faceLandmarker = null
        ortSession = null
        ortEnvironment = null
    }

    companion object {
        private const val FACE_LANDMARKER_ASSET = "face_landmarker.task"
        private const val EXPRESSION_MODEL_ASSET = "enet_b2_7.onnx"
        private const val MODEL_SIZE = 260
        private const val HORIZONTAL_PADDING = 0.50f
        private const val TOP_PADDING = 0.50f
        private const val BOTTOM_PADDING = 0.35f
        private const val MEAN_RED = 0.485f
        private const val MEAN_GREEN = 0.456f
        private const val MEAN_BLUE = 0.406f
        private const val STD_RED = 0.229f
        private const val STD_GREEN = 0.224f
        private const val STD_BLUE = 0.225f
    }
}
