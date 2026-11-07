package dev.hatv.launcher.data

import java.time.*
import org.json.JSONArray
import org.json.JSONObject

data class AreaInfo(
    val id: String,
    val name: String,
    val temperature: String = "",
    val humidity: String = "",
)

data class EntityInfo(
    val area: String = "",
    val device: String = "",
    val category: String = "",
    val hidden: Boolean = false,
    val disabled: Boolean = false,
)

data class HomeCatalog(
    val areas: Map<String, AreaInfo> = emptyMap(),
    val entities: Map<String, EntityInfo> = emptyMap(),
    val deviceAreas: Map<String, String> = emptyMap(),
) {
    fun areaId(e: Entity): String =
        entities[e.id]
            ?.let { it.area.ifBlank { deviceAreas[it.device].orEmpty() } }
            .orEmpty()
            .ifBlank { e.attributes.optString("area_id") }

    fun roomName(e: Entity): String =
        areas[areaId(e)]?.name.orEmpty().ifBlank { e.name.substringBefore(" · ", "") }

    fun visible(e: Entity): Boolean =
        entities[e.id]?.let { !it.hidden && !it.disabled && it.category.isBlank() } ?: true
}

data class Room(
    val id: String,
    val name: String,
    val metrics: List<Entity>,
    val controls: List<Entity>,
)

fun climateMetric(e: Entity): String? {
    if (e.domain != "sensor") return null
    return when (e.attributes.optString("device_class")) {
        "temperature" ->
            if (Regex("(relay|plug|switch|cpu|device)_temperature").containsMatchIn(e.id)) null
            else "temperature"
        "humidity" -> "humidity"
        "carbon_dioxide" -> "co2"
        "pm25" -> "pm25"
        else -> null
    }
}

fun rooms(states: Map<String, Entity>, catalog: HomeCatalog): List<Room> =
    states.values
        .filter {
            catalog.visible(it) &&
                (climateMetric(it) != null || it.domain in listOf("climate", "cover"))
        }
        .groupBy { catalog.areaId(it).ifBlank { catalog.roomName(it) }.ifBlank { "__other" } }
        .map { (id, entities) ->
            val area = catalog.areas[id]
            Room(
                id,
                area?.name ?: catalog.roomName(entities.first()),
                entities
                    .filter { climateMetric(it) != null }
                    .sortedWith(
                        compareBy<Entity> {
                                if (it.id == area?.temperature || it.id == area?.humidity) 0 else 1
                            }
                            .thenBy { it.name }
                    ),
                entities.filter { it.domain in listOf("climate", "cover") }.sortedBy { it.name },
            )
        }
        .sortedWith(compareBy<Room> { it.id == "__other" }.thenBy { it.name })

data class CalendarEvent(
    val calendarId: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val location: String = "",
)

fun parseCalendarEvents(id: String, array: JSONArray, zone: ZoneId): List<CalendarEvent> =
    (0 until array.length())
        .mapNotNull { i ->
            runCatching {
                    val e = array.getJSONObject(i)
                    fun date(key: String): String =
                        when (val d = e.get(key)) {
                            is JSONObject -> d.optString("dateTime").ifBlank { d.getString("date") }
                            else -> d.toString()
                        }
                    val startText = date("start")
                    val allDay = startText.length == 10
                    fun instant(text: String): Instant =
                        if (text.length == 10) LocalDate.parse(text).atStartOfDay(zone).toInstant()
                        else
                            runCatching { OffsetDateTime.parse(text).toInstant() }
                                .getOrElse { LocalDateTime.parse(text).atZone(zone).toInstant() }
                    CalendarEvent(
                        id,
                        e.optString("summary", "—"),
                        instant(startText),
                        instant(date("end")),
                        allDay,
                        e.optString("location"),
                    )
                }
                .getOrNull()
        }
        .sortedBy { it.start }
