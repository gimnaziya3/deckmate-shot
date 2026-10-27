package app.deckmate.shot

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Ключ игрока хранится зашифрованным (AES-GCM, ключ шифрования в Android Keystore), в логи и копии не попадает. Также хранит последний статус плитки. */
class SecureStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("deckmate", Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    var key: String?
        get() {
            val stored = prefs.getString("k", null) ?: return null
            return try {
                val raw = Base64.decode(stored, Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
                String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
            } catch (e: Exception) {
                null
            }
        }
        set(value) {
            if (value.isNullOrBlank()) {
                prefs.edit().remove("k").apply()
                return
            }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, secretKey())
            val enc = c.iv + c.doFinal(value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString("k", Base64.encodeToString(enc, Base64.NO_WRAP)).apply()
        }

    var status: String
        get() = prefs.getString("status", "idle") ?: "idle"
        set(value) {
            prefs.edit().putString("status", value).apply()
        }

    companion object {
        private const val ALIAS = "deckmate_key"
    }
}
