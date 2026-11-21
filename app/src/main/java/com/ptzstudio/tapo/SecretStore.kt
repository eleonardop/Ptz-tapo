package com.ptzstudio.tapo

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Guarda as configurações (que têm as senhas das câmeras) cifradas com uma chave do próprio celular
 * (Android Keystore). A chave não sai do aparelho; outro app ou uma cópia do arquivo não conseguem ler.
 */
object SecretStore {
    private const val ALIAS = "ptz-tapo-config"
    private const val PREFIX = "k1:"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    /** Devolve o texto cifrado, ou nulo se o celular não deixou cifrar (aí o app guarda sem cifra). */
    fun seal(plain: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        PREFIX + Base64.encodeToString(data, Base64.NO_WRAP)
    } catch (_: Exception) {
        null
    }

    fun open(sealed: String?): String? {
        if (sealed == null || !sealed.startsWith(PREFIX)) return sealed
        return try {
            val data = Base64.decode(sealed.removePrefix(PREFIX), Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
            String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }
}
