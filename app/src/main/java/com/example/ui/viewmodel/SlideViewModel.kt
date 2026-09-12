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
    var isFloatingOverlayEnabled = MutableStateFlow(true)
    var currentCropRegion = MutableStateFlow(CropRegion.FULL)
    var sessionTitleInput = MutableStateFlow("Lecture Slides")

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
    }

    fun startSession(
        resultCode: Int,
        resultData: Intent?,
        title: String,
        context: Context
    ) {
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

            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, resultData)
                putExtra(ScreenCaptureService.EXTRA_SESSION_ID, newSessionId)
                putExtra(ScreenCaptureService.EXTRA_SESSION_TITLE, session.title)
                putExtra(ScreenCaptureService.EXTRA_INTERVAL_SECONDS, session.intervalSeconds)
                putExtra(ScreenCaptureService.EXTRA_CROP_LEFT, session.cropLeft)
                putExtra(ScreenCaptureService.EXTRA_CROP_TOP, session.cropTop)
                putExtra(ScreenCaptureService.EXTRA_CROP_RIGHT, session.cropRight)
                putExtra(ScreenCaptureService.EXTRA_CROP_BOTTOM, session.cropBottom)
                putExtra(ScreenCaptureService.EXTRA_AUTO_EDGE, session.edgeDetectionEnabled)
                putExtra(ScreenCaptureService.EXTRA_SHOW_OVERLAY, isFloatingOverlayEnabled.value)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    fun pauseCapture(context: Context) {
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_PAUSE
        }
        context.startService(intent)
    }

    fun resumeCapture(context: Context) {
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_RESUME
        }
        context.startService(intent)
    }

    fun stopCapture(context: Context) {
        val intent = Intent(context, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        context.startService(intent)
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
