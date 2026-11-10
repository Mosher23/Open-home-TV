package dev.hatv.launcher

import dev.hatv.launcher.data.*
import org.json.*
import org.junit.Assert.*
import org.junit.Test

class HomeCameraEnergyTest {
    @Test
    fun upgradesLegacyHomeWithoutLosingCameraEndpointsOrOrder() {
        val legacy =
            JSONObject(
                """{"demo":false,"livePreviews":false,"widgets":["cameras","energy","weather","climate","lights","scenes"],"cameras":[{"id":"camera.second","hls":"http://ha.local/a.m3u8"},{"id":"camera.first","rtc":"http://rtc.local/offer"}]}"""
            )
        val c = parseConfig(legacy)
        assertTrue(c.livePreviews)
        assertEquals(1, c.homeCameraCount)
        assertEquals(listOf("cameras", "energy", "weather", "lights", "scenes"), c.widgets)
        assertEquals(listOf("camera.second"), c.homeCameras().map { it.entityId })
        assertEquals("http://ha.local/a.m3u8", c.cameras.first().hls)
        assertEquals(
            c.copy(livePreviews = false, homeCameraCount = 4),
            parseConfig(c.copy(livePreviews = false, homeCameraCount = 4).toJson()),
        )
    }

    @Test
    fun cameraLimitsAndEnergyMappingsSurvivePersistence() {
        val c =
            Config(
                demo = false,
                cameras = (1..8).map { CameraConfig("camera.c$it") },
                homeCameraCount = 4,
                energyMeter = "sensor.home_energy",
                energyDevices = mapOf("sensor.desk_power" to "sensor.desk_energy"),
            )
        assertEquals(c, parseConfig(c.toJson()))
        assertEquals(4, c.homeCameras().size)
        assertTrue(c.copy(homeCameraCount = 0).homeCameras().isEmpty())
        assertEquals(4, c.copy(homeCameraCount = 999).homeCameras().size)
        assertEquals(0, parseConfig(c.toJson().put("homeCameraCount", -5)).homeCameraCount)
    }

    @Test
    fun recorderChangesNeverUseLifetimeReadingsOrUnknownAsZero() {
        val values =
            todayChanges(
                JSONObject(
                    """{"a":[{"change":0.4,"sum":9500},{"change":0.1}],"b":[{"change":null}],"c":[{"change":-0.5}],"d":[{"change":0}]}"""
                ),
                listOf("a", "b", "c", "d", "missing"),
            )
        assertEquals(.5, values["a"]!!, .000001)
        assertNull(values["b"])
        assertNull(values["c"])
        assertEquals(0.0, values["d"]!!, .0)
        assertNull(values["missing"])
    }

    @Test
    fun discoversEnergyDashboardPairsAndRespectsManualOverrides() {
        val prefs =
            EnergyPreferences.parse(
                JSONObject(
                    """{"energy_sources":[{"type":"gas","stat_energy_from":"sensor.gas"},{"type":"home","stat_rate":"sensor.home_power","stat_energy_to":"sensor.home_energy"}],"device_consumption":[{"stat_consumption":"sensor.desk_energy","name":"Work desk"}]}"""
                )
            )
        val states =
            listOf(
                    Entity("sensor.desk_power", "25", JSONObject().put("unit_of_measurement", "W")),
                    Entity(
                        "sensor.desk_energy",
                        "9999",
                        JSONObject().put("unit_of_measurement", "Wh"),
                    ),
                )
                .associateBy { it.id }
        val plan = energyPlan(Config(demo = false), states, prefs, HomeCatalog())
        assertEquals("sensor.home_energy", plan.homeEnergy)
        assertEquals("sensor.desk_power", plan.loads.single().powerId)
        assertEquals(setOf("sensor.home_energy", "sensor.desk_energy"), plan.statisticIds.toSet())
        val manual =
            energyPlan(
                Config(
                    demo = false,
                    energyMeter = "sensor.custom_home",
                    energyDevices = mapOf("sensor.desk_power" to "sensor.custom_desk"),
                ),
                states,
                prefs,
                HomeCatalog(),
            )
        assertEquals("sensor.custom_home", manual.homeEnergy)
        assertEquals("sensor.custom_desk", manual.loads.single().energyId)
    }

    @Test
    fun explicitPowerOnlyLoadsRemainVisibleAndNestedMetersAreNotDoubleCounted() {
        val states =
            mapOf(
                "sensor.load_power" to
                    Entity("sensor.load_power", "20", JSONObject().put("unit_of_measurement", "W"))
            )
        val manual =
            energyPlan(
                Config(demo = false, powerDevices = listOf("sensor.load_power")),
                states,
                EnergyPreferences(),
                HomeCatalog(),
            )
        assertEquals("sensor.load_power", manual.loads.single().powerId)
        val nested =
            EnergyPreferences(
                loads =
                    listOf(
                        EnergyLoad("sensor.parent_power", "sensor.parent_energy", "Parent"),
                        EnergyLoad(
                            "sensor.child_power",
                            "sensor.child_energy",
                            "Child",
                            "sensor.parent_energy",
                        ),
                    )
            )
        assertEquals(
            listOf("Parent"),
            energyPlan(Config(demo = false), emptyMap(), nested, HomeCatalog()).loads.map {
                it.name
            },
        )
    }
}
