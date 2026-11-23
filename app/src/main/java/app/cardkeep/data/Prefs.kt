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

    /** Parser generation the stored statements were last refreshed with (see Ledger.PARSER_VERSION). */
    var parserVersion: Int
        get() = sp.getInt("parser_version", 1)
        set(v) = sp.edit { putInt("parser_version", v) }

    var askedNotifications: Boolean
        get() = sp.getBoolean("asked_notif", false)
        set(v) = sp.edit { putBoolean("asked_notif", v) }

    /** Colour palette id (see app.cardkeep.ui.Palettes). */
    var palette: String
        get() = sp.getString("palette", "indigo") ?: "indigo"
        set(v) = sp.edit { putString("palette", v) }

    /** "system", "light" or "dark". */
    var themeMode: String
        get() = sp.getString("theme_mode", "system") ?: "system"
        set(v) = sp.edit { putString("theme_mode", v) }

    /** When the last backup file was saved (0 = never). */
    var lastBackupAt: Long
        get() = sp.getLong("last_backup_at", 0L)
        set(v) = sp.edit { putLong("last_backup_at", v) }
}
