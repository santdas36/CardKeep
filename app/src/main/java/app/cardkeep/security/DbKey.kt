package app.cardkeep.security

import android.content.Context
import android.util.Base64
import java.security.SecureRandom

/**
 * SQLCipher passphrase: 32 random bytes (hex-encoded), wrapped by a Keystore key that does not
 * need user auth, so background reminder jobs can still read due dates while the app is locked.
 * Card number / expiry / CVV get a second, auth-bound layer in [Vault].
 */
object DbKey {
    private const val ALIAS = "duely_db_v1"
    private const val PREFS = "duely_keys"
    private const val PREF_WRAPPED = "db_wrapped"

    fun passphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = Keys.getOrCreate(ALIAS, requireAuth = false)
        prefs.getString(PREF_WRAPPED, null)?.let {
            return Keys.decrypt(key, Base64.decode(it, Base64.NO_WRAP))
        }
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val hex = raw.joinToString("") { "%02x".format(it) }.toByteArray(Charsets.US_ASCII)
        raw.fill(0)
        val wrapped = Keys.encrypt(key, hex)
        prefs.edit().putString(PREF_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP)).commit()
        return hex
    }
}
