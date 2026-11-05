package dev.hatv.launcher

import dev.hatv.launcher.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ModelsTest {
    @Test
    fun powerConversionsAndUnavailableValues() {
        fun sensor(state: String, unit: String) =
            Entity("sensor.test", state, JSONObject().put("unit_of_measurement", unit))
        assertEquals(1.2, powerKw(sensor("1200", "W"))!!, .001)
        assertEquals(-2.0, powerKw(sensor("-2", "kW"))!!, .001)
        assertEquals(500.0, powerKw(sensor("0.5", "MW"))!!, .001)
        listOf("unknown", "unavailable", "NaN", "Infinity", "bad").forEach {
            assertNull(powerKw(sensor(it, "W")))
        }
        assertNull(powerKw(sensor("30", "kWh")))
        assertNull(powerKw(null))
    }

    @Test
    fun serverNormalization() {
        assertEquals(
            "http://homeassistant.local:8123",
            normalizeServer(" http://homeassistant.local:8123/ "),
        )
        assertEquals("https://ha.example/ha", normalizeServer("https://ha.example/ha/"))
        listOf(
                "ftp://ha",
                "https://user:secret@ha",
                "https://ha?token=secret",
                "https://ha/#x",
                "invalid",
            )
            .forEach { assertTrue(runCatching { normalizeServer(it) }.isFailure) }
    }

    @Test
    fun temperatureConvertsBothDirectionsAndKeepsMissingMissing() {
        assertEquals(32.0, temperature(0.0, false, true)!!, .001)
        assertEquals(21.5, temperature(70.7, true, false)!!, .001)
        assertNull(temperature(null, false, true))
    }

    @Test
    fun cameraUrlsKeepStreamQueryButRejectEmbeddedSecrets() {
        assertEquals(
            "http://go2rtc.local:1984/api/webrtc?src=front",
            normalizeCameraEndpoint("http://go2rtc.local:1984/api/webrtc?src=front"),
        )
        assertEquals("", normalizeCameraEndpoint(""))
        assertTrue(
            runCatching { normalizeCameraEndpoint("https://user:secret@camera.local/stream") }
                .isFailure
        )
        assertTrue(runCatching { normalizeCameraEndpoint("file:///etc/passwd") }.isFailure)
    }

    @Test
    fun entityStateAndMetadataParsing() {
        val entity =
            Entity.parse(
                JSONObject(
                    """{"entity_id":"light.test","state":"unavailable","attributes":{"friendly_name":"Desk"}}"""
                )
            )
        assertEquals("light", entity.domain)
        assertEquals("Desk", entity.name)
        assertFalse(entity.available)
        assertNull(entity.number("brightness"))
    }
}
