package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Normalized rectangular crop region [0.0f .. 1.0f]
 */
data class CropRegion(
    val left: Float = 0f,
    val top: Float = 0f,
    val right: Float = 1f,
    val bottom: Float = 1f
) {
    val isValid: Boolean
        get() = right > left && bottom > top && left >= 0f && right <= 1f && top >= 0f && bottom <= 1f

    fun getWidthFraction(): Float = (right - left).coerceIn(0.05f, 1f)
    fun getHeightFraction(): Float = (bottom - top).coerceIn(0.05f, 1f)

    companion object {
        val FULL = CropRegion(0f, 0f, 1f, 1f)
    }
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val endedAt: Long? = null,
    val intervalSeconds: Int = 10,
    val cropLeft: Float = 0f,
    val cropTop: Float = 0f,
    val cropRight: Float = 1f,
    val cropBottom: Float = 1f,
    val slideCount: Int = 0,
    val edgeDetectionEnabled: Boolean = true
) {
    fun toCropRegion(): CropRegion = CropRegion(cropLeft, cropTop, cropRight, cropBottom)
}

@Entity(tableName = "slides")
data class SlideEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val filePath: String,
    val rawFilePath: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val orderIndex: Int = 0,
    val groupId: Long = 0,
    val isRepresentative: Boolean = false,
    val isSelected: Boolean = true,
    val sharpnessScore: Double = 0.0,
    val perceptualHash: Long = 0L,
    val isImported: Boolean = false,
    val edgeDetected: Boolean = false
)
