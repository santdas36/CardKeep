package app.cardkeep.ingest.alerts

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import app.cardkeep.CardsApp
import app.cardkeep.data.SpendSource
import kotlinx.coroutines.launch

/** New SMS: card alerts go to the spend log as they arrive. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = CardsApp.from(context)
        if (!app.prefs.readSms) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        // A long SMS arrives in parts; join them per sender.
        val messages = parts.filterNotNull().groupBy { it.originatingAddress.orEmpty() }
            .mapValues { (_, p) -> p.joinToString("") { it.messageBody.orEmpty() } to (p.minOfOrNull { it.timestampMillis } ?: System.currentTimeMillis()) }
        val pending = goAsync()
        app.scope.launch {
            try {
                for ((from, msg) in messages) {
                    if (from.none(Char::isLetter)) continue
                    AlertIngest.handle(app, from, msg.first, msg.second, SpendSource.SMS)
                }
            } finally {
                pending.finish()
            }
        }
    }
}

/**
 * Reads bank alerts from messaging apps' notifications. This is the only way to see RCS messages (they
 * never reach the SMS inbox), and it also catches SMS when the inbox isn't readable. Only new
 * notifications are seen; nothing is read back from the apps themselves.
 */
class AlertListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = CardsApp.from(this)
        if (!app.prefs.readNotifications || sbn.packageName !in MESSAGING_APPS) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val (sender, body) = read(n) ?: return
        app.scope.launch { AlertIngest.handle(app, sender, body, sbn.postTime, SpendSource.NOTIFICATION) }
    }

    /** Sender and full text of the newest message in a notification. */
    private fun read(n: Notification): Pair<String?, String>? {
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        val title = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        if (last?.text != null) return (last.person?.name?.toString() ?: title) to last.text.toString()
        val text = n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)
        return text?.toString()?.takeIf { it.isNotBlank() }?.let { title to it }
    }

    companion object {
        /** SMS / RCS apps whose notifications carry bank alerts. */
        val MESSAGING_APPS = setOf(
            "com.google.android.apps.messaging", // Google Messages (SMS and RCS)
            "com.samsung.android.messaging", // Samsung Messages
            "com.android.mms", // AOSP and several OEM messaging apps
            "com.oneplus.mms",
            "com.truecaller",
        )
    }
}
