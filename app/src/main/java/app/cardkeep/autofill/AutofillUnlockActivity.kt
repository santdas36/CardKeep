package app.cardkeep.autofill

import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Bundle
import android.service.autofill.Dataset
import android.view.autofill.AutofillManager
import android.widget.RemoteViews
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import app.cardkeep.CardsApp
import app.cardkeep.R
import app.cardkeep.security.Auth
import app.cardkeep.security.Vault
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible screen behind an autofill suggestion: always asks for fingerprint or PIN, then decrypts
 * just the chosen card and hands the filled values back to the system. Cancelling fills nothing.
 */
class AutofillUnlockActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Runs again after a rotation too: the old instance's flow died with it, and an invisible activity
        // that never finishes would swallow every tap. A prompt still on screen reconnects to this one.
        val cardId = intent.getLongExtra(EXTRA_CARD, 0L)
        @Suppress("DEPRECATION")
        val structure = intent.getParcelableExtra<AssistStructure>(AutofillManager.EXTRA_ASSIST_STRUCTURE)
        if (cardId == 0L || structure == null) return finishWith(null)

        lifecycleScope.launch {
            val dataset = try {
                fill(cardId, structure)
            } catch (e: CancellationException) {
                throw e // destroyed (e.g. rotated): the new instance finishes the job
            } catch (e: Vault.KeyLost) {
                Toast.makeText(applicationContext, e.message, Toast.LENGTH_LONG).show()
                null
            } catch (_: Exception) {
                null
            }
            finishWith(dataset)
        }
    }

    private suspend fun fill(cardId: Long, structure: AssistStructure): Dataset? {
        val db = CardsApp.from(this).db
        val (card, secret) = withContext(Dispatchers.IO) { db.dao().card(cardId) to db.dao().secret(cardId) }
        if (card == null || secret == null) return null
        val draft = Auth.unlockVault(this, "Fill card details") { withContext(Dispatchers.IO) { Vault.decrypt(secret) } }
            ?: return null
        val view = RemoteViews(packageName, R.layout.autofill_item).apply {
            setTextViewText(R.id.autofill_title, "${card.nickname}  ·  •• ${card.last4}")
            setTextViewText(R.id.autofill_subtitle, card.issuer)
        }
        val ds = Dataset.Builder(view)
        var any = false
        CardFields.find(structure).forEach { f ->
            CardFields.value(f, draft)?.let { ds.setValue(f.id, it); any = true }
        }
        return if (any) ds.build() else null
    }

    private fun finishWith(dataset: Dataset?) {
        if (dataset != null) setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, dataset))
        else setResult(RESULT_CANCELED)
        finish()
    }

    companion object { const val EXTRA_CARD = "card_id" }
}
