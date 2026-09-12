package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.SessionEntity
import com.example.data.model.SlideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY createdAt DESC")
    fun getAllSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionById(sessionId: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    fun observeSessionById(sessionId: Long): Flow<SessionEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity): Long

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Delete
    suspend fun deleteSession(session: SessionEntity)

    @Query("UPDATE sessions SET slideCount = (SELECT COUNT(*) FROM slides WHERE sessionId = :sessionId) WHERE id = :sessionId")
    suspend fun refreshSlideCount(sessionId: Long)
}

@Dao
interface SlideDao {
    @Query("SELECT * FROM slides WHERE sessionId = :sessionId ORDER BY orderIndex ASC, timestamp ASC")
    fun getSlidesForSession(sessionId: Long): Flow<List<SlideEntity>>

    @Query("SELECT * FROM slides WHERE sessionId = :sessionId ORDER BY orderIndex ASC, timestamp ASC")
    suspend fun getSlidesForSessionSync(sessionId: Long): List<SlideEntity>

    @Query("SELECT * FROM slides WHERE id = :slideId LIMIT 1")
    suspend fun getSlideById(slideId: Long): SlideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlide(slide: SlideEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSlides(slides: List<SlideEntity>)

    @Update
    suspend fun updateSlide(slide: SlideEntity)

    @Update
    suspend fun updateSlides(slides: List<SlideEntity>)

    @Delete
    suspend fun deleteSlide(slide: SlideEntity)

    @Query("DELETE FROM slides WHERE id = :slideId")
    suspend fun deleteSlideById(slideId: Long)

    @Query("DELETE FROM slides WHERE sessionId = :sessionId")
    suspend fun deleteSlidesForSession(sessionId: Long)

    @Query("UPDATE slides SET isSelected = :isSelected WHERE id = :slideId")
    suspend fun setSlideSelected(slideId: Long, isSelected: Boolean)

    @Query("UPDATE slides SET isSelected = :isSelected WHERE sessionId = :sessionId")
    suspend fun setAllSlidesSelected(sessionId: Long, isSelected: Boolean)
}
