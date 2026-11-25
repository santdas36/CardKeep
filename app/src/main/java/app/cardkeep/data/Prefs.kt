package app.cardkeep.data

import android.content.Context
import androidx.core.content.edit

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("duely_settings", Context.MODE_PRIVATE)

    /** Seconds in background before the app locks again. 0 = immediately. */
    var lockTimeoutSec: Int
        get() = sp.getInt("lock_timeout", 30)
        set(v) = sp.edit { putInt("lock_timeout", v) }

    var defaultLeads: String
        get() = sp.getString("default_leads", "5,2,0") ?: "5,2,0"
        set(v) = sp.edit { putString("default_leads", v) }

    /** Banks (issuer names) whose alert emails are read. */
    var emailAlertBanks: Set<String>
        get() = sp.getStringSet("email_alert_banks", emptySet()).orEmpty().toSet()
        set(v) = sp.edit { putStringSet("email_alert_banks", v) }

    /** Received time of the newest alert email read, so each check only reads newer ones. */
    var emailAlertsUpTo: Long
        get() = sp.getLong("email_alerts_up_to", 0L)
        set(v) = sp.edit { putLong("email_alerts_up_to", v) }

    /** Payment alerts (spend ids) already used to mark a bill paid, so each counts once. */
    var autoPaidSpends: Set<Long>
        get() = sp.getStringSet("auto_paid_spends", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = sp.edit { putStringSet("auto_paid_spends", v.map { it.toString() }.toSet()) }

    /** Parser generation the stored statements were last refreshed with (see Ledger.PARSER_VERSION). */
    var parserVersion: Int
        get() = sp.getInt("parser_version", 1)
        set(v) = sp.edit { putInt("parser_version", v) }

    /** The first-run screens were finished or skipped (or the app already had cards). */
    var onboarded: Boolean
        get() = sp.getBoolean("onboarded", false)
        set(v) = sp.edit { putBoolean("onboarded", v) }

    /** Whether [onboarded] was ever written (it wasn't on installs from before onboarding existed). */
    val onboardedKnown: Boolean get() = sp.contains("onboarded")

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notif", false)
        set(v) = sp.edit { putBoolean("asked_notif", v) }

    /** Colour palette id (see app.cardkeep.ui.Palettes). */
    var palette: String
        get() = sp.getString("palette", "indigo") ?: "indigo"
        set(v) = sp.edit { putString("palette", v) }

    /** The last solid palette picked, restored when switching back from wallpaper colours. */
    var solidPalette: String
        get() = sp.getString("solid_palette", null) ?: palette.takeIf { it != "dynamic" } ?: "indigo"
        set(v) = sp.edit { putString("solid_palette", v) }

    /** "system", "light" or "dark". */
    var themeMode: String
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(v) = sp.edit { putString("theme_mode", v) }

    /** Spend tracking: read bank SMS (needs the SMS permissions too). */
    var readSms: Boolean
        get() = sp.getBoolean("read_sms", false)
        set(v) = sp.edit { putBoolean("read_sms", v) }

    /** Spend tracking: read bank alerts from message notifications (RCS). Needs notification access too. */
    var readNotifications: Boolean
        get() = sp.getBoolean("read_notifications", false)
        set(v) = sp.edit { putBoolean("read_notifications", v) }

    /** Newest SMS date already scanned from the inbox (0 = never scanned). */
    var smsScannedUpTo: Long
        get() = sp.getLong("sms_scanned_up_to", 0L)
        set(v) = sp.edit { putLong("sms_scanned_up_to", v) }

    /** When the last backup file was saved (0 = never). */
    var lastBackupAt: Long
        get() = sp.getLong("last_backup_at", 0L)
        set(v) = sp.edit { putLong("last_backup_at", v) }
}
