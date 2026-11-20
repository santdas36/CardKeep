package app.duely

import android.app.Application
import android.content.Context
import app.duely.data.AppDatabase
import app.duely.data.Prefs
import app.duely.data.Repo
import app.duely.ingest.SyncWorker
import app.duely.reminders.Notifications
import app.duely.reminders.Reminders
import app.duely.reminders.SweepWorker
import app.duely.security.DbKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DuelyApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var repo: Repo
        private set
    lateinit var prefs: Prefs
        private set
    lateinit var db: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        db = AppDatabase.open(this, DbKey.passphrase(this))
        repo = Repo(db.dao(), db.ledger(), this)
        Notifications.createChannel(this)
        SweepWorker.enqueue(this)
        scope.launch {
            repo.ledger.ensureSeeded()
            if (prefs.parserVersion < PARSER_VERSION) {
                val issuers = repo.allCards().associate { it.id to it.issuer }
                runCatching { repo.ledger.refreshAfterParserUpgrade { issuers[it] } }
                runCatching { repo.ledger.reclassifyAll() }
                prefs.parserVersion = PARSER_VERSION
            }
            Reminders.rescheduleAll(this@DuelyApp)
            if (repo.ledger.mailSourceOnce()?.passwordEnc != null) SyncWorker.enable(this@DuelyApp)
        }
    }

    companion object {
        /** Bump when parsers improve enough that stored statements should be refreshed. */
        const val PARSER_VERSION = 4
        fun from(context: Context) = context.applicationContext as DuelyApp
    }
}
