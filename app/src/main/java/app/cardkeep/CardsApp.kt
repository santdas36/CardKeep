package app.cardkeep

import android.app.Application
import android.content.Context
import app.cardkeep.data.AppDatabase
import app.cardkeep.data.Prefs
import app.cardkeep.data.Repo
import app.cardkeep.ingest.SyncWorker
import app.cardkeep.reminders.Notifications
import app.cardkeep.reminders.Reminders
import app.cardkeep.reminders.SweepWorker
import app.cardkeep.security.DbKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CardsApp : Application() {
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
        // Installs from before onboarding existed: a database already on disk means it isn't a first run.
        // Checked before the database is opened (opening creates it).
        if (!prefs.onboardedKnown) prefs.onboarded = getDatabasePath(AppDatabase.FILE).exists()
        db = AppDatabase.open(this, DbKey.passphrase(this))
        repo = Repo(db.dao(), db.ledger(), this)
        Notifications.createChannel(this)
        SweepWorker.enqueue(this)
        app.cardkeep.update.Updater.cleanUp(this) // a downloaded update, now installed or abandoned
        scope.launch {
            repo.ledger.ensureSeeded()
            if (prefs.parserVersion < PARSER_VERSION) {
                val issuers = repo.allCards().associate { it.id to it.issuer }
                runCatching { repo.ledger.refreshAfterParserUpgrade { issuers[it] } }
                runCatching { repo.ledger.reclassifyAll() }
                prefs.parserVersion = PARSER_VERSION
            }
            Reminders.rescheduleAll(this@CardsApp)
            if (repo.ledger.mailSourceOnce()?.passwordEnc != null) SyncWorker.enable(this@CardsApp)
            // Catch up on bank SMS that arrived while the app wasn't running.
            runCatching { app.cardkeep.ingest.alerts.AlertIngest.scanInbox(this@CardsApp) }
        }
    }

    companion object {
        /** Bump when parsers improve enough that stored statements should be refreshed. */
        const val PARSER_VERSION = 7
        fun from(context: Context) = context.applicationContext as CardsApp
    }
}
