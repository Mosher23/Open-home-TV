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
import org.json.JSONArray
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
        runCatching {
                val j = JSONObject(prefs.getString("config", "{}")!!)
                val energy = j.optJSONObject("energy") ?: JSONObject()
                val cameras = j.optJSONArray("cameras") ?: JSONArray()
                val apps = j.optJSONArray("apps") ?: JSONArray()
                Config(
                    server = j.optString("server"),
                    demo = j.optBoolean("demo", true),
                    location = j.optString("location", "Duisburg"),
                    weather = j.optString("weather"),
                    controls = j.optJSONArray("controls")?.strings() ?: emptyList(),
                    energy = energy.keys().asSequence().associateWith { energy.getString(it) },
                    cameras =
                        (0 until cameras.length()).map {
                            cameras.getJSONObject(it).let { c ->
                                CameraConfig(
                                    c.getString("id"),
                                    c.optString("hls"),
                                    c.optString("rtc"),
                                )
                            }
                        },
                    apps =
                        (0 until apps.length()).map {
                            apps.getJSONObject(it).let { a ->
                                AppShortcut(
                                    a.getString("id"),
                                    a.optString("label"),
                                    a.optString("icon"),
                                )
                            }
                        },
                    animate = j.optBoolean("animate", true),
                    livePreviews = j.optBoolean("livePreviews"),
                    fahrenheit = j.optBoolean("fahrenheit"),
                    darkPanels = j.optBoolean("darkPanels"),
                    widgets =
                        j.optJSONArray("widgets")?.strings()?.takeIf {
                            it.toSet() == Config().widgets.toSet()
                        } ?: Config().widgets,
                )
            }
            .getOrDefault(Config())

    fun save(c: Config) {
        val j =
            JSONObject()
                .put("server", c.server)
                .put("demo", c.demo)
                .put("location", c.location)
                .put("weather", c.weather)
                .put("controls", JSONArray(c.controls))
                .put("energy", JSONObject(c.energy))
                .put(
                    "cameras",
                    JSONArray().apply {
                        c.cameras.forEach {
                            put(
                                JSONObject()
                                    .put("id", it.entityId)
                                    .put("hls", it.hls)
                                    .put("rtc", it.webRtc)
                            )
                        }
                    },
                )
                .put(
                    "apps",
                    JSONArray().apply {
                        c.apps.forEach {
                            put(
                                JSONObject()
                                    .put("id", it.id)
                                    .put("label", it.label)
                                    .put("icon", it.icon)
                            )
                        }
                    },
                )
                .put("animate", c.animate)
                .put("livePreviews", c.livePreviews)
                .put("fahrenheit", c.fahrenheit)
                .put("darkPanels", c.darkPanels)
                .put("widgets", JSONArray(c.widgets))
        check(prefs.edit().putString("config", j.toString()).commit())
    }
}
