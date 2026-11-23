package app.cardkeep.security

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.security.keystore.UserNotAuthenticatedException
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object Auth {
    /** True while a system auth UI is up, so the app lock ignores the resulting onStop. */
    @Volatile var inProgress = false
        private set

    fun deviceSecure(context: Context): Boolean =
        context.getSystemService(KeyguardManager::class.java).isDeviceSecure

    // BIOMETRIC_STRONG | DEVICE_CREDENTIAL is only accepted from API 30. Below that a weak biometric can get
    // through, which doesn't unlock vault keys; [unlockVault] covers that with a keyguard PIN confirmation.
    private fun authenticators() =
        if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Shows the system biometric / PIN prompt. Returns true on success. Main thread only. */
    suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String? = null): Boolean =
        suspendCancellableCoroutine { cont ->
            inProgress = true
            val prompt = BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        inProgress = false
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        inProgress = false
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .apply { if (subtitle != null) setSubtitle(subtitle) }
                .setAllowedAuthenticators(authenticators())
                .build()
            cont.invokeOnCancellation {
                inProgress = false
                // On rotation the prompt survives and reconnects to the new activity's callback; leave it up.
                if (!activity.isChangingConfigurations) prompt.cancelAuthentication()
            }
            prompt.authenticate(info)
        }

    /**
     * Runs [block], which touches auth-bound vault keys. If the auth window has lapsed, prompts
     * once and retries. Returns null if the user cancelled.
     */
    suspend fun <T : Any> withVault(activity: FragmentActivity, title: String, block: suspend () -> T): T? {
        try {
            return block()
        } catch (_: UserNotAuthenticatedException) {
        }
        return unlockVault(activity, title, null, block)
    }

    /**
     * Always prompts, then runs [block] (for flows that must confirm the user every time). Returns null if
     * the user cancelled.
     *
     * API 26-29 can't combine BIOMETRIC_STRONG with DEVICE_CREDENTIAL, so the prompt may accept a weak
     * biometric (e.g. face) that doesn't unlock timed Keystore keys. If the keys still refuse after a
     * "successful" prompt, confirm the PIN / pattern / password through the keyguard, which always does.
     */
    suspend fun <T : Any> unlockVault(
        activity: FragmentActivity, title: String, subtitle: String? = null, block: suspend () -> T,
    ): T? {
        if (!authenticate(activity, title, subtitle)) return null
        return try {
            block()
        } catch (e: UserNotAuthenticatedException) {
            if (Build.VERSION.SDK_INT >= 30) throw e
            if (!confirmCredential(activity, title, subtitle)) return null
            block()
        }
    }

    /** The system PIN / pattern / password screen. Returns true if confirmed. Main thread only. */
    private suspend fun confirmCredential(activity: FragmentActivity, title: String, subtitle: String?): Boolean {
        @Suppress("DEPRECATION")
        val intent = activity.getSystemService(KeyguardManager::class.java)
            .createConfirmDeviceCredentialIntent(title, subtitle) ?: return false
        return suspendCancellableCoroutine { cont ->
            inProgress = true
            lateinit var launcher: ActivityResultLauncher<Intent>
            launcher = activity.activityResultRegistry.register(
                "cards_confirm_credential_${System.nanoTime()}", ActivityResultContracts.StartActivityForResult(),
            ) { r ->
                inProgress = false
                launcher.unregister()
                if (cont.isActive) cont.resume(r.resultCode == Activity.RESULT_OK)
            }
            cont.invokeOnCancellation { inProgress = false; launcher.unregister() }
            launcher.launch(intent)
        }
    }
}
