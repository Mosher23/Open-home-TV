package dev.hatv.launcher.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

class SettingsStore(context: Context, private val namespace: String = "launcher") {
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    private val alias = "ha_tv_access_token_$namespace"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(alias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                .apply {
                    init(
                        KeyGenParameterSpec.Builder(
                                alias,
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setKeySize(256)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build()
                    )
                }
                .generateKey()
    }

    fun token(): String {
        val encrypted = prefs.getString("token", null) ?: return ""
        val iv = Base64.decode(prefs.getString("iv", ""), Base64.NO_WRAP)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
        }
    }

    fun saveToken(token: String) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        check(
            prefs
                .edit()
                .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit()
        )
    }

    fun forgetToken() {
        check(prefs.edit().remove("token").remove("iv").commit())
    }

    fun load(): Config =
        runCatching { parseConfig(JSONObject(prefs.getString("config", "{}")!!)) }
            .getOrDefault(Config())

    fun save(c: Config) {
        persist(c, null)
    }

    /** Configuration and replacement credentials are committed together. */
    fun saveConnection(c: Config, token: String?) {
        persist(c, token)
    }

    private fun persist(c: Config, token: String?) {
        val editor = prefs.edit().putString("config", c.toJson().toString())
        if (token != null) {
            require(token.isNotBlank())
            val cipher =
                Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
            editor
                .putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
        }
        check(editor.commit())
    }
}
