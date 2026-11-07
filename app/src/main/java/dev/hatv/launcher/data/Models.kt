package dev.hatv.launcher.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

data class Entity(val id: String, val state: String, val attributes: JSONObject = JSONObject()) {
    val name: String
        get() = attributes.optString("friendly_name", id.substringAfter('.').replace('_', ' '))

    val domain: String
        get() = id.substringBefore('.')

    val available: Boolean
        get() = state != "unavailable" && (domain == "scene" || state != "unknown")

    fun number(key: String): Double? = attributes.opt(key)?.toString()?.toDoubleOrNull()

    companion object {
        fun parse(json: JSONObject) =
            Entity(
                json.getString("entity_id"),
                json.getString("state"),
                json.optJSONObject("attributes") ?: JSONObject(),
            )
    }
}

data class Forecast(val time: String, val temperature: Double?, val condition: String)

data class CameraConfig(val entityId: String, val hls: String = "", val webRtc: String = "")

data class AppShortcut(val id: String, val label: String = "", val icon: String = "")

data class Config(
    val server: String = "",
    val demo: Boolean = true,
    val location: String = "Duisburg",
    val weather: String = "",
    val controls: List<String> = emptyList(),
    val energy: Map<String, String> = emptyMap(),
    val powerDevices: List<String> = emptyList(),
    val energyMeter: String = "",
    val energyDevices: Map<String, String> = emptyMap(),
    val cameras: List<CameraConfig> = emptyList(),
    val apps: List<AppShortcut> = emptyList(),
    val animate: Boolean = true,
    val livePreviews: Boolean = true,
    val homeCameraCount: Int = 2,
    val fahrenheit: Boolean = false,
    val darkPanels: Boolean = false,
    val widgets: List<String> = listOf("weather", "energy", "cameras", "scenes", "lights"),
)

const val MAX_HOME_CAMERAS = 4

fun Config.selectedCameras(): List<CameraConfig> =
    if (demo && cameras.isEmpty()) Demo.cameras else cameras

fun Config.homeCameras(): List<CameraConfig> =
    selectedCameras().distinctBy { it.entityId }.take(homeCameraCount.coerceIn(0, MAX_HOME_CAMERAS))

fun normalizeServer(input: String): String {
    val url = input.trim().toHttpUrl()
    require(
        url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.query == null &&
            url.fragment == null
    ) {
        "Use a server URL without credentials, query or fragment"
    }
    return url.toString().trimEnd('/')
}

fun normalizeCameraEndpoint(input: String): String {
    if (input.isBlank()) return ""
    val url = input.trim().toHttpUrl()
    require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) {
        "Camera URLs must not include credentials or fragments"
    }
    return url.toString()
}

fun powerKw(entity: Entity?): Double? {
    if (entity == null || !entity.available) return null
    val value = entity.state.toDoubleOrNull()?.takeIf { it.isFinite() } ?: return null
    return when (entity.attributes.optString("unit_of_measurement")) {
        "W" -> value / 1000
        "kW" -> value
        "MW" -> value * 1000
        else -> null
    }
}

fun temperature(value: Double?, sourceF: Boolean, targetF: Boolean): Double? =
    value?.let {
        if (sourceF == targetF) it else if (targetF) it * 9 / 5 + 32 else (it - 32) * 5 / 9
    }

fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

object Demo {
    val cameras = listOf("harbor", "rhine", "garden", "entry").map { CameraConfig("camera.$it") }

    val entities: Map<String, Entity> =
        listOf(
                Entity(
                    "weather.home",
                    "partlycloudy",
                    JSONObject()
                        .put("friendly_name", "Duisburg")
                        .put("temperature", 12)
                        .put("temperature_unit", "°C")
                        .put("humidity", 72)
                        .put("wind_speed", 15),
                ),
                Entity(
                    "light.living_room",
                    "on",
                    JSONObject().put("friendly_name", "Wohnzimmer").put("brightness", 179),
                ),
                Entity(
                    "light.kitchen",
                    "on",
                    JSONObject().put("friendly_name", "Küche").put("brightness", 128),
                ),
                Entity("light.dining", "off", JSONObject().put("friendly_name", "Esstisch")),
                Entity("light.garden", "on", JSONObject().put("friendly_name", "Garten")),
                Entity(
                    "climate.living_room",
                    "heat",
                    JSONObject()
                        .put("friendly_name", "Living Room · Thermostat")
                        .put("area_id", "living_room")
                        .put("current_temperature", 21.2)
                        .put("current_humidity", 47)
                        .put("hvac_modes", JSONArray(listOf("off", "heat", "auto")))
                        .put(
                            "preset_modes",
                            JSONArray(listOf("home", "away", "eco", "sleep", "comfort", "boost")),
                        )
                        .put("preset_mode", "comfort")
                        .put("temperature", 21.5)
                        .put("min_temp", 7)
                        .put("max_temp", 30)
                        .put("target_temp_step", 0.5),
                ),
                Entity(
                    "sensor.living_room_co2",
                    "630",
                    JSONObject()
                        .put("friendly_name", "Living Room · Air quality")
                        .put("area_id", "living_room")
                        .put("device_class", "carbon_dioxide")
                        .put("unit_of_measurement", "ppm"),
                ),
                Entity(
                    "sensor.living_room_pm25",
                    "4",
                    JSONObject()
                        .put("friendly_name", "Living Room · Air quality")
                        .put("area_id", "living_room")
                        .put("device_class", "pm25")
                        .put("unit_of_measurement", "µg/m³"),
                ),
                Entity(
                    "cover.living_room",
                    "open",
                    JSONObject()
                        .put("friendly_name", "Living Room · Shutters")
                        .put("area_id", "living_room")
                        .put("supported_features", 15)
                        .put("current_position", 100),
                ),
                Entity("scene.home", "scening", JSONObject().put("friendly_name", "Zu Hause")),
                Entity("scene.evening", "scening", JSONObject().put("friendly_name", "Abend")),
                Entity("scene.movie", "scening", JSONObject().put("friendly_name", "Film")),
                Entity(
                    "scene.energy",
                    "scening",
                    JSONObject().put("friendly_name", "Energie sparen"),
                ),
                Entity(
                    "camera.harbor",
                    "idle",
                    JSONObject().put("friendly_name", "Duisburg · Innenhafen"),
                ),
                Entity("camera.garden", "idle", JSONObject().put("friendly_name", "Garden")),
                Entity("camera.entry", "idle", JSONObject().put("friendly_name", "Entrance")),
                Entity(
                    "camera.rhine",
                    "idle",
                    JSONObject().put("friendly_name", "Rhein · Duisburg"),
                ),
            )
            .associateBy { it.id } +
            listOf(
                    "solar" to 3.8,
                    "battery" to 1.2,
                    "grid" to 0.0,
                    "heat_pump" to 2.1,
                    "ev" to 1.6,
                    "household" to 5.0,
                )
                .associate { (key, value) ->
                    "sensor.$key" to
                        Entity(
                            "sensor.$key",
                            "$value",
                            JSONObject().put("unit_of_measurement", "kW"),
                        )
                }
    val hourly =
        (0..5).map {
            Forecast(
                "${16 + it}:00",
                (12 - it.coerceAtMost(4)).toDouble(),
                if (it == 0) "partlycloudy" else "cloudy",
            )
        }
    val daily = (0..5).map { Forecast("+${it + 1}d", (12 + it % 3).toDouble(), "partlycloudy") }
}
