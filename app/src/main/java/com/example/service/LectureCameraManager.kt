package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.cv.EdgeDetector
import com.example.cv.ImageProcessor
import com.example.data.local.AppDatabase
import com.example.data.model.CropRegion
import com.example.data.model.SlideEntity
import com.example.data.repository.SlideRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class LectureCameraManager(
    private val context: Context,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "LectureCameraManager"
        private const val DHASH_SIMILARITY_THRESHOLD = 8
        private const val MIN_LAPLACIAN_SHARPNESS = 12.0
    }

    private val db = AppDatabase.getInstance(context.applicationContext)
    private val repository = SlideRepository(db.sessionDao(), db.slideDao())

    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null

    private var captureLoopJob: Job? = null

    // Lecture session properties
    var sessionId: Long = 0L
    var sessionTitle: String = "Lecture Slides"
    var intervalSeconds: Int = 10
    var cropRegion: CropRegion = CropRegion.FULL
    var isAutoEdgeEnabled: Boolean = true
    var isEnhanceContrastEnabled: Boolean = true
    var isSilentMode: Boolean = true

    // State tracking
    private var captureCount = 0
    private var currentZoom = 1.0f
    private var isTorchOn = false
    private var isPaused = false
    private var startTimeMillis = 0L

    // Deduplication tracking
    private var lastSlideHash: Long? = null
    private var lastSlideId: Long? = null
    private var lastSlideSharpness: Double = 0.0
    private var lastSlideFilePath: String? = null

    // Simulation topics for mock/emulator presentation feed
    private var simulatedSlideIndex = 0
    private val lectureTopics = listOf(
        Pair("Distributed Systems & Consensus", listOf(
            "• Paxos Consensus Algorithm (Lamport, 1998)",
            "• Two-Phase Commit vs Three-Phase Commit protocol",
            "• Quorum-based voting for partition tolerance",
            "• Raft: Understandable leader election & log replication",
            "• Network partitions and CAP Theorem tradeoffs"
        )),
        Pair("Vector Clocks & Causality", listOf(
            "• Logical vs Physical time in asynchronous networks",
            "• Lamport Timestamps: partial ordering of events",
            "• Vector Clocks: tracking causal dependencies [V(a) < V(b)]",
            "• Conflict detection in multi-leader databases",
            "• Version vectors vs vector clocks in distributed caches"
        )),
        Pair("Byzantine Fault Tolerance (BFT)", listOf(
            "• The Byzantine Generals Problem (3m + 1 nodes required)",
            "• Practical Byzantine Fault Tolerance (PBFT) states",
            "• Pre-prepare, Prepare, and Commit phase validation",
            "• Cryptographic threshold signatures in modern BFT",
            "• Application in resilient blockchain consensus layers"
        )),
        Pair("MapReduce & Large-Scale Processing", listOf(
            "• Split -> Map -> Shuffle & Sort -> Reduce pipeline",
            "• Fault tolerance via deterministic re-execution",
            "• Straggler mitigation via speculative backup tasks",
            "• Locality-aware scheduling: bringing compute to data",
            "• Evolution to in-memory DAG engines (Apache Spark)"
        )),
        Pair("Summary & Key Takeaways", listOf(
            "• Consistency guarantees define database architecture",
            "• Failure is the normal state in hyperscale clusters",
            "• Replicate state machine approach yields predictability",
            "• Reading: Chapter 5 'Designing Data-Intensive Applications'",
            "• Next Lecture: Gossip Protocols & Peer-to-Peer Networks"
        ))
    )

    fun initializeSession(
        sessionId: Long,
        title: String,
        interval: Int,
        crop: CropRegion,
        autoEdge: Boolean,
        enhanceContrast: Boolean,
        silent: Boolean
    ) {
        this.sessionId = sessionId
        this.sessionTitle = title
        this.intervalSeconds = interval.coerceAtLeast(3)
        this.cropRegion = crop
        this.isAutoEdgeEnabled = autoEdge
        this.isEnhanceContrastEnabled = enhanceContrast
        this.isSilentMode = silent

        captureCount = 0
        startTimeMillis = System.currentTimeMillis()
        isPaused = false
        lastSlideHash = null
        lastSlideId = null
        lastSlideSharpness = 0.0
        lastSlideFilePath = null

        CaptureStateManager.update {
            it.copy(
                status = CaptureStatus.RUNNING,
                sessionId = sessionId,
                sessionTitle = title,
                intervalSeconds = intervalSeconds,
                captureCount = 0,
                isAutoEdgeDetectionEnabled = isAutoEdgeEnabled,
                isEnhanceContrastEnabled = isEnhanceContrastEnabled,
                isTorchOn = false,
                zoomRatio = 1.0f,
                lastCaptureMessage = "Point camera at lecture hall projector screen"
            )
        }
    }

    /**
     * Binds CameraX Preview and ImageCapture to the given lifecycle and PreviewView.
     */
    fun bindCamera(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        onCameraReady: (Boolean) -> Unit
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                val provider = cameraProvider ?: run {
                    onCameraReady(false)
                    return@addListener
                }

                provider.unbindAll()

                val preview = Preview.Builder()
                    .build()
                    .also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                camera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )

                CaptureStateManager.update { it.copy(isSimulatorActive = false) }
                onCameraReady(true)
            } catch (exc: Exception) {
                Log.w(TAG, "Camera binding failed, falling back to simulator feed: ${exc.message}")
                CaptureStateManager.update { it.copy(isSimulatorActive = true) }
                onCameraReady(false)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun startAutoCaptureLoop() {
        captureLoopJob?.cancel()
        captureLoopJob = scope.launch {
            var secondsUntilNext = 1 // Quick first capture

            while (isActive) {
                if (!isPaused) {
                    val elapsed = (System.currentTimeMillis() - startTimeMillis) / 1000
                    CaptureStateManager.update {
                        it.copy(
                            status = CaptureStatus.RUNNING,
                            elapsedSeconds = elapsed,
                            nextCaptureInSeconds = secondsUntilNext,
                            captureCount = captureCount
                        )
                    }

                    if (secondsUntilNext <= 0) {
                        captureFrameNow(isManual = false)
                        secondsUntilNext = intervalSeconds
                    } else {
                        secondsUntilNext--
                    }
                } else {
                    CaptureStateManager.update {
                        it.copy(status = CaptureStatus.PAUSED)
                    }
                }
                delay(1000)
            }
        }
    }

    /**
     * Triggers manual capture or automatic interval capture.
     */
    fun captureFrameNow(isManual: Boolean = false) {
        val capture = imageCapture
        if (capture != null && camera != null) {
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val bitmap = imageProxyToBitmap(image)
                        image.close()
                        if (bitmap != null) {
                            scope.launch(Dispatchers.Default) {
                                processCapturedBitmap(bitmap, isManual)
                            }
                        } else {
                            scope.launch(Dispatchers.Default) {
                                processCapturedBitmap(generateLectureHallSlideBitmap(), isManual)
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Photo capture failed: ${exception.message}", exception)
                        scope.launch(Dispatchers.Default) {
                            processCapturedBitmap(generateLectureHallSlideBitmap(), isManual)
                        }
                    }
                }
            )
        } else {
            // Simulator feed for emulators or testing without physical camera
            scope.launch(Dispatchers.Default) {
                processCapturedBitmap(generateLectureHallSlideBitmap(), isManual)
            }
        }
    }

    /**
     * Core slide computer vision pipeline:
     * 1. Perspective warp / keystone correction + projector contour crop
     * 2. Contrast enhancement for glare & ambient classroom lighting
     * 3. Laplacian sharpness measurement
     * 4. Perceptual dHash deduplication
     */
    private suspend fun processCapturedBitmap(rawBitmap: Bitmap, isManual: Boolean) = withContext(Dispatchers.Default) {
        try {
            // 1. Edge & Keystone correction
            val cropResult = EdgeDetector.processAndCrop(
                source = rawBitmap,
                manualCropRegion = cropRegion,
                enableAutoEdgeDetection = isAutoEdgeEnabled,
                enableEnhanceContrast = isEnhanceContrastEnabled
            )
            val slideBitmap = cropResult.bitmap

            // 2. Compute Sharpness & Hash
            val sharpness = ImageProcessor.calculateLaplacianVariance(slideBitmap)
            val pHash = ImageProcessor.calculateDHash(slideBitmap)

            // Blur filter: if not manual snap, skip very blurry frames caused by hands moving
            if (!isManual && sharpness < MIN_LAPLACIAN_SHARPNESS && captureCount > 0) {
                CaptureStateManager.update {
                    it.copy(lastCaptureMessage = "Skipped blurry frame (focus score: ${String.format(Locale.US, "%.1f", sharpness)})")
                }
                return@withContext
            }

            // 3. Deduplication Check with last slide
            val previousHash = lastSlideHash
            val isDuplicateOfPrevious = previousHash != null &&
                    ImageProcessor.areDuplicates(previousHash, pHash, DHASH_SIMILARITY_THRESHOLD)

            if (isDuplicateOfPrevious && !isManual) {
                // Same slide still projected on screen
                if (sharpness > lastSlideSharpness && lastSlideFilePath != null) {
                    // Update current slide with this sharper capture!
                    val existingFile = File(lastSlideFilePath!!)
                    FileOutputStream(existingFile).use { out ->
                        slideBitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
                    }
                    lastSlideSharpness = sharpness
                    lastSlideId?.let { sId ->
                        val existingEntity = repository.getSlidesForSessionSync(sessionId).find { it.id == sId }
                        if (existingEntity != null) {
                            repository.updateSlide(existingEntity.copy(sharpnessScore = sharpness))
                        }
                    }
                    CaptureStateManager.latestBitmapPreview = slideBitmap
                    CaptureStateManager.update {
                        it.copy(
                            lastSharpness = sharpness,
                            lastCaptureMessage = "Updated Slide #$captureCount with sharper focus"
                        )
                    }
                } else {
                    CaptureStateManager.update {
                        it.copy(lastCaptureMessage = "Same slide on screen (sharpest version kept)")
                    }
                }
            } else {
                // New distinct slide detected!
                val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val slideFile = File(outputDir, "lecture_${sessionId}_slide_${captureCount + 1}_${timeStamp}.jpg")

                FileOutputStream(slideFile).use { out ->
                    slideBitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)
                }

                captureCount++
                lastSlideHash = pHash
                lastSlideSharpness = sharpness
                lastSlideFilePath = slideFile.absolutePath

                val entity = SlideEntity(
                    sessionId = sessionId,
                    filePath = slideFile.absolutePath,
                    timestamp = System.currentTimeMillis(),
                    orderIndex = captureCount - 1,
                    groupId = captureCount.toLong(),
                    isRepresentative = true,
                    isSelected = true,
                    sharpnessScore = sharpness,
                    perceptualHash = pHash,
                    isImported = false,
                    edgeDetected = cropResult.isEdgeDetected
                )
                val insertedId = repository.insertSlide(entity)
                lastSlideId = insertedId

                // Update session slide count in DB
                val session = repository.getSessionSync(sessionId)
                if (session != null) {
                    repository.updateSession(session.copy(slideCount = captureCount))
                }

                CaptureStateManager.latestBitmapPreview = slideBitmap
                CaptureStateManager.update {
                    it.copy(
                        captureCount = captureCount,
                        lastSlidePath = slideFile.absolutePath,
                        lastSharpness = sharpness,
                        lastCaptureMessage = "Slide #$captureCount captured (Keystone: ${if (cropResult.isEdgeDetected) "Rectified" else "Custom"})"
                    )
                }

                // Haptic feedback
                if (!isSilentMode) {
                    triggerHapticFeedback()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in slide processing pipeline", e)
        } finally {
            if (rawBitmap != rawBitmap) {
                rawBitmap.recycle()
            }
        }
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (_: Exception) {}
    }

    fun setZoom(ratio: Float) {
        currentZoom = ratio.coerceIn(1.0f, 5.0f)
        try {
            camera?.cameraControl?.setZoomRatio(currentZoom)
        } catch (e: Exception) {
            Log.w(TAG, "Set zoom ratio failed: ${e.message}")
        }
        CaptureStateManager.update { it.copy(zoomRatio = currentZoom) }
    }

    fun toggleTorch() {
        isTorchOn = !isTorchOn
        try {
            camera?.cameraControl?.enableTorch(isTorchOn)
        } catch (e: Exception) {
            Log.w(TAG, "Toggle torch failed: ${e.message}")
        }
        CaptureStateManager.update { it.copy(isTorchOn = isTorchOn) }
    }

    fun focusOnPoint(x: Float, y: Float, width: Float, height: Float) {
        if (width <= 0 || height <= 0) return
        val normX = (x / width).coerceIn(0f, 1f)
        val normY = (y / height).coerceIn(0f, 1f)

        try {
            val factory = androidx.camera.core.SurfaceOrientedMeteringPointFactory(width, height)
            val point = factory.createPoint(x, y)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            camera?.cameraControl?.startFocusAndMetering(action)
        } catch (e: Exception) {
            Log.w(TAG, "Focus action failed: ${e.message}")
        }
    }

    fun pauseCapture() {
        isPaused = true
        CaptureStateManager.update { it.copy(status = CaptureStatus.PAUSED) }
    }

    fun resumeCapture() {
        isPaused = false
        CaptureStateManager.update { it.copy(status = CaptureStatus.RUNNING) }
    }

    fun stopSession() {
        captureLoopJob?.cancel()
        captureLoopJob = null

        scope.launch {
            val session = repository.getSessionSync(sessionId)
            if (session != null) {
                repository.updateSession(
                    session.copy(
                        endedAt = System.currentTimeMillis(),
                        slideCount = captureCount
                    )
                )
                repository.regroupSlides(sessionId)
            }
        }

        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}

        CaptureStateManager.update { it.copy(status = CaptureStatus.STOPPED) }
    }

    fun nextSimulatedSlide() {
        simulatedSlideIndex = (simulatedSlideIndex + 1) % lectureTopics.size
        captureFrameNow(isManual = true)
    }

    /**
     * Generates a realistic lecture hall slide projected on a screen.
     * Perfect for Android emulators, testing, and live verification.
     */
    fun generateLectureHallSlideBitmap(): Bitmap {
        val topic = lectureTopics[simulatedSlideIndex % lectureTopics.size]
        val w = 1280
        val h = 720
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Lecture Hall background (ambient room wall)
        val hallWallPaint = Paint().apply { color = Color.parseColor("#090D16") }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), hallWallPaint)

        // Projector screen border / frame (trapezoid/keystone slight angle as in a real lecture hall)
        val screenPadding = 48f
        val screenRect = RectF(screenPadding, screenPadding, w - screenPadding, h - screenPadding)

        // Projector screen canvas (whiteboard/matte screen)
        val screenPaint = Paint().apply { color = Color.parseColor("#0F172A") }
        canvas.drawRoundRect(screenRect, 12f, 12f, screenPaint)

        // Projection Header Banner
        val headerPaint = Paint().apply { color = Color.parseColor("#1E3A8A") }
        canvas.drawRoundRect(
            RectF(screenPadding, screenPadding, w - screenPadding, screenPadding + 90f),
            12f, 12f, headerPaint
        )

        // Lecture Title
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 32f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        canvas.drawText("$sessionTitle  •  ${topic.first}", screenPadding + 32f, screenPadding + 58f, titlePaint)

        // Slide Content Card
        val cardPaint = Paint().apply { color = Color.parseColor("#1E293B") }
        canvas.drawRoundRect(
            RectF(screenPadding + 24f, screenPadding + 114f, w - screenPadding - 24f, h - screenPadding - 48f),
            12f, 12f, cardPaint
        )

        // Bullet Points
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F1F5F9")
            textSize = 26f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

        var textY = screenPadding + 170f
        for (bullet in topic.second) {
            canvas.drawText(bullet, screenPadding + 56f, textY, textPaint)
            textY += 60f
        }

        // Footer / Timestamp info
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 20f
        }
        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        canvas.drawText("Slide #${(simulatedSlideIndex % lectureTopics.size) + 1}  •  Projected in Hall A  •  $timeStr", screenPadding + 56f, h - screenPadding - 18f, footerPaint)

        return bitmap
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val planeProxy = image.planes[0]
        val buffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    fun release() {
        captureLoopJob?.cancel()
        try {
            cameraProvider?.unbindAll()
            cameraExecutor.shutdown()
        } catch (_: Exception) {}
    }
}
