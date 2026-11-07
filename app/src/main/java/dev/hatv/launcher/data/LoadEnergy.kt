package dev.hatv.launcher.data

import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

data class EnergyLoad(
    val powerId: String = "",
    val energyId: String = "",
    val name: String = "",
    val includedIn: String = "",
) {
    val id
        get() = energyId.ifBlank { powerId }
}

data class EnergyPreferences(
    val homePower: String = "",
    val homeEnergy: String = "",
    val loads: List<EnergyLoad> = emptyList(),
) {
    companion object {
        fun parse(json: JSONObject): EnergyPreferences {
            val sources = json.optJSONArray("energy_sources") ?: JSONArray()
            val home =
                (0 until sources.length())
                    .map { sources.getJSONObject(it) }
                    .firstOrNull { it.optString("type") == "home" }
            val devices = json.optJSONArray("device_consumption") ?: JSONArray()
            fun JSONObject.text(key: String) = if (isNull(key)) "" else optString(key)
            return EnergyPreferences(
                home?.text("stat_rate").orEmpty(),
                home?.text("stat_energy_to").orEmpty(),
                (0 until devices.length())
                    .map {
                        devices.getJSONObject(it).let { d ->
                            EnergyLoad(
                                d.text("stat_rate"),
                                d.text("stat_consumption"),
                                d.text("name"),
                                d.text("included_in_stat"),
                            )
                        }
                    }
                    .filter { it.id.isNotBlank() }
                    .distinctBy { it.id }
                    .take(40),
            )
        }
    }
}

data class EnergyPlan(val homePower: String, val homeEnergy: String, val loads: List<EnergyLoad>) {
    val statisticIds
        get() =
            (listOf(homeEnergy) + loads.map { it.energyId })
                .filter { it.isNotBlank() }
                .distinct()
                .take(41)
}

data class TodayEnergy(
    val day: LocalDate? = null,
    val values: Map<String, Double?> = emptyMap(),
    val updated: java.time.Instant? = null,
    val failed: Boolean = false,
)

fun isEnergySensor(e: Entity) =
    e.domain == "sensor" &&
        e.attributes.optString("unit_of_measurement") in listOf("Wh", "kWh", "MWh")

fun energyPlan(
    c: Config,
    states: Map<String, Entity>,
    prefs: EnergyPreferences,
    catalog: HomeCatalog,
): EnergyPlan {
    fun powerSensor(id: String) =
        states[id]?.let {
            it.domain == "sensor" &&
                it.attributes.optString("unit_of_measurement") in listOf("W", "kW", "MW")
        } == true
    fun powerFor(energyId: String): String {
        val guess =
            listOf(
                energyId.removeSuffix("_consumed_energy") + "_power",
                energyId.removeSuffix("_energy") + "_power",
            )
        guess.firstOrNull(::powerSensor)?.let {
            return it
        }
        val device = catalog.entities[energyId]?.device?.takeIf { it.isNotBlank() } ?: return ""
        return states.values
            .filter {
                catalog.entities[it.id]?.device == device &&
                    powerSensor(it.id) &&
                    catalog.visible(it)
            }
            .singleOrNull()
            ?.id
            .orEmpty()
    }
    fun energyFor(powerId: String): String {
        c.energyDevices[powerId]
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }
        prefs.loads
            .firstOrNull { it.powerId == powerId }
            ?.energyId
            ?.takeIf { it.isNotBlank() }
            ?.let {
                return it
            }
        val guess = powerId.removeSuffix("_power") + "_energy"
        if (states[guess]?.let(::isEnergySensor) == true) return guess
        val device = catalog.entities[powerId]?.device?.takeIf { it.isNotBlank() } ?: return ""
        return states.values
            .filter {
                catalog.entities[it.id]?.device == device &&
                    isEnergySensor(it) &&
                    catalog.visible(it)
            }
            .singleOrNull()
            ?.id
            .orEmpty()
    }
    val homePower = c.energy["household"].orEmpty().ifBlank { prefs.homePower }
    val homeEnergy =
        c.energyMeter.ifBlank {
            if (c.energy["household"].orEmpty().isNotBlank() && homePower != prefs.homePower)
                energyFor(homePower)
            else prefs.homeEnergy.ifBlank { energyFor(homePower) }
        }
    val explicit = c.powerDevices + listOfNotNull(c.energy["heat_pump"], c.energy["ev"])
    val source =
        if (c.powerDevices.isNotEmpty())
            explicit.distinct().map { id ->
                val existing = prefs.loads.firstOrNull { it.powerId == id }
                EnergyLoad(
                    id,
                    energyFor(id),
                    existing?.name.orEmpty(),
                    existing?.includedIn.orEmpty(),
                )
            }
        else
            prefs.loads.map {
                val power = it.powerId.ifBlank { powerFor(it.energyId) }
                it.copy(
                    powerId = power,
                    energyId = c.energyDevices[power].orEmpty().ifBlank { it.energyId },
                )
            } + explicit.map { EnergyLoad(it, energyFor(it)) }
    val energyIds = source.map { it.energyId }.filter { it.isNotBlank() }.toSet()
    val loads =
        source
            .filter {
                (homePower.isBlank() || it.powerId != homePower) &&
                    (homeEnergy.isBlank() || it.energyId != homeEnergy) &&
                    it.includedIn !in energyIds
            }
            .map { load ->
                val e = states[load.powerId] ?: states[load.energyId]
                val baseName = load.name.ifBlank { e?.name ?: load.id }
                val room = e?.let { catalog.roomName(it) }.orEmpty()
                load.copy(
                    name =
                        if (room.isNotBlank() && !baseName.startsWith(room, true))
                            "$room · $baseName"
                        else baseName
                )
            }
            .distinctBy { it.id }
            .take(40)
    return EnergyPlan(homePower, homeEnergy, loads)
}

