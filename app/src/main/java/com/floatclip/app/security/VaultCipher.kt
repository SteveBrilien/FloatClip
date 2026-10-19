package com.floatclip.app.security

interface VaultCipher {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(payload: ByteArray): ByteArray
}
