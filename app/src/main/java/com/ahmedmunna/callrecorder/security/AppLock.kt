package com.ahmedmunna.callrecorder.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** PINs are never stored. Only a salted PBKDF2 hash is kept in DataStore. */
object PinHasher {
    private const val ITER = 120_000

    fun newSalt(): String = SecureRandom().let { r -> ByteArray(16).also(r::nextBytes) }.toHex()

    fun hash(pin: String, saltHex: String): String {
        val spec = PBEKeySpec(pin.toCharArray(), saltHex.hexToBytes(), ITER, 256)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded.toHex()
    }

    fun verify(pin: String, saltHex: String, expected: String): Boolean =
        MessageDigest.isEqual(hash(pin, saltHex).toByteArray(), expected.toByteArray())

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.hexToBytes() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}

object Biometrics {
    fun available(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, onSuccess: () -> Unit, onFail: (String) -> Unit = {}) {
        val executor = ContextCompat.getMainExecutor(activity)
        val bp = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFail(errString.toString())
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        bp.authenticate(info)
    }
}
