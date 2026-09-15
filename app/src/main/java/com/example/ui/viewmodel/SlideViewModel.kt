package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.model.CropRegion
import com.example.data.model.SessionEntity
import com.example.data.model.SlideEntity
import com.example.data.repository.SlideRepository
import com.example.export.PdfExporter
import com.example.service.ActiveCaptureInfo
import com.example.service.CaptureStateManager
import com.example.service.CaptureStatus
import com.example.service.LectureCameraManager
import com.example.service.ScreenCaptureService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SlideViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: SlideRepository
    init {
        val db = AppDatabase.getInstance(application)
        repository = SlideRepository(db.sessionDao(), db.slideDao())
    }

    // Active screen capture state
    val captureState: StateFlow<ActiveCaptureInfo> = CaptureStateManager.state

    // All past and active sessions
    val sessions: StateFlow<List<SessionEntity>> = repository.getAllSessions()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Currently viewed session ID
    private val _selectedSessionId = MutableStateFlow<Long?>(null)
    val selectedSessionId: StateFlow<Long?> = _selectedSessionId.asStateFlow()

    // Slides for currently viewed session
    val currentSessionSlides: StateFlow<List<SlideEntity>> = _selectedSessionId.flatMapLatest { id ->
        if (id != null) {
            repository.getSlidesForSession(id)
        } else {
            flowOf(emptyList())
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val currentSession: StateFlow<SessionEntity?> = _selectedSessionId.flatMapLatest { id ->
        if (id != null) {
            repository.getSession(id)
        } else {
            flowOf(null)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    // Settings state
    var captureIntervalSeconds = MutableStateFlow(10)
    var isAutoEdgeDetectionEnabled = MutableStateFlow(true)
    var isEnhanceContrastEnabled = MutableStateFlow(true)
    var isSilentCaptureEnabled = MutableStateFlow(true)
    var isFloatingOverlayEnabled = MutableStateFlow(false)
    var currentCropRegion = MutableStateFlow(CropRegion.FULL)
    var sessionTitleInput = MutableStateFlow("Lecture Slides")

    val cameraManager = LectureCameraManager(application, viewModelScope)

    // PDF Export progress & result
    private val _isExportingPdf = MutableStateFlow(false)
    val isExportingPdf: StateFlow<Boolean> = _isExportingPdf.asStateFlow()

    private val _exportProgress = MutableStateFlow(Pair(0, 0))
    val exportProgress: StateFlow<Pair<Int, Int>> = _exportProgress.asStateFlow()

    private val _exportedPdfFile = MutableStateFlow<File?>(null)
    val exportedPdfFile: StateFlow<File?> = _exportedPdfFile.asStateFlow()

    fun selectSession(sessionId: Long) {
        _selectedSessionId.value = sessionId
    }

    fun setCropRegion(region: CropRegion) {
        currentCropRegion.value = region
        cameraManager.cropRegion = region
    }

    /**
     * Starts a lecture hall slide capture session using the device camera.
     */
    fun startCameraSession(title: String, onSessionCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val session = SessionEntity(
                title = title.ifBlank { "Lecture Session" },
                intervalSeconds = captureIntervalSeconds.value,
                cropLeft = currentCropRegion.value.left,
                cropTop = currentCropRegion.value.top,
                cropRight = currentCropRegion.value.right,
                cropBottom = currentCropRegion.value.bottom,
                edgeDetectionEnabled = isAutoEdgeDetectionEnabled.value
            )
            val newSessionId = repository.createSession(session)
            _selectedSessionId.value = newSessionId

            cameraManager.initializeSession(
                sessionId = newSessionId,
                title = session.title,
                interval = session.intervalSeconds,
                crop = currentCropRegion.value,
                autoEdge = isAutoEdgeDetectionEnabled.value,
                enhanceContrast = isEnhanceContrastEnabled.value,
                silent = isSilentCaptureEnabled.value
            )
            cameraManager.startAutoCaptureLoop()
            onSessionCreated(newSessionId)
        }
    }

    fun snapSlideNow() {
        cameraManager.captureFrameNow(isManual = true)
    }

    fun setCameraZoom(ratio: Float) {
        cameraManager.setZoom(ratio)
    }

    fun toggleCameraTorch() {
        cameraManager.toggleTorch()
    }

    fun nextSimulatedSlide() {
        cameraManager.nextSimulatedSlide()
    }

    fun pauseCapture(context: Context? = null) {
        cameraManager.pauseCapture()
    }

    fun resumeCapture(context: Context? = null) {
        cameraManager.resumeCapture()
    }

    fun stopCapture(context: Context? = null) {
        cameraManager.stopSession()
    }

    fun toggleSlideSelection(slideId: Long, isSelected: Boolean) {
        viewModelScope.launch {
            repository.setSlideSelected(slideId, isSelected)
        }
    }

    fun setAllSlidesSelected(sessionId: Long, isSelected: Boolean) {
        viewModelScope.launch {
            repository.setAllSlidesSelected(sessionId, isSelected)
        }
    }

    fun deleteSlide(slide: SlideEntity) {
        viewModelScope.launch {
            repository.deleteSlide(slide)
        }
    }

    fun deleteSession(session: SessionEntity) {
        viewModelScope.launch {
            repository.deleteSession(session)
            if (_selectedSessionId.value == session.id) {
                _selectedSessionId.value = null
            }
        }
    }

    fun regroupSlides(sessionId: Long) {
        viewModelScope.launch {
            repository.regroupSlides(sessionId)
        }
    }

    fun setGroupRepresentative(groupId: Long, chosenSlideId: Long) {
        viewModelScope.launch {
            val slides = currentSessionSlides.value.filter { it.groupId == groupId }
            slides.forEach { slide ->
                val isChosen = slide.id == chosenSlideId
                repository.updateSlide(
                    slide.copy(
                        isRepresentative = isChosen,
                        isSelected = isChosen
                    )
                )
            }
        }
    }

    fun moveSlide(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val list = currentSessionSlides.value.toMutableList()
            if (fromIndex in list.indices && toIndex in list.indices) {
                val item = list.removeAt(fromIndex)
                list.add(toIndex, item)
                repository.reorderSlides(list)
            }
        }
    }

    fun importSlideFromUri(uri: Uri, context: Context, insertAtIndex: Int = -1) {
        val sId = _selectedSessionId.value ?: return
        viewModelScope.launch {
            repository.importImageFromUri(sId, uri, context, insertAtIndex)
        }
    }

    fun exportToPdf(context: Context, customTitle: String? = null) {
        val sId = _selectedSessionId.value ?: return
        val slides = currentSessionSlides.value.filter { it.isSelected }
        if (slides.isEmpty()) return

        val title = customTitle ?: currentSession.value?.title ?: "Slides"

        viewModelScope.launch {
            _isExportingPdf.value = true
            _exportProgress.value = Pair(0, slides.size)
            try {
                val pdfFile = PdfExporter.exportSlidesToPdf(
                    context = context,
                    sessionTitle = title,
                    slides = slides,
                    onProgress = { cur, tot ->
                        _exportProgress.value = Pair(cur, tot)
                    }
                )
                _exportedPdfFile.value = pdfFile
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isExportingPdf.value = false
            }
        }
    }

    fun clearExportResult() {
        _exportedPdfFile.value = null
        _exportProgress.value = Pair(0, 0)
    }
}
