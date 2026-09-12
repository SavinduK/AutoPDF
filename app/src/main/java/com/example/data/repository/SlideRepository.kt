package com.example.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import com.example.cv.ImageProcessor
import com.example.data.local.SessionDao
import com.example.data.local.SlideDao
import com.example.data.model.SessionEntity
import com.example.data.model.SlideEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SlideRepository(
    private val sessionDao: SessionDao,
    private val slideDao: SlideDao
) {
    fun getAllSessions(): Flow<List<SessionEntity>> = sessionDao.getAllSessions()

    fun getSession(sessionId: Long): Flow<SessionEntity?> = sessionDao.observeSessionById(sessionId)

    suspend fun getSessionSync(sessionId: Long): SessionEntity? = sessionDao.getSessionById(sessionId)

    suspend fun createSession(session: SessionEntity): Long = sessionDao.insertSession(session)

    suspend fun updateSession(session: SessionEntity) = sessionDao.updateSession(session)

    suspend fun deleteSession(session: SessionEntity) {
        slideDao.deleteSlidesForSession(session.id)
        sessionDao.deleteSession(session)
    }

    fun getSlidesForSession(sessionId: Long): Flow<List<SlideEntity>> =
        slideDao.getSlidesForSession(sessionId)

    suspend fun getSlidesForSessionSync(sessionId: Long): List<SlideEntity> =
        slideDao.getSlidesForSessionSync(sessionId)

    suspend fun insertSlide(slide: SlideEntity): Long {
        val id = slideDao.insertSlide(slide)
        sessionDao.refreshSlideCount(slide.sessionId)
        return id
    }

    suspend fun deleteSlide(slide: SlideEntity) {
        // Remove file from disk
        try {
            val f = File(slide.filePath)
            if (f.exists()) f.delete()
            slide.rawFilePath?.let { raw ->
                val rf = File(raw)
                if (rf.exists()) rf.delete()
            }
        } catch (_: Exception) {}

        slideDao.deleteSlide(slide)
        sessionDao.refreshSlideCount(slide.sessionId)
    }

    suspend fun setSlideSelected(slideId: Long, isSelected: Boolean) {
        slideDao.setSlideSelected(slideId, isSelected)
    }

    suspend fun setAllSlidesSelected(sessionId: Long, isSelected: Boolean) {
        slideDao.setAllSlidesSelected(sessionId, isSelected)
    }

    suspend fun updateSlide(slide: SlideEntity) {
        slideDao.updateSlide(slide)
    }

    suspend fun reorderSlides(slides: List<SlideEntity>) {
        val updated = slides.mapIndexed { index, slide ->
            slide.copy(orderIndex = index)
        }
        slideDao.updateSlides(updated)
    }

    /**
     * Clusters consecutive/near slides based on perceptual hash (pHash/dHash).
     * For each group, selects the sharpest slide (highest Laplacian variance) as representative and selected.
     */
    suspend fun regroupSlides(sessionId: Long, similarityThreshold: Int = 8) = withContext(Dispatchers.IO) {
        val slides = slideDao.getSlidesForSessionSync(sessionId)
        if (slides.isEmpty()) return@withContext

        val groupedList = mutableListOf<MutableList<SlideEntity>>()
        var currentGroup = mutableListOf<SlideEntity>()

        for (slide in slides) {
            if (currentGroup.isEmpty()) {
                currentGroup.add(slide)
            } else {
                val previous = currentGroup.last()
                val areDuplicates = ImageProcessor.areDuplicates(
                    previous.perceptualHash,
                    slide.perceptualHash,
                    similarityThreshold
                )
                if (areDuplicates) {
                    currentGroup.add(slide)
                } else {
                    groupedList.add(currentGroup)
                    currentGroup = mutableListOf(slide)
                }
            }
        }
        if (currentGroup.isNotEmpty()) {
            groupedList.add(currentGroup)
        }

        // Process each group: pick highest sharpness score as representative & selected
        val updatedSlides = mutableListOf<SlideEntity>()
        var globalGroupId = 1L

        for (group in groupedList) {
            val groupId = globalGroupId++
            val sharpest = group.maxByOrNull { it.sharpnessScore } ?: group.first()

            for (slide in group) {
                val isRep = slide.id == sharpest.id
                updatedSlides.add(
                    slide.copy(
                        groupId = groupId,
                        isRepresentative = isRep,
                        // Representative is selected by default; if group has only 1 slide, it's also selected
                        isSelected = if (group.size == 1) slide.isSelected else isRep
                    )
                )
            }
        }

        slideDao.updateSlides(updatedSlides)
    }

    /**
     * Imports an external image (from Gallery or Camera) as a new slide in the session.
     */
    suspend fun importImageFromUri(
        sessionId: Long,
        uri: Uri,
        context: Context,
        orderIndex: Int = -1
    ): SlideEntity? = withContext(Dispatchers.IO) {
        try {
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            } ?: return@withContext null

            val outputDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.filesDir
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val savedFile = File(outputDir, "slide_imported_${sessionId}_${timeStamp}.jpg")

            FileOutputStream(savedFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }

            val sharpness = ImageProcessor.calculateLaplacianVariance(bitmap)
            val dHash = ImageProcessor.calculateDHash(bitmap)

            val currentSlides = slideDao.getSlidesForSessionSync(sessionId)
            val calculatedOrder = if (orderIndex >= 0) orderIndex else currentSlides.size

            val slide = SlideEntity(
                sessionId = sessionId,
                filePath = savedFile.absolutePath,
                timestamp = System.currentTimeMillis(),
                orderIndex = calculatedOrder,
                groupId = (currentSlides.size + 1).toLong(),
                isRepresentative = true,
                isSelected = true,
                sharpnessScore = sharpness,
                perceptualHash = dHash,
                isImported = true,
                edgeDetected = false
            )

            val id = slideDao.insertSlide(slide)
            sessionDao.refreshSlideCount(sessionId)
            slide.copy(id = id)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
