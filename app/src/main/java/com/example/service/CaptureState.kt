package com.example.service

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CaptureStatus {
    IDLE,
    PREPARING,
    RUNNING,
    PAUSED,
    STOPPED
}

data class ActiveCaptureInfo(
    val status: CaptureStatus = CaptureStatus.IDLE,
    val sessionId: Long = 0L,
    val sessionTitle: String = "",
    val captureCount: Int = 0,
    val elapsedSeconds: Long = 0L,
    val nextCaptureInSeconds: Int = 0,
    val intervalSeconds: Int = 10,
    val lastSlidePath: String? = null,
    val lastSharpness: Double = 0.0,
    val isAutoEdgeDetectionEnabled: Boolean = true,
    val isEnhanceContrastEnabled: Boolean = true,
    val isTorchOn: Boolean = false,
    val zoomRatio: Float = 1.0f,
    val isSlideDetected: Boolean = true,
    val lastCaptureMessage: String? = null,
    val isSimulatorActive: Boolean = false,
    val isOverlayActive: Boolean = false
)

object CaptureStateManager {
    private val _state = MutableStateFlow(ActiveCaptureInfo())
    val state: StateFlow<ActiveCaptureInfo> = _state.asStateFlow()

    // Transient in-memory reference to the latest captured frame for UI preview
    @Volatile
    var latestBitmapPreview: Bitmap? = null

    fun update(transform: (ActiveCaptureInfo) -> ActiveCaptureInfo) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = ActiveCaptureInfo()
        latestBitmapPreview = null
    }
}