/** Recorder changes are requested in kWh; raw cumulative sensor states are never daily totals. */
fun todayChanges(result: JSONObject, ids: List<String>): Map<String, Double?> =
    ids.associateWith { id ->
        val rows = result.optJSONArray(id)
        val changes =
            if (rows == null) emptyList()
            else
                (0 until rows.length()).mapNotNull {
                    rows.optJSONObject(it)?.opt("change")?.toString()?.toDoubleOrNull()?.takeIf { n
                        ->
                        n.isFinite()
                    }
                }
        changes.takeIf { it.isNotEmpty() }?.sum()?.takeIf { it >= 0 && it.isFinite() }
    }

object DemoLoads {
    private val samples =
        listOf(
            Triple("dining", "Living room · Dining light", 66.0 to .4),
            Triple("ceiling", "Living room · Ceiling light", 46.0 to .1),
            Triple("hallway", "Hallway · Ceiling light", 31.0 to .07),
            Triple("desk", "Office · Work desk", 24.0 to .3),
            Triple("server", "Unraid server", 23.0 to .4),
            Triple("kitchen", "Kitchen · Ceiling light", 20.0 to .1),
            Triple("ha_server", "Home Assistant server", 12.0 to .2),
            Triple("washer", "Washing machine", 0.0 to 1.8),
            Triple("media", "Living room · Media system", 0.0 to .5),
            Triple("dryer", "Dryer", 0.0 to .4),
            Triple("fridge", "Refrigerator", 0.0 to .4),
            Triple("coffee", "Coffee machine", 0.0 to .4),
            Triple("bedroom", "Bedroom · Ceiling light", 0.0 to .03),
        )
    val preferences =
        EnergyPreferences(
            "sensor.demo_home_power",
            "sensor.demo_home_energy",
            samples.map {
                EnergyLoad(
                    "sensor.demo_${it.first}_power",
                    "sensor.demo_${it.first}_energy",
                    it.second,
                )
            },
        )
    val states =
        samples.associate {
            "sensor.demo_${it.first}_power" to
                Entity(
                    "sensor.demo_${it.first}_power",
                    "${it.third.first}",
                    JSONObject().put("unit_of_measurement", "W").put("friendly_name", it.second),
                )
        } +
            ("sensor.demo_home_power" to
                Entity(
                    "sensor.demo_home_power",
                    "957",
                    JSONObject().put("unit_of_measurement", "W"),
                ))
    val today =
        samples.associate { "sensor.demo_${it.first}_energy" to it.third.second } +
            ("sensor.demo_home_energy" to (samples.sumOf { it.third.second } + 3.6))
}
