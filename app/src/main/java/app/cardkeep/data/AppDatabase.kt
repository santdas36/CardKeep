package app.cardkeep.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        Card::class, CardSecret::class, Payment::class,
        Statement::class, Txn::class, Category::class, CategoryRule::class, MailSource::class, ImportLog::class, PendingPdf::class, SenderCard::class,
        HolderName::class, Spend::class, AlertMiss::class,
    ],
    version = 11,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): CardDao
    abstract fun ledger(): LedgerDao
    abstract fun backup(): BackupDao
    abstract fun spends(): SpendDao

    companion object {
        /** Database file name (kept from the app's old name, so existing data is found). */
        const val FILE = "duely.db"

        /** Opens the SQLCipher database. [passphrase] comes from [app.cardkeep.security.DbKey]. */
        fun open(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context, AppDatabase::class.java, FILE)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addMigrations(Repair(4), Repair(5), V6to7, V7to8, V8to9, V9to10, V10to11)
                .build()
        }
    }

    /** Card types: debit and prepaid cards sit in the stack without statements or dues. */
    private object V6to7 : Migration(6, 7) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `cards` ADD COLUMN `kind` TEXT NOT NULL DEFAULT 'credit'")
        }
    }

    /** Card designs: which art a card shows (null = auto). */
    private object V7to8 : Migration(7, 8) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `cards` ADD COLUMN `art` TEXT")
        }
    }

    /** Optional billing: existing cards keep their cycle (1); new ones saved without a due date get 0. */
    private object V8to9 : Migration(8, 9) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("ALTER TABLE `cards` ADD COLUMN `billingSet` INTEGER NOT NULL DEFAULT 1")
        }
    }

    /** Saved cardholder names for suggestions. Starts empty: names stay in the vault until a card is saved again. */
    private object V9to10 : Migration(9, 10) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `holder_names` (`name` TEXT NOT NULL, `lastUsed` INTEGER NOT NULL, `uses` INTEGER NOT NULL, PRIMARY KEY(`name`))",
            )
        }
    }

    /** The spend log (bank alerts and manual entries) and alerts that couldn't be read. */
    private object V10to11 : Migration(10, 11) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `spends` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `cardId` INTEGER NOT NULL, " +
                    "`atMillis` INTEGER NOT NULL, `epochDay` INTEGER NOT NULL, `amountPaise` INTEGER NOT NULL, `isCredit` INTEGER NOT NULL, " +
                    "`kind` TEXT NOT NULL, `currency` TEXT NOT NULL, `merchant` TEXT NOT NULL, `categoryId` INTEGER, `note` TEXT NOT NULL, " +
                    "`source` TEXT NOT NULL, `availablePaise` INTEGER, `reference` TEXT NOT NULL, `dedupeKey` TEXT NOT NULL, " +
                    "`review` TEXT NOT NULL, `statementId` INTEGER, `statementTxnId` INTEGER, " +
                    "FOREIGN KEY(`cardId`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_spends_cardId` ON `spends` (`cardId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_spends_epochDay` ON `spends` (`epochDay`)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_spends_dedupeKey` ON `spends` (`dedupeKey`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `alert_misses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sender` TEXT NOT NULL, " +
                    "`body` TEXT NOT NULL, `at` INTEGER NOT NULL, `reason` TEXT NOT NULL, `source` TEXT NOT NULL)",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_alert_misses_at` ON `alert_misses` (`at`)")
        }
    }

    /**
     * Versions 4 and 5 each shipped in more than one shape during development (columns and tables were
     * added without a version bump). This brings any of them to version 6 by adding only what's missing,
     * so no data is lost whichever build was installed.
     */
    private class Repair(from: Int) : Migration(from, 6) {
        override fun migrate(db: SupportSQLiteDatabase) {
            fun columns(table: String): Set<String> = db.query("PRAGMA table_info(`$table`)").use { c ->
                val i = c.getColumnIndex("name")
                buildSet { while (c.moveToNext()) add(c.getString(i)) }
            }
            fun addColumn(table: String, column: String, def: String) {
                if (column !in columns(table)) db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $def")
            }

            addColumn("cards", "archivedAt", "INTEGER")

            addColumn("statements", "rewardPointsBalance", "INTEGER")
            addColumn("statements", "rewardPointsEarned", "INTEGER")
            addColumn("statements", "cashbackPaise", "INTEGER")
            addColumn("statements", "rewardUnit", "TEXT")
            addColumn("statements", "creditLimitPaise", "INTEGER")

            addColumn("transactions", "kind", "TEXT NOT NULL DEFAULT 'spend'")
            addColumn("transactions", "note", "TEXT NOT NULL DEFAULT ''")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `pending_pdfs` (`key` TEXT NOT NULL, `fileName` TEXT NOT NULL, `bytes` BLOB NOT NULL, PRIMARY KEY(`key`))",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `sender_cards` (`sender` TEXT NOT NULL, `cardId` INTEGER NOT NULL, PRIMARY KEY(`sender`), " +
                    "FOREIGN KEY(`cardId`) REFERENCES `cards`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_sender_cards_cardId` ON `sender_cards` (`cardId`)")
        }
    }
}
