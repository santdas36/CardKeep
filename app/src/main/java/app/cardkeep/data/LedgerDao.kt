package app.cardkeep.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    // Statements
    @Query("SELECT * FROM statements ORDER BY statementEpochDay DESC")
    fun observeStatements(): Flow<List<Statement>>

    @Query("SELECT * FROM statements WHERE id = :id")
    fun observeStatement(id: Long): Flow<Statement?>

    @Query("SELECT * FROM statements WHERE id = :id")
    suspend fun statement(id: Long): Statement?

    @Query("SELECT * FROM statements")
    suspend fun statements(): List<Statement>

    @Query("SELECT COUNT(*) FROM statements WHERE fileHash = :hash")
    suspend fun countByHash(hash: String): Int

    @Insert
    suspend fun insertStatement(s: Statement): Long

    @Update
    suspend fun updateStatement(s: Statement)

    @Query("DELETE FROM statements WHERE id = :id")
    suspend fun deleteStatement(id: Long)

    // Transactions
    @Query("SELECT * FROM transactions ORDER BY epochDay DESC, id DESC")
    fun observeTxns(): Flow<List<Txn>>

    @Query("SELECT * FROM transactions WHERE statementId = :statementId ORDER BY epochDay, id")
    fun observeTxnsFor(statementId: Long): Flow<List<Txn>>

    @Query("SELECT * FROM transactions WHERE statementId = :statementId")
    suspend fun txnsFor(statementId: Long): List<Txn>

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun txn(id: Long): Txn?

    /** Ignore on conflict: the unique dedupe key drops lines we already have. Returns -1 for a duplicate. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTxn(t: Txn): Long

    @Update
    suspend fun updateTxn(t: Txn)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTxn(id: Long)

    @Query("DELETE FROM transactions WHERE statementId = :statementId")
    suspend fun deleteTxnsFor(statementId: Long)

    @Query("UPDATE transactions SET categoryId = :categoryId WHERE merchant LIKE '%' || :pattern || '%'")
    suspend fun recategorise(pattern: String, categoryId: Long?)

    @Query("SELECT * FROM transactions")
    suspend fun txns(): List<Txn>

    // Categories and rules
    @Query("SELECT * FROM categories ORDER BY name")
    fun observeCategories(): Flow<List<Category>>

    @Query("SELECT * FROM categories")
    suspend fun categories(): List<Category>

    @Insert
    suspend fun insertCategory(c: Category): Long

    @Query("SELECT * FROM category_rules ORDER BY priority DESC, LENGTH(pattern) DESC")
    suspend fun rules(): List<CategoryRule>

    @Insert
    suspend fun insertRule(r: CategoryRule): Long

    @Query("DELETE FROM category_rules WHERE pattern = :pattern COLLATE NOCASE")
    suspend fun deleteRulesFor(pattern: String)

    // Mail source
    @Query("SELECT * FROM mail_source WHERE id = 1")
    fun observeMailSource(): Flow<MailSource?>

    @Query("SELECT * FROM mail_source WHERE id = 1")
    suspend fun mailSource(): MailSource?

    @Upsert
    suspend fun upsertMailSource(m: MailSource)

    @Query("DELETE FROM mail_source")
    suspend fun deleteMailSource()

    // Import log
    @Query("SELECT * FROM import_log ORDER BY receivedAt DESC LIMIT 200")
    fun observeImportLog(): Flow<List<ImportLog>>

    @Query("SELECT * FROM import_log WHERE `key` = :key")
    suspend fun logEntry(key: String): ImportLog?

    @Upsert
    suspend fun upsertLog(l: ImportLog)

    /** Addresses that have actually sent us statements: searched first next time. */
    @Query("SELECT DISTINCT sender FROM import_log WHERE outcome IN ('imported', 'needs_review', 'duplicate', 'locked', 'unmatched') AND sender LIKE '%@%'")
    suspend fun statementSenders(): List<String>

    @Query("UPDATE import_log SET outcome = 'dismissed' WHERE `key` = :key")
    suspend fun dismissLog(key: String)

    @Upsert
    suspend fun upsertSenderCard(s: SenderCard)

    @Query("SELECT cardId FROM sender_cards WHERE sender = :sender")
    suspend fun cardForSender(sender: String): Long?

    // PDFs waiting for a fix
    @Upsert
    suspend fun keepPdf(p: PendingPdf)

    @Query("SELECT * FROM pending_pdfs WHERE `key` = :key")
    suspend fun pendingPdf(key: String): PendingPdf?

    @Query("DELETE FROM pending_pdfs WHERE `key` = :key")
    suspend fun dropPdf(key: String)

    @Query("SELECT `key` FROM pending_pdfs")
    fun observePendingKeys(): Flow<List<String>>

    @Query("DELETE FROM import_log WHERE outcome IN ('locked', 'unmatched', 'unparsed', 'error')")
    suspend fun clearRetryableLog()

    /** Inserts a statement and its lines together. Returns the statement id. */
    @Transaction
    suspend fun insertStatementWithLines(s: Statement, lines: List<Txn>): Long {
        val id = insertStatement(s)
        lines.forEach { insertTxn(it.copy(statementId = id)) }
        return id
    }

    @Transaction
    suspend fun replaceStatementLines(statementId: Long, lines: List<Txn>): Int {
        deleteTxnsFor(statementId)
        return lines.count { insertTxn(it) != -1L }
    }
}
