package app.cardkeep.reminders

import app.cardkeep.data.hasDues
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import app.cardkeep.CardsApp
import app.cardkeep.domain.Billing
import app.cardkeep.domain.CardWithDue
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Due-date alarms. Each card has up to [MAX_SLOTS] lead-time slots plus one snooze slot; the
 * alarm identity is a data URI so slots can be cancelled without bookkeeping tables.
 * Everything here is derivable from cards + payments, so [rescheduleAll] is always safe.
 */
object Reminders {
    const val ACTION_REMIND = "app.cardkeep.action.REMIND"
    const val EXTRA_DUE = "due_epoch_day"
    const val EXTRA_LEAD = "lead_days"
    private const val MAX_SLOTS = 8
    private const val SNOOZE_SLOT = 99
    private val REMIND_TIME: LocalTime = LocalTime.of(9, 0)
    private val EVENING: LocalTime = LocalTime.of(19, 0)
    private val QUIET_FROM: LocalTime = LocalTime.of(21, 0)
    private val QUIET_UNTIL: LocalTime = LocalTime.of(8, 0)
    private const val CATCH_UP_DELAY_MS = 60_000L
    private const val PREFS = "reminders_fired"

    fun cardIdFrom(intent: Intent): Long? = intent.data?.pathSegments?.getOrNull(0)?.toLongOrNull()

    private fun pending(context: Context, cardId: Long, slot: Int, dueEpochDay: Long?, flags: Int, lead: Int? = null): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ACTION_REMIND)
            .setData(Uri.parse("cards://remind/$cardId/$slot"))
        if (dueEpochDay != null) intent.putExtra(EXTRA_DUE, dueEpochDay)
        if (lead != null) intent.putExtra(EXTRA_LEAD, lead)
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun alarms(context: Context) = context.getSystemService(AlarmManager::class.java)

    fun canExact(context: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || alarms(context).canScheduleExactAlarms()

    private fun set(context: Context, atMillis: Long, pi: PendingIntent) {
        val am = alarms(context)
        if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
    }

    fun cancel(context: Context, cardId: Long, includeSnooze: Boolean = true) {
        val slots = (0 until MAX_SLOTS) + if (includeSnooze) listOf(SNOOZE_SLOT) else emptyList()
        for (slot in slots) {
            pending(context, cardId, slot, null, PendingIntent.FLAG_NO_CREATE)?.let {
                alarms(context).cancel(it); it.cancel()
            }
        }
    }

    fun schedule(context: Context, cw: CardWithDue) {
        val id = cw.card.id
        cancel(context, id, includeSnooze = false)
        // No cycle (debit/prepaid, or a credit card without a due date yet): nothing to remind about.
        if (cw.due.overdue || !cw.card.hasDues) return
        val zone = ZoneId.systemDefault()
        val now = System.currentTimeMillis()
        val dueEpoch = cw.due.dueDate.toEpochDay()
        val leads = Billing.parseLeads(cw.card.reminderLeads).take(MAX_SLOTS)
        // Leads are sorted largest first, so the last one already past is the most recent reminder we missed.
        // Only today's: an older one was either shown already or is stale by now.
        val today = java.time.LocalDate.now(zone)
        val missed = leads.withIndex().lastOrNull { (_, lead) -> leadTime(cw, lead, zone) <= now }
            ?.takeIf { cw.due.dueDate.minusDays(it.value.toLong()) == today }
        leads.forEachIndexed { slot, lead ->
            val at = leadTime(cw, lead, zone)
            if (at > now) {
                pending(context, id, slot, dueEpoch, PendingIntent.FLAG_UPDATE_CURRENT, lead)?.let { set(context, at, it) }
            } else if (slot == missed?.index && !cw.due.lastCyclePaid && !fired(context, id, dueEpoch, lead)) {
                // Phone was off at 9:00, or the card/statement arrived later that day: catch up once, shortly.
                // The receiver marks it fired, so later reschedules don't repeat it.
                pending(context, id, slot, dueEpoch, PendingIntent.FLAG_UPDATE_CURRENT, lead)?.let { set(context, now + CATCH_UP_DELAY_MS, it) }
            }
        }
    }

    private fun leadTime(cw: CardWithDue, lead: Int, zone: ZoneId): Long =
        cw.due.dueDate.minusDays(lead.toLong()).atTime(REMIND_TIME).atZone(zone).toInstant().toEpochMilli()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun firedKey(cardId: Long, dueEpochDay: Long, lead: Int) = "$cardId:$dueEpochDay:$lead"

    private fun fired(context: Context, cardId: Long, dueEpochDay: Long, lead: Int) =
        prefs(context).getBoolean(firedKey(cardId, dueEpochDay, lead), false)

    /** Records that this card/cycle/lead reminder went off (shown or not), so it is never caught up again. */
    fun markFired(context: Context, cardId: Long, dueEpochDay: Long, lead: Int) {
        prefs(context).edit().putBoolean(firedKey(cardId, dueEpochDay, lead), true).apply()
    }

    /** Drops fired markers for cycles that ended over two months ago. */
    private fun pruneFired(context: Context) {
        val cutoff = java.time.LocalDate.now().minusDays(60).toEpochDay()
        val p = prefs(context)
        val old = p.all.keys.filter { k -> k.split(':').getOrNull(1)?.toLongOrNull()?.let { it < cutoff } ?: true }
        if (old.isNotEmpty()) p.edit().apply { old.forEach { remove(it) } }.apply()
    }

    /**
     * Re-fires the reminder at 7 pm today, or in an hour if it is already evening. Never at night:
     * anything that would land after 9 pm or before 8 am moves to 9 am the next morning.
     */
    fun snooze(context: Context, cardId: Long, dueEpochDay: Long) {
        val now = LocalDateTime.now()
        val evening = now.toLocalDate().atTime(EVENING)
        var at = if (now.isBefore(evening.minusMinutes(30))) evening else now.plusHours(1)
        if (at.toLocalTime().isAfter(QUIET_FROM)) at = at.toLocalDate().plusDays(1).atTime(REMIND_TIME)
        else if (at.toLocalTime().isBefore(QUIET_UNTIL)) at = at.toLocalDate().atTime(REMIND_TIME)
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        pending(context, cardId, SNOOZE_SLOT, dueEpochDay, PendingIntent.FLAG_UPDATE_CURRENT)?.let { set(context, millis, it) }
    }

    suspend fun rescheduleAll(context: Context) {
        pruneFired(context)
        CardsApp.from(context).repo.snapshot().forEach { schedule(context, it) }
    }
}
