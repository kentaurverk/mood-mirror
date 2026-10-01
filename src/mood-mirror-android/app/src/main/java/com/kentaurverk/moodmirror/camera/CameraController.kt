package com.kentaurverk.moodmirror.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import androidx.core.content.ContextCompat
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera2 controller used instead of CameraX on Samsung tablets.
 *
 * The SM-P613 exposes several virtual camera IDs. CameraX enumerates all of
 * them during provider startup and can stall before delivering a provider.
 * Camera2 lets us select the real front-facing sensor directly and keeps the
 * app fully offline.
 */
class CameraController(
    private val textureView: TextureView,
    private val onFrame: (Bitmap, Long) -> Unit,
    private val onError: (String) -> Unit,
    private val onUnsupported: () -> Unit,
) : AutoCloseable {
    private val context: Context = textureView.context
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraThread = HandlerThread("mood-mirror-camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var sensorOrientation = 0
    private var running = false
    private var starting = false
    private val frameInFlight = AtomicBoolean(false)
    private val previewWidth = 640
    private val previewHeight = 480
    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
            applyPreviewTransform(width, height)
            openCameraIfReady()
        }

        override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
            applyPreviewTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean {
            stop()
            return true
        }

        override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) = Unit
    }

    fun start() {
        if (running || starting) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onError("Camera permission is required")
            return
        }
        starting = true
        textureView.surfaceTextureListener = surfaceListener
        if (textureView.isAvailable) openCameraIfReady()
    }

    private fun openCameraIfReady() {
        if (!starting || cameraDevice != null) return
        try {
            val selected = selectFrontCamera() ?: run {
                starting = false
                onUnsupported()
                return
            }
            sensorOrientation = selected.second
            val surfaceTexture = textureView.surfaceTexture ?: return
            surfaceTexture.setDefaultBufferSize(previewWidth, previewHeight)
            previewSurface = Surface(surfaceTexture)
            imageReader = ImageReader.newInstance(previewWidth, previewHeight, ImageFormat.YUV_420_888, 2).also { reader ->
                reader.setOnImageAvailableListener({ source ->
                    if (!frameInFlight.compareAndSet(false, true)) return@setOnImageAvailableListener
                    try {
                        val image = source.acquireLatestImage() ?: run {
                            frameInFlight.set(false)
                            return@setOnImageAvailableListener
                        }
                        cameraExecutor.execute { processImage(image) }
                    } catch (exception: Exception) {
                        frameInFlight.set(false)
                        onError(exception.message ?: "Unable to acquire camera frame")
                    }
                }, cameraHandler)
            }
            cameraManager.openCamera(selected.first, cameraStateCallback, cameraHandler)
        } catch (exception: SecurityException) {
            starting = false
            onError("Camera permission is required")
        } catch (exception: Exception) {
            starting = false
            onError(exception.message ?: "Unable to open camera")
        }
    }

    private fun selectFrontCamera(): Pair<String, Int>? {
        val ids = cameraManager.cameraIdList
        // Samsung's primary front sensor is ID 1. Prefer it explicitly, then
        // fall back to the first regular front-facing camera reported by HAL.
        val ordered = ids.sortedWith(compareBy<String> { if (it == "1") 0 else 1 }.thenBy { it.length }.thenBy { it })
        for (id in ordered) {
            val characteristics = cameraManager.getCameraCharacteristics(id)
            if (characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT) {
                return id to (characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0)
            }
        }
        return null
    }

    private val cameraStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(device: CameraDevice) {
            if (!starting) {
                device.close()
                return
            }
            cameraDevice = device
            createCaptureSession(device)
        }

        override fun onDisconnected(device: CameraDevice) {
            device.close()
            cameraDevice = null
            starting = false
            running = false
            onError("Camera disconnected")
        }

        override fun onError(device: CameraDevice, error: Int) {
            device.close()
            cameraDevice = null
            starting = false
            running = false
            onError("Unable to open camera (error $error)")
        }
    }

    private fun createCaptureSession(device: CameraDevice) {
        val preview = previewSurface ?: return
        val analysis = imageReader?.surface ?: return
        device.createCaptureSession(
            listOf(preview, analysis),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (!starting) {
                        session.close()
                        return
                    }
                    captureSession = session
                    try {
                        val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                            addTarget(preview)
                            addTarget(analysis)
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        }.build()
                        session.setRepeatingRequest(request, null, cameraHandler)
                        starting = false
                        running = true
                        applyPreviewTransform(textureView.width, textureView.height)
                    } catch (exception: Exception) {
                        onError(exception.message ?: "Unable to start camera stream")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    starting = false
                    onError("Unable to configure camera stream")
                }
            },
            cameraHandler,
        )
    }

    /**
     * Preserve the 4:3 sensor aspect ratio while filling the preview card.
     * Excess vertical edges are cropped instead of leaving a black border;
     * no non-uniform stretching is applied.
     */
    private fun applyPreviewTransform(viewWidth: Int, viewHeight: Int) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val centerX = viewWidth / 2f
        val centerY = viewHeight / 2f
        val sourceAspect = previewWidth.toFloat() / previewHeight
        val viewAspect = viewWidth.toFloat() / viewHeight
        // TextureView already maps its buffer to the view. Correct that
        // mapping in view space rather than mapping 640x480 pixel coordinates
        // (which would shrink the camera layer into a tiny inset rectangle).
        val fillScaleX: Float
        val fillScaleY: Float
        if (viewAspect >= sourceAspect) {
            fillScaleX = 1f
            fillScaleY = viewAspect / sourceAspect
        } else {
            fillScaleX = sourceAspect / viewAspect
            fillScaleY = 1f
        }
        val scaleX = fillScaleX
        val scaleY = fillScaleY
        // The dog-mode thumbnail is intentionally a small viewport, not a
        // second zoomed-out mirror. Let it fill its border while preserving
        // aspect ratio so the webcam image remains readable.
        val transform = Matrix().apply {
            setScale(scaleX, scaleY, centerX, centerY)
            // Mirror around the view center, like a physical mirror.
            postScale(-1f, 1f, centerX, centerY)
        }
        textureView.setTransform(transform)
    }

    private fun processImage(image: Image) {
        try {
            if (!running) return
            val bitmap = image.toBitmap()
            val rotation = rotationDegrees()
            val rotated = if (rotation == 0) bitmap else {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true).also { bitmap.recycle() }
            }
            onFrame(rotated, SystemClock.elapsedRealtime())
        } catch (exception: Exception) {
            onError(exception.message ?: "Unable to analyze camera frame")
        } finally {
            image.close()
            frameInFlight.set(false)
        }
    }

    private fun rotationDegrees(): Int {
        val displayRotation = when (textureView.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        return (sensorOrientation + displayRotation) % 360
    }

    fun stop() {
        running = false
        starting = false
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        imageReader?.close()
        imageReader = null
        previewSurface?.release()
        previewSurface = null
    }

    override fun close() {
        stop()
        cameraExecutor.shutdownNow()
        cameraThread.quitSafely()
    }
}

