package dev.hatv.launcher

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import dev.hatv.launcher.data.*
import dev.hatv.launcher.phone.*
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PhoneSetupTest {
    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()

    private val http =
        OkHttpClient.Builder().callTimeout(5, java.util.concurrent.TimeUnit.SECONDS).build()

    private fun onTv(action: () -> Unit) = instrumentation.runOnMainSync(action)

    private fun request(
        url: String,
        key: ByteArray,
        op: String,
        args: JSONObject = JSONObject(),
    ): JSONObject {
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val nonce = Base64.getEncoder().encodeToString(iv)
        val plain =
            JSONObject(args.toString()).put("op", op).put("client", "emulator-phone-client-0001")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD("OpenHomeTV setup v1 request".toByteArray())
        val envelope =
            JSONObject()
                .put("iv", nonce)
                .put(
                    "data",
                    Base64.getEncoder()
                        .encodeToString(cipher.doFinal(plain.toString().toByteArray())),
                )
        return http
            .newCall(
                Request.Builder()
                    .url(url + "api")
                    .header("Origin", url.trimEnd('/'))
                    .post(envelope.toString().toRequestBody("application/json".toMediaType()))
                    .build()
            )
            .execute()
            .use {
                assertEquals(200, it.code)
                val response = JSONObject(it.body!!.string())
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(key, "AES"),
                    GCMParameterSpec(128, Base64.getDecoder().decode(response.getString("iv"))),
                )
                cipher.updateAAD("OpenHomeTV setup v1 response:$nonce".toByteArray())
                JSONObject(
                    String(cipher.doFinal(Base64.getDecoder().decode(response.getString("data"))))
                )
            }
    }

    @Test
    fun approvalConnectSelectionAndApplyAreSeparateAndServerClosesAfterSaving() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var saved: Config? = null
        var savedToken: String? = null
        var fetches = 0
        val fixture =
            listOf(
                    Entity("sensor.pv", "1500", JSONObject().put("unit_of_measurement", "W")),
                    Entity("light.desk", "on"),
                    Entity("camera.front", "idle"),
                    Entity("camera.back", "idle"),
                    Entity(
                        "sensor.pv_energy",
                        "1000",
                        JSONObject().put("unit_of_measurement", "kWh"),
                    ),
                    Entity("weather.home", "cloudy"),
                )
                .associateBy { it.id }
        val coordinator =
            PhoneSetupCoordinator(
                instrumentation.targetContext,
                scope,
                { Config() },
                { "" },
                { listOf(InstalledApp("test.app", "Test app", null, Intent())) },
                { server, token ->
                    assertEquals("http://ha.local:8123", server)
                    assertEquals("test-secret", token)
                    fetches++
                    fixture
                },
                { next, token ->
                    saved = next
                    savedToken = token
                },
                {},
            )
        try {
            onTv { coordinator.start() }
            val state = withTimeout(8000) { coordinator.state.first { it.phase == "waiting" } }
            val url = state.url.substringBefore('#')
            val key = Base64.getUrlDecoder().decode(state.url.substringAfter('#'))
            assertEquals("approval", request(url, key, "pair").getString("phase"))
            assertFalse(
                request(
                        url,
                        key,
                        "connect",
                        JSONObject()
                            .put("server", "http://ha.local:8123")
                            .put("token", "test-secret"),
                    )
                    .getBoolean("ok")
            )
            assertEquals(0, fetches)
            onTv { coordinator.approve() }
            assertFalse(request(url, key, "info").toString().contains("test-secret"))
            val connected =
                request(
                    url,
                    key,
                    "connect",
                    JSONObject().put("server", "http://ha.local:8123").put("token", "test-secret"),
                )
            assertTrue(connected.getBoolean("connected"))
            assertTrue(
                request(url, key, "connect", JSONObject().put("server", "http://ha.local:8123"))
                    .getBoolean("connected")
            )
            assertEquals(2, fetches)
            assertNull(saved)
            val next =
                parseConfig(connected.getJSONObject("config"))
                    .copy(
                        weather = "weather.home",
                        controls = listOf("light.desk"),
                        energy = mapOf("solar" to "sensor.pv"),
                        cameras = listOf(CameraConfig("camera.back"), CameraConfig("camera.front")),
                        homeCameraCount = 1,
                        energyMeter = "sensor.pv_energy",
                        energyDevices = mapOf("sensor.pv" to "sensor.pv_energy"),
                        apps = listOf(AppShortcut("test.app")),
                    )
            assertFalse(
                request(
                        url,
                        key,
                        "apply",
                        JSONObject()
                            .put(
                                "config",
                                next.copy(apps = listOf(AppShortcut("unknown"))).toJson(),
                            ),
                    )
                    .getBoolean("ok")
            )
            assertFalse(
                request(
                        url,
                        key,
                        "apply",
                        JSONObject().put("config", next.toJson().put("homeCameraCount", 5)),
                    )
                    .getBoolean("ok")
            )
            assertFalse(
                request(
                        url,
                        key,
                        "apply",
                        JSONObject().put("config", next.copy(energyMeter = "sensor.pv").toJson()),
                    )
                    .getBoolean("ok")
            )
            assertNull(saved)
            assertTrue(
                request(url, key, "apply", JSONObject().put("config", next.toJson()))
                    .getBoolean("ok")
            )
            assertEquals(next, saved)
            assertEquals("test-secret", savedToken)
            withTimeout(4000) { coordinator.state.first { it.phase == "done" } }
            assertTrue(runCatching { request(url, key, "info") }.isFailure)
        } finally {
            onTv { coordinator.stop() }
            scope.cancel()
            http.connectionPool.evictAll()
        }
    }

    @Test
    fun cancellationDiscardsCredentialsAndExpiryClosesListener() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var saves = 0
        val coordinator =
            PhoneSetupCoordinator(
                instrumentation.targetContext,
                scope,
                { Config() },
                { "" },
                { emptyList() },
                { _, _ -> emptyMap() },
                { _, _ -> saves++ },
                {},
                lifetimeMillis = 1500,
            )
        try {
            onTv { coordinator.start() }
            val state = withTimeout(8000) { coordinator.state.first { it.phase == "waiting" } }
            val url = state.url.substringBefore('#')
            val key = Base64.getUrlDecoder().decode(state.url.substringAfter('#'))
            withTimeout(5000) { coordinator.state.first { it.phase == "expired" } }
            assertTrue(runCatching { request(url, key, "pair") }.isFailure)
            assertEquals(0, saves)
            onTv { coordinator.start() }
            val next = withTimeout(8000) { coordinator.state.first { it.phase == "waiting" } }
            val nextUrl = next.url.substringBefore('#')
            val nextKey = Base64.getUrlDecoder().decode(next.url.substringAfter('#'))
            request(nextUrl, nextKey, "pair")
            onTv { coordinator.approve() }
            assertTrue(
                request(
                        nextUrl,
                        nextKey,
                        "connect",
                        JSONObject()
                            .put("server", "http://fixture.local:8123")
                            .put("token", "unsaved-test-secret"),
                    )
                    .getBoolean("ok")
            )
            onTv { coordinator.stop() }
            assertTrue(runCatching { request(nextUrl, nextKey, "info") }.isFailure)
            assertEquals("closed", coordinator.state.value.phase)
            assertEquals(0, saves)
        } finally {
            onTv { coordinator.stop() }
            scope.cancel()
            http.connectionPool.evictAll()
        }
    }
}
