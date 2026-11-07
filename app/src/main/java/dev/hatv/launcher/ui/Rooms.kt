package dev.hatv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hatv.launcher.LauncherViewModel
import dev.hatv.launcher.R
import dev.hatv.launcher.data.*
import org.json.JSONObject

@Composable
fun RoomsPage(model: LauncherViewModel, config: Config) {
    val states by model.entities.collectAsStateWithLifecycle()
    val catalog by model.catalog.collectAsStateWithLifecycle()
    val serverF by model.serverTemperatureF.collectAsStateWithLifecycle()
    val rooms = remember(states, catalog) { rooms(states, catalog) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item { Label(stringResource(R.string.climate), size = 26, bold = true) }
        items(rooms, key = { it.id }) { room ->
            PanelCard(
                room.name.ifBlank { stringResource(R.string.room_other) },
                Icons.Outlined.Home,
                Modifier.fillMaxWidth(),
                config.darkPanels,
            ) {
                val thermostat =
                    room.controls.firstOrNull { it.domain == "climate" && it.available }
                val metrics =
                    listOf("temperature", "humidity", "co2", "pm25").mapNotNull { key ->
                        val candidates = room.metrics.filter { climateMetric(it) == key }
                        val sensor =
                            candidates.firstOrNull {
                                it.available && it.state.toDoubleOrNull()?.isFinite() == true
                            } ?: candidates.firstOrNull()
                        if (sensor != null) key to sensor
                        else if (thermostat != null && key in listOf("temperature", "humidity")) {
                            val value =
                                thermostat.number(
                                    if (key == "temperature") "current_temperature"
                                    else "current_humidity"
                                )
                            value?.let {
                                key to
                                    Entity(
                                        thermostat.id,
                                        "$it",
                                        JSONObject()
                                            .put("friendly_name", thermostat.name)
                                            .put(
                                                "unit_of_measurement",
                                                if (key == "humidity") "%"
                                                else if (serverF && !config.demo) "°F" else "°C",
                                            ),
                                    )
                            }
                        } else null
                    }
                if (metrics.isNotEmpty())
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        metrics.forEach { (key, sensor) ->
                            Column(
                                Modifier.weight(1f)
                                    .background(Ink.copy(alpha = .45f), RoundedCornerShape(12.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                val value =
                                    sensor.state.toDoubleOrNull()?.takeIf {
                                        it.isFinite() && sensor.available
                                    }
                                Label(
                                    when (key) {
                                        "temperature" -> stringResource(R.string.room_temperature)
                                        "humidity" -> stringResource(R.string.room_humidity)
                                        "co2" -> "CO₂"
                                        else -> "PM2.5"
                                    },
                                    size = 12,
                                    color = Muted,
                                )
                                Label(
                                    if (value == null) stringResource(R.string.unavailable)
                                    else if (key == "temperature")
                                        formatTemperature(
                                            value,
                                            sensor.attributes.optString("unit_of_measurement") ==
                                                "°F",
                                            config.fahrenheit,
                                        )
                                    else
                                        String.format(
                                            java.util.Locale.getDefault(),
                                            "%.0f %s",
                                            value,
                                            sensor.attributes.optString("unit_of_measurement"),
                                        ),
                                    size = 24,
                                    bold = true,
                                )
                                Label(sensor.name, size = 10, color = Muted)
                            }
                        }
                    }
                room.controls.forEach { entity ->
                    Column(
                        Modifier.fillMaxWidth().padding(top = 5.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp),
                        ) {
                            Icon(
                                if (entity.domain == "cover") Icons.Outlined.Blinds
                                else Icons.Outlined.Thermostat,
                                null,
                                Modifier.size(20.dp),
                                tint = Accent,
                            )
                            Label(entity.name, bold = true)
                            Label(
                                if (entity.available) entity.state
                                else stringResource(R.string.unavailable),
                                size = 11,
                                color = Muted,
                            )
                        }
                        if (entity.domain == "cover") {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                listOf(
                                        1 to (R.string.open_cover to "open_cover"),
                                        8 to (R.string.stop_cover to "stop_cover"),
                                        2 to (R.string.close_cover to "close_cover"),
                                    )
                                    .forEach { (flag, pair) ->
                                        if (
                                            entity.attributes.optInt("supported_features", 11) and
                                                flag != 0
                                        )
                                            Action(
                                                stringResource(pair.first),
                                                { model.service(entity, pair.second) },
                                                enabled = entity.available,
                                            )
                                    }
                                entity.number("current_position")?.let {
                                    Label("${it.toInt()} %", color = Muted)
                                }
                            }
                        } else {
                            val target = entity.number("temperature")
                            if (target != null)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Action(
                                        "−",
                                        { adjustClimate(model, entity, -1) },
                                        enabled = entity.available,
                                    )
                                    Label(
                                        formatTemperature(
                                            target,
                                            serverF && !config.demo,
                                            config.fahrenheit,
                                        ),
                                        size = 22,
                                        bold = true,
                                    )
                                    Action(
                                        "+",
                                        { adjustClimate(model, entity, 1) },
                                        enabled = entity.available,
                                    )
                                }
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(3.dp),
                            ) {
                                items(
                                    entity.attributes
                                        .optJSONArray("hvac_modes")
                                        ?.strings()
                                        .orEmpty()
                                ) { mode ->
                                    Action(
                                        mode,
                                        {
                                            model.service(
                                                entity,
                                                "set_hvac_mode",
                                                JSONObject().put("hvac_mode", mode),
                                            )
                                        },
                                        selected = entity.state == mode,
                                        enabled = entity.available,
                                    )
                                }
                                items(
                                    entity.attributes
                                        .optJSONArray("preset_modes")
                                        ?.strings()
                                        .orEmpty()
                                ) { preset ->
                                    PresetChip(
                                        preset = preset,
                                        action = {
                                            model.service(
                                                entity,
                                                "set_preset_mode",
                                                JSONObject().put("preset_mode", preset),
                                            )
                                        },
                                        selected =
                                            entity.attributes.optString("preset_mode") == preset,
                                        enabled = entity.available,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (rooms.isEmpty()) item { Label(stringResource(R.string.no_rooms), color = Muted) }
    }
}
