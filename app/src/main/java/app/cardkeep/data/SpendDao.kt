package app.cardkeep.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SpendDao {
    @Query("SELECT * FROM spends ORDER BY atMillis DESC, id DESC")
    fun observeSpends(): Flow<List<Spend>>

    @Query("SELECT * FROM spends WHERE cardId = :cardId ORDER BY atMillis")
    suspend fun spendsFor(cardId: Long): List<Spend>

    @Query("SELECT * FROM spends WHERE id = :id")
    suspend fun spend(id: Long): Spend?

    @Query("SELECT * FROM spends")
    suspend fun spends(): List<Spend>

    /** Same card, amount and direction within a few minutes: the same transaction seen twice. */
    @Query(
        "SELECT COUNT(*) FROM spends WHERE cardId = :cardId AND amountPaise = :amount AND isCredit = :isCredit " +
            "AND atMillis BETWEEN :from AND :to",
    )
    suspend fun countNear(cardId: Long, amount: Long, isCredit: Boolean, from: Long, to: Long): Int

    /** Ignore on conflict: the dedupe key drops alerts we already have. Returns -1 for a duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(s: Spend): Long

    @Update
    suspend fun update(s: Spend)

    @Update
    suspend fun updateAll(s: List<Spend>)

    @Query("DELETE FROM spends WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE spends SET categoryId = :categoryId WHERE merchant LIKE '%' || :pattern || '%'")
    suspend fun recategorise(pattern: String, categoryId: Long?)

    // Alerts that couldn't be logged
    @Query("SELECT * FROM alert_misses ORDER BY at DESC")
    fun observeMisses(): Flow<List<AlertMiss>>

    @Insert
    suspend fun insertMiss(m: AlertMiss)

    @Query("SELECT COUNT(*) FROM alert_misses WHERE body = :body AND sender = :sender")
    suspend fun countMiss(sender: String, body: String): Int

    @Query("DELETE FROM alert_misses WHERE at < :before")
    suspend fun pruneMisses(before: Long)

    @Query("DELETE FROM alert_misses")
    suspend fun clearMisses()
}
