package com.gemmamobile.securechat

import android.app.Activity
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Debug
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object Security {

    private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /** Blocks screenshots, screen recording, casting and the Recents thumbnail. */
    fun protectWindow(activity: Activity) {
        activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity.setRecentsScreenshotEnabled(false)
        }
    }

    /**
     * True when this looks like a tampered/repackaged or actively debugged release build.
     * A genuine release build is never debuggable and never has a debugger attached.
     */
    fun isCompromised(context: Context): Boolean {
        if (BuildConfig.DEBUG) return false
        val debuggable = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return debuggable || Debug.isDebuggerConnected() || Debug.waitingForDebugger()
    }

    fun canLock(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(activity: FragmentActivity, onSuccess: () -> Unit, onFail: (String) -> Unit) {
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) =
                    onFail(errString.toString())
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Gemma Secure Chat")
            .setSubtitle("Use your fingerprint, face or screen lock")
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    /** Copies text while marking it sensitive so it isn't shown in clipboard previews. */
    fun copySensitive(context: Context, text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText("Gemma reply", text)
        clip.description.extras = PersistableBundle().apply {
            putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        cm.setPrimaryClip(clip)
    }
}
