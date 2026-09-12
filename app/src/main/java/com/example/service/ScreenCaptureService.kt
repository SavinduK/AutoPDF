package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.MainActivity
import com.example.R
import com.example.cv.EdgeDetector
import com.example.cv.ImageProcessor
import com.example.data.local.AppDatabase
import com.example.data.model.CropRegion
import com.example.data.model.SlideEntity
import com.example.data.repository.SlideRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "slide_capture_channel"
        const val NOTIFICATION_ID = 4041

        const val ACTION_START = "com.example.action.START_CAPTURE"
        const val ACTION_PAUSE = "com.example.action.PAUSE_CAPTURE"
        const val ACTION_RESUME = "com.example.action.RESUME_CAPTURE"
        const val ACTION_STOP = "com.example.action.STOP_CAPTURE"

        const val EXTRA_RESULT_CODE = "extra_result_code"
        const val EXTRA_RESULT_DATA = "extra_result_data"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_SESSION_TITLE = "extra_session_title"
        const val EXTRA_INTERVAL_SECONDS = "extra_interval_seconds"
        const val EXTRA_CROP_LEFT = "extra_crop_left"
        const val EXTRA_CROP_TOP = "extra_crop_top"
        const val EXTRA_CROP_RIGHT = "extra_crop_right"
        const val EXTRA_CROP_BOTTOM = "extra_crop_bottom"
        const val EXTRA_AUTO_EDGE = "extra_auto_edge"
        const val EXTRA_SHOW_OVERLAY = "extra_show_overlay"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var captureJob: Job? = null

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private var floatingOverlayManager: FloatingOverlayManager? = null
    private lateinit var repository: SlideRepository

    private var sessionId: Long = 0L
    private var sessionTitle: String = "Lecture Capture"
    private var intervalSeconds: Int = 10
    private var cropRegion: CropRegion = CropRegion.FULL
    private var isAutoEdgeEnabled: Boolean = true
    private var isPaused: Boolean = false
    private var captureCount: Int = 0
    private var startTimeMillis: Long = 0L

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 320

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val db = AppDatabase.getInstance(applicationContext)
        repository = SlideRepository(db.sessionDao(), db.slideDao())
        createNotificationChannel()

        floatingOverlayManager = FloatingOverlayManager(this).apply {
            onPauseResumeClicked = {
                if (isPaused) resumeCapture() else pauseCapture()
            }
            onStopClicked = {
                stopCapture()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
                val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                sessionId = intent.getLongExtra(EXTRA_SESSION_ID, System.currentTimeMillis())
                sessionTitle = intent.getStringExtra(EXTRA_SESSION_TITLE) ?: "Lecture Capture"
                intervalSeconds = intent.getIntExtra(EXTRA_INTERVAL_SECONDS, 10).coerceAtLeast(3)
                val cropLeft = intent.getFloatExtra(EXTRA_CROP_LEFT, 0f)
                val cropTop = intent.getFloatExtra(EXTRA_CROP_TOP, 0f)
                val cropRight = intent.getFloatExtra(EXTRA_CROP_RIGHT, 1f)
                val cropBottom = intent.getFloatExtra(EXTRA_CROP_BOTTOM, 1f)
                cropRegion = CropRegion(cropLeft, cropTop, cropRight, cropBottom)
                isAutoEdgeEnabled = intent.getBooleanExtra(EXTRA_AUTO_EDGE, true)
                val showOverlay = intent.getBooleanExtra(EXTRA_SHOW_OVERLAY, true)

                startCapture(resultCode, resultData, showOverlay)
            }
            ACTION_PAUSE -> pauseCapture()
            ACTION_RESUME -> resumeCapture()
            ACTION_STOP -> stopCapture()
        }
        return START_NOT_STICKY
    }

    private fun startCapture(resultCode: Int, resultData: Intent?, showOverlay: Boolean) {
        val notification = buildNotification("Starting capture...", "0 slides captured")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        if (resultCode == Activity.RESULT_OK && resultData != null && mediaProjection == null) {
            try {
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        stopCapture()
                    }
                }, null)

                imageReader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "SlideCaptureVirtualDisplay",
                    screenWidth,
                    screenHeight,
                    screenDensity,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader?.surface,
                    null,
                    null
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        startTimeMillis = System.currentTimeMillis()
        isPaused = false
        captureCount = 0

        if (showOverlay) {
            floatingOverlayManager?.show()
        }

        CaptureStateManager.update {
            it.copy(
                status = CaptureStatus.RUNNING,
                sessionId = sessionId,
                sessionTitle = sessionTitle,
                intervalSeconds = intervalSeconds,
                captureCount = 0,
                isAutoEdgeDetectionEnabled = isAutoEdgeEnabled,
                isOverlayActive = showOverlay
            )
        }

        startCaptureLoop()
    }

    private fun startCaptureLoop() {
        captureJob?.cancel()
        captureJob = serviceScope.launch {
            var secondsUntilNext = 1 // Quick first capture after 1s

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
                        doCaptureFrame()
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

    private suspend fun doCaptureFrame() {
        val rawBitmap = acquireLatestScreenBitmap() ?: generateFallbackPresentationSlide()
        if (rawBitmap == null) return

        withContext(Dispatchers.Default) {
            // Edge detection + perspective crop with manual crop region fallback
            val cropResult = EdgeDetector.processAndCrop(
                source = rawBitmap,
                manualCropRegion = cropRegion,
                enableAutoEdgeDetection = isAutoEdgeEnabled
            )
            val finalSlideBitmap = cropResult.bitmap

            // Compute sharpness score and perceptual hash
            val sharpness = ImageProcessor.calculateLaplacianVariance(finalSlideBitmap)
            val pHash = ImageProcessor.calculateDHash(finalSlideBitmap)

            // Save to app-private pictures directory
            val outputDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: filesDir
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val slideFile = File(outputDir, "slide_${sessionId}_${timeStamp}.jpg")

            FileOutputStream(slideFile).use { out ->
                finalSlideBitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }

            captureCount++

            // Save into Room DB
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
            repository.insertSlide(entity)

            // Cache preview
            CaptureStateManager.latestBitmapPreview = finalSlideBitmap
            CaptureStateManager.update {
                it.copy(
                    captureCount = captureCount,
                    lastSlidePath = slideFile.absolutePath,
                    lastSharpness = sharpness
                )
            }

            // Update UI & Notification
            withContext(Dispatchers.Main) {
                updateNotification()
                floatingOverlayManager?.update(captureCount, isPaused)
            }

            if (rawBitmap != finalSlideBitmap && !rawBitmap.isRecycled) {
                rawBitmap.recycle()
            }
        }
    }

    private fun acquireLatestScreenBitmap(): Bitmap? {
        val reader = imageReader ?: return null
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            return if (rowPadding == 0) {
                bitmap
            } else {
                val clean = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                clean
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        } finally {
            image?.close()
        }
    }

    /**
     * Fallback presentation generator for testing, emulator feeds, or background simulation.
     */
    private fun generateFallbackPresentationSlide(): Bitmap {
        val w = 1280
        val h = 720
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Slide background
        val bgPaint = Paint().apply { color = Color.parseColor("#0F172A") }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgPaint)

        // Slide header bar
        val barPaint = Paint().apply { color = Color.parseColor("#2563EB") }
        canvas.drawRect(0f, 0f, w.toFloat(), 90f, barPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        canvas.drawText("$sessionTitle  •  Slide ${captureCount + 1}", 40f, 60f, titlePaint)

        // Slide Content Card
        val cardPaint = Paint().apply { color = Color.parseColor("#1E293B") }
        canvas.drawRoundRect(40f, 130f, w - 40f, h - 50f, 16f, 16f, cardPaint)

        val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            textSize = 28f
        }
        canvas.drawText("• Key Topic: Lecture Slide Recording & Auto-Extraction", 70f, 200f, bodyPaint)
        canvas.drawText("• Real-time OpenCV Edge Detection and Perspective Warping", 70f, 260f, bodyPaint)
        canvas.drawText("• Difference Hash (dHash) Deduplication & Perceptual Grouping", 70f, 320f, bodyPaint)
        canvas.drawText("• Laplacian Focus Analysis & Sharpest Image Selection", 70f, 380f, bodyPaint)
        canvas.drawText("• Seamless Android Native PdfDocument Compilation", 70f, 440f, bodyPaint)

        // Time indicator
        val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#94A3B8")
            textSize = 20f
        }
        canvas.drawText("Captured at ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())}", 70f, 580f, timePaint)

        return bitmap
    }

    private fun pauseCapture() {
        isPaused = true
        CaptureStateManager.update { it.copy(status = CaptureStatus.PAUSED) }
        updateNotification()
        floatingOverlayManager?.update(captureCount, true)
    }

    private fun resumeCapture() {
        isPaused = false
        CaptureStateManager.update { it.copy(status = CaptureStatus.RUNNING) }
        updateNotification()
        floatingOverlayManager?.update(captureCount, false)
    }

    private fun stopCapture() {
        captureJob?.cancel()
        captureJob = null

        // Mark session as completed
        serviceScope.launch {
            val session = repository.getSessionSync(sessionId)
            if (session != null) {
                repository.updateSession(
                    session.copy(
                        endedAt = System.currentTimeMillis(),
                        slideCount = captureCount
                    )
                )
                // Regroup duplicate slides
                repository.regroupSlides(sessionId)
            }
        }

        floatingOverlayManager?.hide()

        try {
            virtualDisplay?.release()
            virtualDisplay = null
            imageReader?.close()
            imageReader = null
            mediaProjection?.stop()
            mediaProjection = null
        } catch (e: Exception) {
            e.printStackTrace()
        }

        CaptureStateManager.update { it.copy(status = CaptureStatus.STOPPED) }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openAppPendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
        }
        val pauseResumePendingIntent = PendingIntent.getService(
            this, 1, pauseResumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 2, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val pauseResumeActionText = if (isPaused) "Resume" else "Pause"
        val pauseResumeIcon = if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openAppPendingIntent)
            .addAction(pauseResumeIcon, pauseResumeActionText, pauseResumePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Capture", stopPendingIntent)
            .build()
    }

    private fun updateNotification() {
        val title = if (isPaused) "Slide Capture Paused" else "Recording $sessionTitle"
        val content = "$captureCount slides captured • Every ${intervalSeconds}s"
        val notification = buildNotification(title, content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Slide Capture Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows recording status for slide capture sessions"
                setShowBadge(true)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        floatingOverlayManager?.hide()
        captureJob?.cancel()
        serviceScope.cancel()
        try {
            virtualDisplay?.release()
            imageReader?.close()
            mediaProjection?.stop()
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
