package app.cardkeep.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Whole-table reads and writes for backup and restore. Ids are kept as-is so links survive. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM cards") suspend fun cards(): List<Card>
    @Query("SELECT * FROM card_secrets") suspend fun secrets(): List<CardSecret>
    @Query("SELECT * FROM payments") suspend fun payments(): List<Payment>
    @Query("SELECT * FROM statements") suspend fun statements(): List<Statement>
    @Query("SELECT * FROM transactions") suspend fun txns(): List<Txn>
    @Query("SELECT * FROM categories") suspend fun categories(): List<Category>
    @Query("SELECT * FROM category_rules") suspend fun rules(): List<CategoryRule>
    @Query("SELECT * FROM mail_source WHERE id = 1") suspend fun mailSource(): MailSource?
    @Query("SELECT * FROM import_log") suspend fun importLog(): List<ImportLog>
    @Query("SELECT * FROM sender_cards") suspend fun senderCards(): List<SenderCard>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCards(v: List<Card>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSecrets(v: List<CardSecret>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPayments(v: List<Payment>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putStatements(v: List<Statement>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putTxns(v: List<Txn>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCategories(v: List<Category>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putRules(v: List<CategoryRule>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMailSource(v: MailSource)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putImportLog(v: List<ImportLog>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSenderCards(v: List<SenderCard>)

    // Children first, so foreign keys never block the wipe.
    @Query("DELETE FROM sender_cards") suspend fun clearSenderCards()
    @Query("DELETE FROM pending_pdfs") suspend fun clearPendingPdfs()
    @Query("DELETE FROM import_log") suspend fun clearImportLog()
    @Query("DELETE FROM transactions") suspend fun clearTxns()
    @Query("DELETE FROM statements") suspend fun clearStatements()
    @Query("DELETE FROM payments") suspend fun clearPayments()
    @Query("DELETE FROM card_secrets") suspend fun clearSecrets()
    @Query("DELETE FROM cards") suspend fun clearCards()
    @Query("DELETE FROM category_rules") suspend fun clearRules()
    @Query("DELETE FROM categories") suspend fun clearCategories()
    @Query("DELETE FROM mail_source") suspend fun clearMailSource()
}
