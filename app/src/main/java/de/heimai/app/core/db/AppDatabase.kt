package de.heimai.app.core.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

/**
 * Lokale DB — seit der zentralen Chat-Historie (Server besitzt die Gespräche)
 * nur noch Cache für die Karten-Layouts. Die früheren conversations/messages-
 * Tabellen sind entfallen (Version 2, destruktive Migration ist ok: der Cache
 * baut sich aus Assets + Server-Sync neu auf).
 */
@Entity(tableName = "card_layouts")
data class CardLayoutEntity(
    /** card_type, z. B. "weather" */
    @PrimaryKey val cardType: String,
    val layoutVersion: Int,
    /** Komplettes LayoutTemplate als JSON */
    val templateJson: String,
)

@Dao
interface CardLayoutDao {
    @Query("SELECT * FROM card_layouts")
    suspend fun all(): List<CardLayoutEntity>

    @Query("SELECT * FROM card_layouts WHERE cardType = :cardType")
    suspend fun forType(cardType: String): CardLayoutEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(layouts: List<CardLayoutEntity>)
}

@Database(
    entities = [CardLayoutEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun cardLayoutDao(): CardLayoutDao
}
