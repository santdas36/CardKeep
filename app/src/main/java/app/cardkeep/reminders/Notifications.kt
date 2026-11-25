package app.cardkeep.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.cardkeep.MainActivity
import app.cardkeep.R
import app.cardkeep.domain.CardWithDue
import app.cardkeep.domain.Dates
import app.cardkeep.domain.Money

object Notifications {
    const val CHANNEL = "due_reminders"
    const val CHANNEL_STATEMENTS = "statements"
    const val CHANNEL_SPENDS = "spend_checks"
    const val EXTRA_OPEN_CARD = "open_card"
    const val EXTRA_OPEN_STATEMENT = "open_statement"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Due-date reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Reminders before a card's payment is due" }
        val st = NotificationChannel(CHANNEL_STATEMENTS, "New statements", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "When a statement arrives by email" }
        val sp = NotificationChannel(CHANNEL_SPENDS, "Spend checks", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "When a statement doesn't match the spends you logged" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(ch, st, sp))
    }

    fun notificationId(cardId: Long) = (cardId % Int.MAX_VALUE).toInt() + 1000

    fun enabled(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun showDue(context: Context, cw: CardWithDue) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val c = cw.card
        val due = cw.due
        val dueEpoch = due.dueDate.toEpochDay()
        val title = "${c.nickname} •• ${c.last4} due ${Dates.relative(due.daysLeft)}"
        val amount = if (c.outstandingPaise > 0) "Pay ${Money.inr(c.outstandingPaise, true)} " else "Payment "
        val text = amount + "by ${Dates.weekday(due.dueDate)}"

        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("cards://card/${c.id}"))
                .putExtra(EXTRA_OPEN_CARD, c.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setColor(c.color.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Mark as paid", ActionReceiver.intent(context, ActionReceiver.ACTION_PAID, c.id, dueEpoch))
            .addAction(0, "Snooze to evening", ActionReceiver.intent(context, ActionReceiver.ACTION_SNOOZE, c.id, dueEpoch))
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(c.id), n)
    }

    /** "New statement" notice after a sync import. */
    fun showStatement(context: Context, card: app.cardkeep.data.Card, s: app.cardkeep.data.Statement) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val review = s.status != app.cardkeep.data.Ledger.STATUS_OK
        val title = if (review) "${card.nickname} statement needs a look" else "${card.nickname} statement"
        val text = buildString {
            s.totalDuePaise?.let { append(Money.inr(it, true)) }
            s.dueEpochDay?.let { append(" due ").append(Dates.weekday(java.time.LocalDate.ofEpochDay(it))) }
            if (review) append(if (isEmpty()) s.note else ". " + s.note)
        }
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("cards://statement/${s.id}"))
                .putExtra(EXTRA_OPEN_STATEMENT, s.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_STATEMENTS)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setColor(card.color.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify((s.id % 100_000).toInt() + 200_000, n)
    }

    /** "Spends need a look": a statement arrived without some logged spends, or with other amounts. */
    fun showSpendReview(context: Context, card: app.cardkeep.data.Card, count: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("cards://card/${card.id}"))
                .putExtra(EXTRA_OPEN_CARD, card.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_SPENDS)
            .setSmallIcon(R.drawable.ic_stat_card)
            .setColor(card.color.toInt())
            .setContentTitle(if (count == 1) "1 spend needs a look" else "$count spends need a look")
            .setContentText("${card.nickname} •• ${card.last4}: the statement doesn't match what was logged")
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify((card.id % 100_000).toInt() + 300_000, n)
    }

    fun cancel(context: Context, cardId: Long) =
        NotificationManagerCompat.from(context).cancel(notificationId(cardId))
}