private fun Image.toBitmap(): Bitmap {
    val width = width
    val height = height
    val y = ByteArray(width * height)
    val u = ByteArray(width * height / 4)
    val v = ByteArray(width * height / 4)
    copyPlane(planes[0], y, width, height)
    copyPlane(planes[1], u, width / 2, height / 2)
    copyPlane(planes[2], v, width / 2, height / 2)
    val pixels = IntArray(width * height)
    for (row in 0 until height) {
        val uvRow = (row / 2) * (width / 2)
        val yRow = row * width
        for (column in 0 until width) {
            val yValue = (y[yRow + column].toInt() and 0xff) - 16
            val uvIndex = uvRow + column / 2
            val uValue = (u[uvIndex].toInt() and 0xff) - 128
            val vValue = (v[uvIndex].toInt() and 0xff) - 128
            val red = ((298 * yValue + 409 * vValue + 128) shr 8).coerceIn(0, 255)
            val green = ((298 * yValue - 100 * uValue - 208 * vValue + 128) shr 8).coerceIn(0, 255)
            val blue = ((298 * yValue + 516 * uValue + 128) shr 8).coerceIn(0, 255)
            pixels[yRow + column] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

private fun copyPlane(plane: android.media.Image.Plane, target: ByteArray, width: Int, height: Int) {
    val buffer: ByteBuffer = plane.buffer
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val row = ByteArray(rowStride)
    for (y in 0 until height) {
        val length = minOf(buffer.remaining(), rowStride)
        buffer.get(row, 0, length)
        for (x in 0 until width) {
            target[y * width + x] = row[minOf(x * pixelStride, length - 1)]
        }
    }
}
