package dev.hatv.launcher

import androidx.test.platform.app.InstrumentationRegistry
import dev.hatv.launcher.data.*
import java.security.KeyStore
import org.junit.Assert.*
import org.junit.Test

class KeystorePersistenceTest {
    @Test
    fun tokenIsEncryptedAndConfigurationSurvivesStoreRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val namespace = "test-${System.nanoTime()}"
        val store = SettingsStore(context, namespace)
        val token = "local-emulator-test-token"
        try {
            store.saveToken(token)
            store.save(
                Config(
                    location = "Berlin",
                    weather = "weather.home",
                    controls = listOf("light.desk"),
                )
            )
            val reopened = SettingsStore(context, namespace)
            assertEquals(token, reopened.token())
            assertEquals("Berlin", reopened.load().location)
            assertEquals(listOf("light.desk"), reopened.load().controls)
            val prefs = context.getSharedPreferences(namespace, 0)
            assertFalse(prefs.all.values.any { it.toString().contains(token) })
            assertNotEquals(token, prefs.getString("token", null))
            reopened.forgetToken()
            assertEquals("", reopened.token())
        } finally {
            context.deleteSharedPreferences(namespace)
            KeyStore.getInstance("AndroidKeyStore").apply {
                load(null)
                deleteEntry("ha_tv_access_token_$namespace")
            }
        }
    }
}
