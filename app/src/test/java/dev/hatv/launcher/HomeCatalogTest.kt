package dev.hatv.launcher

import dev.hatv.launcher.data.*
import java.time.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class HomeCatalogTest {
    @Test
    fun deviceAreaFallbackEntityOverrideAndDiagnosticFiltering() {
        fun sensor(id: String, cls: String) =
            Entity(
                "sensor.$id",
                "21",
                JSONObject().put("device_class", cls).put("friendly_name", id),
            )
        val temp = sensor("temperature", "temperature")
        val humidity = sensor("humidity", "humidity")
        val deviceTemp = sensor("plug_temperature", "temperature")
        val diagnostic = sensor("internal_temperature", "temperature")
        val co2 = sensor("co2", "carbon_dioxide")
        val catalog =
            HomeCatalog(
                mapOf(
                    "office" to AreaInfo("office", "Office"),
                    "bedroom" to AreaInfo("bedroom", "Bedroom"),
                ),
                mapOf(
                    temp.id to EntityInfo(device = "d"),
                    humidity.id to EntityInfo(area = "bedroom", device = "d"),
                    diagnostic.id to EntityInfo(device = "d", category = "diagnostic"),
                    co2.id to EntityInfo(device = "d"),
                ),
                mapOf("d" to "office"),
            )
        val grouped =
            rooms(
                listOf(temp, humidity, deviceTemp, diagnostic, co2).associateBy { it.id },
                catalog,
            )
        assertEquals(listOf("Bedroom", "Office"), grouped.map { it.name })
        assertEquals(setOf(temp.id, co2.id), grouped.last().metrics.map { it.id }.toSet())
    }

    @Test
    fun scenesWithNoPreviousActivationRemainUsable() {
        assertTrue(Entity("scene.evening", "unknown").available)
        assertFalse(Entity("scene.evening", "unavailable").available)
        assertFalse(Entity("light.lamp", "unknown").available)
    }

    @Test
    fun calendarAllDayUsesServerZoneAcrossDaylightSaving() {
        val events =
            parseCalendarEvents(
                "calendar.home",
                JSONArray(
                    """[
            {"summary":"All day","start":{"date":"2026-10-25"},"end":{"date":"2026-10-26"}},
            {"summary":"Meeting","start":{"dateTime":"2026-10-25T11:00:00+01:00"},"end":{"dateTime":"2026-10-25T12:00:00+01:00"}},
            {"summary":"Malformed","start":"bad","end":"bad"}
        ]"""
                ),
                ZoneId.of("Europe/Berlin"),
            )
        assertEquals(2, events.size)
        assertTrue(events.first().allDay)
        assertEquals(25, Duration.between(events.first().start, events.first().end).toHours())
        assertEquals(Instant.parse("2026-10-25T10:00:00Z"), events.last().start)
    }

    @Test
    fun oldConfigurationStillLoadsAndPowerDevicesRoundTrip() {
        assertEquals(
            emptyList<String>(),
            parseConfig(JSONObject("""{"demo":false}""")).powerDevices,
        )
        val config = Config(powerDevices = listOf("sensor.fridge_power"))
        assertEquals(config, parseConfig(config.toJson()))
    }
}
