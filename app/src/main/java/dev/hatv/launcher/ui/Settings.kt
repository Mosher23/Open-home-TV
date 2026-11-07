package dev.hatv.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme as InputTheme
import androidx.compose.material3.darkColorScheme as inputDarkColors
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hatv.launcher.LauncherViewModel
import dev.hatv.launcher.R
import dev.hatv.launcher.data.*
import dev.hatv.launcher.phone.PhoneSetupScreen

@Composable
fun SettingsPage(model: LauncherViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val saved by model.config.collectAsStateWithLifecycle()
    var c by remember(saved) { mutableStateOf(saved) }
    // Token is intentionally not saveable; never enters a saved-state Bundle or text log.
    var token by remember { mutableStateOf("") }
    var section by remember { mutableStateOf("main") }
    var filter by remember { mutableStateOf("") }
    val entities by model.entities.collectAsStateWithLifecycle()
    val installed by model.installed.collectAsStateWithLifecycle()
    if (section != "main") BackHandler { section = "main" }
    if (section == "phone") {
        PhoneSetupScreen(model) { section = "main" }
        return
    }
    InputTheme(colorScheme = inputDarkColors()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(6.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Label(stringResource(R.string.settings), size = 24, bold = true)
                    if (section != "main")
                        Action(stringResource(R.string.back), { section = "main" })
                }
            }
            when {
                section == "main" -> {
                    item {
                        Action(
                            stringResource(R.string.system_settings),
                            {
                                runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.provider.Settings.ACTION_SETTINGS
                                            )
                                        )
                                    }
                                    .onFailure { model.message.value = it.message }
                            },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Action(
                            stringResource(R.string.phone_setup),
                            { section = "phone" },
                            Modifier.fillMaxWidth(),
                            selected = true,
                        )
                        Label(
                            stringResource(R.string.phone_setup_hint),
                            Modifier.padding(top = 7.dp),
                            size = 12,
                            color = Muted,
                        )
                    }
                    item {
                        Field(
                            stringResource(R.string.server_url),
                            c.server,
                            { c = c.copy(server = it) },
                        )
                    }
                    item {
                        Field(
                            stringResource(R.string.access_token),
                            token,
                            { token = it },
                            secret = true,
                        )
                    }
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Action(
                                stringResource(R.string.test_connection),
                                { model.test(c.server, token) },
                            )
                            Action(
                                stringResource(R.string.save),
                                {
                                    model.save(c, token)
                                    token = ""
                                },
                            )
                            Action(
                                stringResource(R.string.remove_token),
                                {
                                    model.forgetToken()
                                    token = ""
                                },
                            )
                        }
                    }
                    item {
                        Toggle(stringResource(R.string.demo_mode), c.demo) {
                            c = c.copy(demo = !c.demo)
                        }
                    }
                    item {
                        Field(
                            stringResource(R.string.location),
                            c.location,
                            { c = c.copy(location = it) },
                        )
                    }
                    item {
                        Action(
                            stringResource(R.string.entities),
                            { section = "entities" },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Action(
                            stringResource(R.string.app_selection),
                            { section = "apps" },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Action(
                            stringResource(R.string.camera_endpoints),
                            { section = "cameras" },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    item { Label(stringResource(R.string.appearance), size = 18, bold = true) }
                    item {
                        Toggle(stringResource(R.string.animations), c.animate) {
                            c = c.copy(animate = !c.animate)
                        }
                    }
                    item {
                        Toggle(stringResource(R.string.streams), c.livePreviews) {
                            c = c.copy(livePreviews = !c.livePreviews)
                        }
                    }
                    item {
                        Toggle(stringResource(R.string.units), c.fahrenheit) {
                            c = c.copy(fahrenheit = !c.fahrenheit)
                        }
                    }
                    item {
                        Toggle(stringResource(R.string.compact), c.darkPanels) {
                            c = c.copy(darkPanels = !c.darkPanels)
                        }
                    }
                    item { Label(stringResource(R.string.widget_order), size = 13, color = Muted) }
                    items(c.widgets, key = { it }) { widget ->
                        Action(
                            "${c.widgets.indexOf(widget) + 1} · ${widgetTitle(widget)}",
                            {
                                c =
                                    c.copy(
                                        widgets =
                                            c.widgets.toMutableList().apply {
                                                val oldIndex = indexOf(widget)
                                                remove(widget)
                                                add(if (oldIndex < 3) 0 else 3, widget)
                                            }
                                    )
                            },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    item { Label(stringResource(R.string.language), size = 11, color = Muted) }
                    item {
                        Action(
                            stringResource(R.string.save),
                            {
                                model.save(c, token)
                                token = ""
                            },
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
                section == "entities" -> {
                    item { Label(stringResource(R.string.weather), size = 16, bold = true) }
                    item {
                        Action(
                            c.weather.ifBlank { "—" },
                            { section = "pick:weather" },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    item { Label(stringResource(R.string.energy), size = 16, bold = true) }
                    items(listOf("solar", "battery", "grid", "heat_pump", "ev", "household")) { role
                        ->
                        Action(
                            "${widgetTitle(role)} · ${c.energy[role] ?: "—"}",
                            { section = "pick:$role" },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Action(
                            stringResource(R.string.power_devices),
                            { section = "powerDevices" },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    item {
                        Label(
                            stringResource(R.string.auto_discovery_hint),
                            color = Muted,
                            size = 12,
                        )
                    }
                    item { Label(stringResource(R.string.devices), size = 16, bold = true) }
                    item { Field("Filter", filter, { filter = it }) }
                    items(
                        entities.values
                            .filter {
                                it.domain in
                                    listOf(
                                        "light",
                                        "switch",
                                        "climate",
                                        "scene",
                                        "cover",
                                        "camera",
                                    ) &&
                                    (it.id.contains(filter, true) || it.name.contains(filter, true))
                            }
                            .sortedBy { it.name },
                        key = { it.id },
                    ) { entity ->
                        val selected =
                            if (entity.domain == "camera")
                                c.cameras.any { it.entityId == entity.id }
                            else entity.id in c.controls
                        Action(
                            "${if (selected) "✓" else "+"} ${entity.name} · ${entity.id}",
                            {
                                c =
                                    if (entity.domain == "camera")
                                        c.copy(
                                            cameras =
                                                if (selected)
                                                    c.cameras.filterNot { it.entityId == entity.id }
                                                else c.cameras + CameraConfig(entity.id)
                                        )
                                    else
                                        c.copy(
                                            controls =
                                                if (selected) c.controls - entity.id
                                                else c.controls + entity.id
                                        )
                            },
                            Modifier.fillMaxWidth(),
                            selected,
                        )
                    }
                    if (entities.isEmpty())
                        item { Label(stringResource(R.string.no_entities), color = Muted) }
                }
                section == "powerDevices" -> {
                    item { Label(stringResource(R.string.power_devices), size = 20, bold = true) }
                    item { Field("Filter", filter, { filter = it }) }
                    items(
                        entities.values
                            .filter {
                                it.domain == "sensor" &&
                                    it.attributes.optString("unit_of_measurement") in
                                        listOf("W", "kW", "MW") &&
                                    (it.name.contains(filter, true) || it.id.contains(filter, true))
                            }
                            .sortedBy { it.name },
                        key = { it.id },
                    ) { entity ->
                        val selected = entity.id in c.powerDevices
                        Action(
                            "${if (selected) "✓" else "+"} ${entity.name}",
                            {
                                c =
                                    c.copy(
                                        powerDevices =
                                            if (selected) c.powerDevices - entity.id
                                            else c.powerDevices + entity.id
                                    )
                            },
                            Modifier.fillMaxWidth(),
                            selected = selected,
                        )
                    }
                }
                section.startsWith("pick:") -> {
                    val role = section.substringAfter(':')
                    item { Label(widgetTitle(role), size = 18) }
                    item { Field("Filter", filter, { filter = it }) }
                    item {
                        Action(
                            "—",
                            {
                                c =
                                    if (role == "weather") c.copy(weather = "")
                                    else c.copy(energy = c.energy - role)
                                section = "entities"
                            },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    items(
                        entities.values
                            .filter { it.domain == if (role == "weather") "weather" else "sensor" }
                            .filter {
                                it.id.contains(filter, true) || it.name.contains(filter, true)
                            }
                            .sortedBy { it.name },
                        key = { it.id },
                    ) { entity ->
                        Action(
                            "${entity.name} · ${entity.id} ${entity.attributes.optString("unit_of_measurement")}",
                            {
                                c =
                                    if (role == "weather") c.copy(weather = entity.id)
                                    else c.copy(energy = c.energy + (role to entity.id))
                                section = "entities"
                            },
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
                section == "apps" -> {
                    items(c.apps, key = { "selected:${it.id}" }) { shortcut ->
                        val index = c.apps.indexOf(shortcut)
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Label(
                                installed.find { it.id == shortcut.id }?.name ?: shortcut.id,
                                size = 15,
                            )
                            Field(
                                "Label",
                                shortcut.label,
                                { text ->
                                    c =
                                        c.copy(
                                            apps =
                                                c.apps.map {
                                                    if (it.id == shortcut.id) it.copy(label = text)
                                                    else it
                                                }
                                        )
                                },
                            )
                            Field(
                                "Icon text (optional)",
                                shortcut.icon,
                                { text ->
                                    c =
                                        c.copy(
                                            apps =
                                                c.apps.map {
                                                    if (it.id == shortcut.id)
                                                        it.copy(icon = text.take(3))
                                                    else it
                                                }
                                        )
                                },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Action(
                                    "↑",
                                    {
                                        val next = c.apps.toMutableList()
                                        if (index > 0) {
                                            java.util.Collections.swap(next, index, index - 1)
                                            c = c.copy(apps = next)
                                        }
                                    },
                                    enabled = index > 0,
                                )
                                Action(
                                    "↓",
                                    {
                                        val next = c.apps.toMutableList()
                                        if (index < next.lastIndex) {
                                            java.util.Collections.swap(next, index, index + 1)
                                            c = c.copy(apps = next)
                                        }
                                    },
                                    enabled = index < c.apps.lastIndex,
                                )
                                Action(
                                    "×",
                                    { c = c.copy(apps = c.apps.filterNot { it.id == shortcut.id }) },
                                )
                            }
                        }
                    }
                    items(
                        installed.filter { app -> c.apps.none { it.id == app.id } },
                        key = { it.id },
                    ) { app ->
                        Action(
                            "+ ${app.name}",
                            { c = c.copy(apps = c.apps + AppShortcut(app.id)) },
                            Modifier.fillMaxWidth(),
                        )
                    }
                }
                section == "cameras" -> {
                    items(c.cameras, key = { it.entityId }) { camera ->
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Label(entities[camera.entityId]?.name ?: camera.entityId, bold = true)
                            Field(
                                stringResource(R.string.webrtc_url),
                                camera.webRtc,
                                { text ->
                                    c =
                                        c.copy(
                                            cameras =
                                                c.cameras.map {
                                                    if (it.entityId == camera.entityId)
                                                        it.copy(webRtc = text)
                                                    else it
                                                }
                                        )
                                },
                            )
                            Field(
                                stringResource(R.string.hls_url),
                                camera.hls,
                                { text ->
                                    c =
                                        c.copy(
                                            cameras =
                                                c.cameras.map {
                                                    if (it.entityId == camera.entityId)
                                                        it.copy(hls = text)
                                                    else it
                                                }
                                        )
                                },
                            )
                        }
                    }
                    if (c.cameras.isEmpty())
                        item { Action(stringResource(R.string.entities), { section = "entities" }) }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, change: (String) -> Unit, secret: Boolean = false) {
    TvTextField(label, value, change, secret)
}

@Composable
private fun Toggle(label: String, selected: Boolean, action: () -> Unit) {
    Action("${if (selected) "✓" else "○"} $label", action, Modifier.fillMaxWidth(), selected)
}

@Composable
fun widgetTitle(key: String): String =
    stringResource(
        when (key) {
            "weather" -> R.string.weather
            "energy" -> R.string.energy
            "cameras" -> R.string.cameras
            "scenes" -> R.string.scenes
            "lights" -> R.string.lights
            "climate" -> R.string.climate
            "solar" -> R.string.solar
            "battery" -> R.string.battery
            "grid" -> R.string.grid
            "heat_pump" -> R.string.heat_pump
            "ev" -> R.string.ev
            "household" -> R.string.household
            else -> R.string.configuration
        }
    )
