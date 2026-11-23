package app.cardkeep.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.widget.RemoteViews
import app.cardkeep.CardsApp
import app.cardkeep.R
import app.cardkeep.data.CardKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Offers saved cards on checkout forms. The suggestion only shows the card's name and last four;
 * picking one opens [AutofillUnlockActivity], which asks for fingerprint or PIN before anything is
 * decrypted. Nothing is filled into this app itself, and nothing is ever saved from other apps.
 */
class CardAutofillService : AutofillService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onFillRequest(request: FillRequest, cancel: CancellationSignal, callback: FillCallback) {
        val structure = request.fillContexts.lastOrNull()?.structure ?: return callback.onSuccess(null)
        if (structure.activityComponent.packageName == packageName) return callback.onSuccess(null)
        val fields = CardFields.find(structure)
        if (fields.isEmpty()) return callback.onSuccess(null)

        val job = scope.launch {
            val response = runCatching {
                val db = CardsApp.from(this@CardAutofillService).db
                val withSecrets = db.backup().secrets().filter { it.numberEnc != null }.map { it.cardId }.toSet()
                val cards = db.dao().cards().filter { it.archivedAt == null && it.id in withSecrets }
                    .sortedWith(compareByDescending<app.cardkeep.data.Card> { it.pinned }.thenBy { it.nickname })
                if (cards.isEmpty()) return@runCatching null
                val builder = FillResponse.Builder()
                cards.take(MAX_SUGGESTIONS).forEach { card ->
                    val label = "${card.nickname}  ·  •• ${card.last4}"
                    val sub = "${card.issuer} ${CardKind.label(card.kind).lowercase()} · Unlock to fill"
                    val view = presentation(label, sub)
                    val intent = Intent(this@CardAutofillService, AutofillUnlockActivity::class.java)
                        .putExtra(AutofillUnlockActivity.EXTRA_CARD, card.id)
                    val sender = PendingIntent.getActivity(
                        this@CardAutofillService, card.id.toInt(), intent,
                        PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_MUTABLE,
                    ).intentSender
                    val ds = Dataset.Builder(view)
                    fields.forEach { f -> ds.setValue(f.id, null, view) }
                    ds.setAuthentication(sender)
                    builder.addDataset(ds.build())
                }
                builder.build()
            }.getOrNull()
            if (!cancel.isCanceled) callback.onSuccess(response)
        }
        cancel.setOnCancelListener { job.cancel() }
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) = callback.onSuccess()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun presentation(title: String, subtitle: String) = RemoteViews(packageName, R.layout.autofill_item).apply {
        setTextViewText(R.id.autofill_title, title)
        setTextViewText(R.id.autofill_subtitle, subtitle)
    }

    private companion object { const val MAX_SUGGESTIONS = 6 }
}
