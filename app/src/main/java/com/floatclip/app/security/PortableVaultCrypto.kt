package com.floatclip.app.security

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object PortableVaultCrypto {
    private val random = SecureRandom()
    private const val VERSION = 1
    private const val ITERATIONS = 210_000
    private const val KEY_BITS = 256
    private const val AAD = "FloatClipPortableVault:v1"

    fun encrypt(plainText: String, pin: String): String {
        require(pin.matches(Regex("\\d{6}"))) { "PIN must be exactly 6 digits" }
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val key = deriveKey(pin, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("format", "floatclip-portable-vault")
            .put("version", VERSION)
            .put("kdf", "PBKDF2-HMAC-SHA256")
            .put("iterations", ITERATIONS)
            .put("salt", b64(salt))
            .put("iv", b64(iv))
            .put("ciphertext", b64(encrypted))
            .toString()
    }

    fun decrypt(envelopeText: String, pin: String): String {
        require(pin.matches(Regex("\\d{6}"))) { "PIN must be exactly 6 digits" }
        val json = JSONObject(envelopeText)
        require(json.optString("format") == "floatclip-portable-vault") { "Unsupported vault format" }
        require(json.getInt("version") == VERSION) { "Unsupported vault version" }
        val iterations = json.optInt("iterations", ITERATIONS).coerceIn(100_000, 1_000_000)
        val salt = unb64(json.getString("salt"))
        val iv = unb64(json.getString("iv"))
        val encrypted = unb64(json.getString("ciphertext"))
        val key = deriveKey(pin, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        cipher.updateAAD(AAD.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    private fun deriveKey(pin: String, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(pin.toCharArray(), salt, iterations, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)
    private fun unb64(value: String): ByteArray = Base64.decode(value, Base64.NO_WRAP)
}
