package app.duely.reminders

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
import app.duely.MainActivity
import app.duely.R
import app.duely.domain.CardWithDue
import app.duely.domain.Dates
import app.duely.domain.Money

object Notifications {
    const val CHANNEL = "due_reminders"
    const val CHANNEL_STATEMENTS = "statements"
    const val EXTRA_OPEN_CARD = "open_card"
    const val EXTRA_OPEN_STATEMENT = "open_statement"

    fun createChannel(context: Context) {
        val ch = NotificationChannel(CHANNEL, "Due-date reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Reminders before a card's payment is due" }
        val st = NotificationChannel(CHANNEL_STATEMENTS, "New statements", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "When a statement arrives by email" }
        context.getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(ch, st))
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
                .setData(Uri.parse("duely://card/${c.id}"))
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
    fun showStatement(context: Context, card: app.duely.data.Card, s: app.duely.data.Statement) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val review = s.status != app.duely.data.Ledger.STATUS_OK
        val title = if (review) "${card.nickname} statement needs a look" else "${card.nickname} statement"
        val text = buildString {
            s.totalDuePaise?.let { append(Money.inr(it, true)) }
            s.dueEpochDay?.let { append(" due ").append(Dates.weekday(java.time.LocalDate.ofEpochDay(it))) }
            if (review) append(if (isEmpty()) s.note else ". " + s.note)
        }
        val open = PendingIntent.getActivity(
            context, 1,
            Intent(context, MainActivity::class.java)
                .setData(Uri.parse("duely://statement/${s.id}"))
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

    fun cancel(context: Context, cardId: Long) =
        NotificationManagerCompat.from(context).cancel(notificationId(cardId))
}
