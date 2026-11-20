package app.duely.reminders

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.duely.DuelyApp
import app.duely.data.hasDues
import kotlinx.coroutines.launch

private fun BroadcastReceiver.runAsync(context: Context, block: suspend (DuelyApp) -> Unit) {
    val pending = goAsync()
    val app = DuelyApp.from(context)
    app.scope.launch {
        try {
            block(app)
        } finally {
            pending.finish()
        }
    }
}

/** Fires a due reminder if the cycle it was set for is still the open, unpaid one. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cardId = Reminders.cardIdFrom(intent) ?: return
        val dueEpoch = intent.getLongExtra(Reminders.EXTRA_DUE, -1)
        val lead = intent.getIntExtra(Reminders.EXTRA_LEAD, -1)
        runAsync(context) { app ->
            // This lead has had its turn, shown or not; a later reschedule must not catch it up again.
            if (lead >= 0 && dueEpoch >= 0) Reminders.markFired(context, cardId, dueEpoch, lead)
            val cw = app.repo.cardWithDue(cardId) ?: return@runAsync
            if (cw.card.hasDues && cw.due.dueDate.toEpochDay() == dueEpoch && !cw.due.lastCyclePaid) {
                Notifications.showDue(context, cw)
            }
        }
    }
}

/** Notification buttons: mark paid, snooze. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cardId = Reminders.cardIdFrom(intent) ?: return
        val dueEpoch = intent.getLongExtra(Reminders.EXTRA_DUE, -1)
        Notifications.cancel(context, cardId)
        runAsync(context) { app ->
            when (intent.action) {
                ACTION_PAID -> {
                    val c = app.repo.cardOnce(cardId) ?: return@runAsync
                    app.repo.markPaid(cardId, dueEpoch, c.outstandingPaise)
                }
                ACTION_SNOOZE -> Reminders.snooze(context, cardId, dueEpoch)
            }
        }
    }

    companion object {
        const val ACTION_PAID = "app.duely.action.PAID"
        const val ACTION_SNOOZE = "app.duely.action.SNOOZE"

        fun intent(context: Context, action: String, cardId: Long, dueEpoch: Long): PendingIntent =
            PendingIntent.getBroadcast(
                context, 0,
                Intent(context, ActionReceiver::class.java)
                    .setAction(action)
                    .setData(Uri.parse("duely://action/$cardId"))
                    .putExtra(Reminders.EXTRA_DUE, dueEpoch),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}

/**
 * Alarms do not survive reboot or clock changes; rebuild them. Also runs when exact-alarm permission is
 * granted (AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED), so inexact alarms become exact.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runAsync(context) { Reminders.rescheduleAll(context) }
    }
}
