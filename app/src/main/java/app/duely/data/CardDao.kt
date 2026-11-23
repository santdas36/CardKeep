package app.duely.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface CardDao {
    @Query("SELECT * FROM cards")
    fun observeCards(): Flow<List<Card>>

    @Query("SELECT * FROM cards")
    suspend fun cards(): List<Card>

    @Query("SELECT * FROM cards WHERE id = :id")
    suspend fun card(id: Long): Card?

    @Insert
    suspend fun insert(card: Card): Long

    @Update
    suspend fun update(card: Card)

    @Query("DELETE FROM cards WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM card_secrets WHERE cardId = :id")
    suspend fun secret(id: Long): CardSecret?

    @Upsert
    suspend fun upsertSecret(secret: CardSecret)

    @Query("SELECT * FROM payments ORDER BY paidOnEpochDay DESC, id DESC")
    fun observePayments(): Flow<List<Payment>>

    @Query("SELECT * FROM payments")
    suspend fun payments(): List<Payment>

    @Query("SELECT * FROM payments WHERE cardId = :cardId")
    suspend fun paymentsFor(cardId: Long): List<Payment>

    @Insert
    suspend fun insertPayment(payment: Payment)

    @Query("DELETE FROM payments WHERE id = :id")
    suspend fun deletePayment(id: Long)

    // Saved cardholder names: most used first, then most recent.
    @Query("SELECT * FROM holder_names ORDER BY uses DESC, lastUsed DESC")
    fun observeHolderNames(): Flow<List<HolderName>>

    @Query("SELECT * FROM holder_names ORDER BY uses DESC, lastUsed DESC")
    suspend fun holderNames(): List<HolderName>

    /** Adds [name] with no uses yet; an existing name is left as it is. */
    @Query("INSERT OR IGNORE INTO holder_names (name, lastUsed, uses) VALUES (:name, :at, 0)")
    suspend fun insertHolderName(name: String, at: Long)

    @Query("UPDATE holder_names SET uses = uses + 1, lastUsed = :at WHERE name = :name")
    suspend fun bumpHolderName(name: String, at: Long)

    @Query("DELETE FROM holder_names WHERE name = :name")
    suspend fun deleteHolderName(name: String)

    @Query("DELETE FROM holder_names")
    suspend fun clearHolderNames()

    @Upsert
    suspend fun putHolderNames(names: List<HolderName>)
}
