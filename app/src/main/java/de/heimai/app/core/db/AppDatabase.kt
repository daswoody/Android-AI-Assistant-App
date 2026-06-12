package de.heimai.app.core.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** chat | talk | assistant */
    val source: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** user | assistant | tool */
    val role: String,
    val text: String,
    /** Serialisierte Card-Envelope (JSON), falls die Nachricht eine Karte trägt */
    val cardJson: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "card_layouts")
data class CardLayoutEntity(
    /** card_type, z. B. "weather" */
    @PrimaryKey val cardType: String,
    val layoutVersion: Int,
    /** Komplettes LayoutTemplate als JSON */
    val templateJson: String,
)

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun get(id: Long): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity): Long

    @Query("UPDATE conversations SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, title: String, updatedAt: Long)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    fun observeForConversation(conversationId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC, id ASC")
    suspend fun forConversation(conversationId: Long): List<MessageEntity>

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: Long)
}

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
    entities = [ConversationEntity::class, MessageEntity::class, CardLayoutEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun cardLayoutDao(): CardLayoutDao
}
