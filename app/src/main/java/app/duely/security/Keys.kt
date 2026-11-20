package app.duely.security

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** AES-256-GCM keys held in Android Keystore. Blobs are iv(12) || ciphertext+tag. */
object Keys {
    private const val STORE = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    /** Seconds a vault key stays usable after a biometric / device-credential auth. */
    const val AUTH_WINDOW_SECONDS = 20

    fun getOrCreate(alias: String, requireAuth: Boolean): SecretKey {
        val ks = KeyStore.getInstance(STORE).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }

        val spec = KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)

        if (requireAuth) {
            spec.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) {
                spec.setUserAuthenticationParameters(
                    AUTH_WINDOW_SECONDS,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
                )
            } else {
                @Suppress("DEPRECATION")
                spec.setUserAuthenticationValidityDurationSeconds(AUTH_WINDOW_SECONDS)
            }
            // Enrolling a new fingerprint must not destroy the stored cards.
            spec.setInvalidatedByBiometricEnrollment(false)
        }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE).run {
            init(spec.build())
            generateKey()
        }
    }

    /** Removes a key entry, so the next [getOrCreate] makes a fresh one. */
    fun delete(alias: String) {
        runCatching { KeyStore.getInstance(STORE).apply { load(null) }.deleteEntry(alias) }
    }

    /**
     * True when [e] means the key is gone for good, not just waiting for an unlock: auth-bound keys are
     * permanently invalidated when the screen lock is removed (some devices then fail the key lookup itself).
     */
    fun isInvalidated(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.take(8).any { it is KeyPermanentlyInvalidatedException || it is UnrecoverableKeyException }

    fun encrypt(key: SecretKey, plain: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key)
        return c.iv + c.doFinal(plain)
    }

    fun decrypt(key: SecretKey, blob: ByteArray): ByteArray {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, blob, 0, IV_LEN))
        return c.doFinal(blob, IV_LEN, blob.size - IV_LEN)
    }
}
