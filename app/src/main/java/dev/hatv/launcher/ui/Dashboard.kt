package dev.hatv.launcher.ui

import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import dev.hatv.launcher.LauncherViewModel
import dev.hatv.launcher.R
import dev.hatv.launcher.camera.CameraPlayer
import dev.hatv.launcher.data.*
import dev.hatv.launcher.ha.HomeAssistantClient
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import org.json.JSONObject

@Composable
fun Launcher(model: LauncherViewModel) {
    val config by model.config.collectAsStateWithLifecycle()
    val entities by model.dashboardEntities.collectAsStateWithLifecycle()
    val message by model.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var page by rememberSaveable { mutableStateOf("home") }
    var fullscreen by remember { mutableStateOf<CameraConfig?>(null) }
    val homeFocus = remember { FocusRequester() }
    val cameraBackFocus = remember { FocusRequester() }
    var homeReady by remember { mutableStateOf(false) }
    var cameraFocused by remember(fullscreen) { mutableStateOf(false) }
    val menu =
        listOf(
            "home" to R.string.home,
            "apps" to R.string.apps,
            "scenes" to R.string.scenes,
            "devices" to R.string.devices,
            "cameras" to R.string.cameras,
            "climate" to R.string.climate,
            "calendar" to R.string.calendar,
            "settings" to R.string.settings,
        )
    val icons =
        listOf(
            Icons.Outlined.Home,
            Icons.Outlined.Apps,
            Icons.Outlined.PlayCircle,
            Icons.Outlined.Devices,
            Icons.Outlined.Videocam,
            Icons.Outlined.Thermostat,
            Icons.Outlined.CalendarMonth,
            Icons.Outlined.Settings,
        )
    BackHandler {
        if (fullscreen != null) fullscreen = null else page = "home"
        homeFocus.requestFocus()
    }

    val homeRequest by model.homeRequest.collectAsStateWithLifecycle()
    LaunchedEffect(homeRequest, homeReady) {
        if (homeReady) {
            if (homeRequest > 0) {
                fullscreen = null
                page = "home"
            }
            homeFocus.requestFocus()
        }
    }
    LaunchedEffect(message) {
        if (message != null) {
            delay(4500)
            model.message.value = null
        }
    }
    MaterialTheme(colorScheme = darkColorScheme()) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Ink)) {
            // Fixed logical canvas scales evenly to 1080p and 4K without rendering a 4K bitmap.
            val scale = constraints.maxWidth / 1000f
            CompositionLocalProvider(LocalDensity provides Density(scale, fontScale = 1f)) {
                Backdrop(Modifier.fillMaxSize())
                Column(
                    Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(model, config, entities)
                    Row(
                        Modifier.fillMaxWidth().weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Column(
                            Modifier.width(52.dp).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            menu.forEachIndexed { i, (route, label) ->
                                Tile(
                                    { page = route },
                                    Modifier.fillMaxWidth()
                                        .semantics { contentDescription = context.getString(label) }
                                        .height(42.dp)
                                        .then(
                                            if (i == 0)
                                                Modifier.focusRequester(homeFocus)
                                                    .onGloballyPositioned { homeReady = true }
                                            else Modifier
                                        ),
                                    selected = page == route,
                                    color = Color.Transparent,
                                ) {
                                    Icon(
                                        icons[i],
                                        null,
                                        Modifier.size(23.dp),
                                        tint = if (page == route) Color.White else Muted,
                                    )
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            Label(
                                dev.hatv.launcher.BuildConfig.VERSION_NAME,
                                size = 8,
                                color = Muted,
                            )
                        }
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            if (fullscreen == null)
                                when (page) {
                                    "home" ->
                                        HomeDashboard(
                                            model,
                                            config,
                                            entities,
                                            { page = "apps" },
                                            { fullscreen = it },
                                            { page = "power" },
                                        )
                                    "power" -> PowerPage(config, entities)
                                    "apps" -> AppsPage(model, config)
                                    "scenes",
                                    "devices" ->
                                        DevicePage(model, config, entities, page == "scenes")
                                    "cameras" ->
                                        CamerasPage(model, config, entities, { fullscreen = it })
                                    "climate" -> RoomsPage(model, config)
                                    "calendar" -> CalendarPage(model, config)
                                    "settings" -> SettingsPage(model)
                                }
                        }
                    }
                }
                if (fullscreen != null)
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        if (!config.demo) CameraPlayer(fullscreen!!, model, Modifier.fillMaxSize())
                        else
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Label(stringResource(R.string.camera_demo), size = 24)
                            }
                        Action(
                            stringResource(R.string.back),
                            {
                                fullscreen = null
                                homeFocus.requestFocus()
                            },
                            Modifier.align(Alignment.TopStart)
                                .padding(20.dp)
                                .focusRequester(cameraBackFocus)
                                .onGloballyPositioned {
                                    if (!cameraFocused) {
                                        cameraFocused = true
                                        cameraBackFocus.requestFocus()
                                    }
                                },
                        )
                    }
                message?.let {
                    Box(
                        Modifier.align(Alignment.BottomCenter)
                            .padding(12.dp)
                            .background(Color(0xFF18374A), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Label(it, size = 13)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(model: LauncherViewModel, c: Config, entities: Map<String, Entity>) {
    var clock by remember { mutableStateOf(ZonedDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            clock = ZonedDateTime.now()
            delay(1000L * (60 - clock.second))
        }
    }
    val status by model.ha.status.collectAsStateWithLifecycle()
    val weather = entities[if (c.demo) "weather.home" else c.weather]
    Row(Modifier.fillMaxWidth().height(61.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Home, null, Modifier.size(42.dp), tint = Accent)
        Spacer(Modifier.width(12.dp))
        Column {
            Label("Open home TV", size = 22, bold = true)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(
                    "●",
                    size = 10,
                    color =
                        if (status == HomeAssistantClient.Status.ONLINE) Green
                        else if (c.demo) Accent else Color(0xFFF3B45D),
                )
                Label(
                    if (c.demo) stringResource(R.string.demo)
                    else if (status == HomeAssistantClient.Status.ONLINE)
                        stringResource(R.string.online)
                    else if (status == HomeAssistantClient.Status.AUTH_ERROR)
                        "Authentication failed"
                    else stringResource(R.string.offline),
                    size = 10,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Label(clock.format(DateTimeFormatter.ofPattern("HH:mm")), size = 34)
            Label(
                clock.format(DateTimeFormatter.ofPattern("EEE, d. MMM yyyy", Locale.getDefault())),
                size = 10,
            )
        }
        Spacer(Modifier.width(22.dp))
        Icon(Icons.Outlined.Cloud, null, Modifier.size(30.dp), tint = Color(0xFFF2D386))
        Spacer(Modifier.width(10.dp))
        Column {
            Label(
                formatTemperature(
                    weather?.number("temperature"),
                    weather?.attributes?.optString("temperature_unit") == "°F",
                    c.fahrenheit,
                ),
                size = 23,
            )
            Label(c.location, size = 11, color = Muted)
        }
    }
}

@Composable
private fun HomeDashboard(
    model: LauncherViewModel,
    c: Config,
    entities: Map<String, Entity>,
    allApps: () -> Unit,
    openCamera: (CameraConfig) -> Unit,
    openPower: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // First three widget slots are large; last three compact. Order is persisted by Settings.
        Row(
            Modifier.weight(1.6f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            c.widgets.take(3).forEach { key ->
                Widget(
                    key,
                    model,
                    c,
                    entities,
                    Modifier.weight(
                            if (key == "energy") 1.45f else if (key == "cameras") .85f else 1.05f
                        )
                        .fillMaxHeight(),
                    openCamera,
                    openPower,
                )
            }
        }
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            c.widgets.drop(3).forEach { key ->
                Widget(
                    key,
                    model,
                    c,
                    entities,
                    Modifier.weight(if (key == "climate") .85f else 1.3f).fillMaxHeight(),
                    openCamera,
                    openPower,
                )
            }
        }
        AppRow(model, c, allApps)
    }
}

@Composable
private fun Widget(
    key: String,
    model: LauncherViewModel,
    c: Config,
    entities: Map<String, Entity>,
    modifier: Modifier,
    openCamera: (CameraConfig) -> Unit,
    openPower: () -> Unit = {},
) {
    val selected = if (c.demo) entities.values.toList() else c.controls.mapNotNull { entities[it] }
    when (key) {
        "energy" -> EnergyCard(c, entities, modifier, openPower)
        "weather" -> WeatherCard(model, c, entities, modifier)
        "cameras" -> CameraCard(model, c, entities, modifier, openCamera)
        "scenes" ->
            ControlsCard(
                stringResource(R.string.scenes),
                Icons.Outlined.PlayCircle,
                entities.values.filter { it.domain == "scene" }.sortedBy { it.name },
                model,
                modifier,
                c,
            )
        "lights" ->
            ControlsCard(
                stringResource(R.string.lights),
                Icons.Outlined.Lightbulb,
                selected.filter { it.domain in listOf("light", "switch", "cover") },
                model,
                modifier,
                c,
            )
        "climate" ->
            ClimateCard(
                selected.firstOrNull { it.domain == "climate" }
                    ?: entities.values.firstOrNull { it.domain == "climate" },
                model,
                modifier,
                c,
            )
    }
}

@Composable
fun AppRow(model: LauncherViewModel, c: Config, allApps: () -> Unit) {
    val installed by model.installed.collectAsStateWithLifecycle()
    LazyRow(
        Modifier.fillMaxWidth().height(88.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(5.dp),
    ) {
        if (c.demo && c.apps.isEmpty())
            items(listOf("YouTube", "Disney+", "Apple TV", "arte", "ARD", "ZDF")) { name ->
                AppBanner(null, name, "", Modifier.width(130.dp).fillMaxHeight()) {
                    model.message.value = "Demo app · choose installed apps in Settings"
                }
            }
        else
            items(c.apps, key = { it.id }) { shortcut ->
                val app = installed.find { it.id == shortcut.id }
                AppBanner(
                    app,
                    shortcut.label.ifBlank { app?.name ?: shortcut.id.substringAfterLast('/') },
                    shortcut.icon,
                    Modifier.width(130.dp).fillMaxHeight(),
                ) {
                    model.launchApp(shortcut.id)
                }
            }
        item {
            Tile(allApps, Modifier.width(95.dp).fillMaxHeight(), color = Color(0xCC293344)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(Icons.Outlined.Apps, null, Modifier.size(26.dp), tint = Color.White)
                    Label(stringResource(R.string.all_apps), size = 12)
                }
            }
        }
    }
}

@Composable
private fun AppBanner(
    app: InstalledApp?,
    title: String,
    initials: String,
    modifier: Modifier,
    launch: () -> Unit,
) {
    val banner = remember(app?.id) { app?.banner?.toBitmap(320, 180)?.asImageBitmap() }
    val icon = remember(app?.id) { app?.icon?.toBitmap(96, 96)?.asImageBitmap() }
    val colors = listOf(Color(0xFF233E65), Color(0xFF35314F), Color(0xFF1F4C50), Color(0xFF343F54))
    Tile(
        launch,
        modifier.semantics { contentDescription = title },
        color = colors[(title.hashCode() and Int.MAX_VALUE) % colors.size],
        focusedScale = 1.08f,
        focusColor = Color.White,
    ) {
        if (banner != null && initials.isBlank())
            Image(banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else
            Column(
                Modifier.fillMaxSize().padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (initials.isNotBlank()) Label(initials.take(3), size = 28, bold = true)
                else if (icon != null) Image(icon, null, Modifier.size(42.dp))
                Label(
                    title,
                    Modifier.padding(top = 5.dp),
                    size = if (icon == null) 18 else 11,
                    bold = true,
                )
            }
    }
}

@Composable
private fun WeatherCard(
    model: LauncherViewModel,
    c: Config,
    entities: Map<String, Entity>,
    modifier: Modifier,
) {
    val weather = entities[if (c.demo) "weather.home" else c.weather]
    val hourly by model.hourly.collectAsStateWithLifecycle()
    val daily by model.daily.collectAsStateWithLifecycle()
    var showDaily by remember { mutableStateOf(false) }
    PanelCard(c.location, Icons.Outlined.Cloud, modifier, c.darkPanels) {
        Label(weatherCondition(weather?.state), size = 10, color = Muted)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.WbCloudy, null, Modifier.size(35.dp), tint = Color(0xFFF7D56D))
            Label(
                formatTemperature(
                    weather?.number("temperature"),
                    weather?.attributes?.optString("temperature_unit") == "°F",
                    c.fahrenheit,
                ),
                size = 30,
                bold = true,
            )
            Column {
                Label("${weather?.number("humidity")?.toInt() ?: "—"} %", size = 9, color = Muted)
                Label(
                    "${weather?.number("wind_speed") ?: "—"} ${weather?.attributes?.optString("wind_speed_unit", "km/h") ?: ""}",
                    size = 9,
                    color = Muted,
                )
            }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            modifier = Modifier.weight(1f),
        ) {
            items((if (showDaily) daily else hourly).take(6)) { f ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Label(forecastTime(f.time, showDaily), size = 9, color = Muted)
                    Icon(
                        if (f.condition == "sunny") Icons.Outlined.WbSunny
                        else Icons.Outlined.Cloud,
                        null,
                        Modifier.size(18.dp),
                        tint = Color(0xFFDCE5EC),
                    )
                    Label(
                        temperature(
                                f.temperature,
                                weather?.attributes?.optString("temperature_unit") == "°F",
                                c.fahrenheit,
                            )
                            ?.let { String.format(Locale.getDefault(), "%.0f°", it) } ?: "—",
                        size = 10,
                    )
                }
            }
        }
        Action(
            stringResource(if (showDaily) R.string.daily else R.string.hourly),
            { showDaily = !showDaily },
            Modifier.height(27.dp),
        )
    }
}

fun formatTemperature(value: Double?, sourceF: Boolean, targetF: Boolean): String =
    temperature(value, sourceF, targetF)?.let {
        String.format(
            Locale.getDefault(),
            if (kotlin.math.abs(it - kotlin.math.round(it)) < .01) "%.0f°%s" else "%.1f°%s",
            it,
            if (targetF) "F" else "C",
        )
    } ?: "—"

private fun forecastTime(time: String, daily: Boolean): String =
    runCatching {
            ZonedDateTime.parse(time)
                .withZoneSameInstant(java.time.ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern(if (daily) "EEE" else "HH:mm"))
        }
        .getOrDefault(time)

@Composable
private fun ControlsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    entities: List<Entity>,
    model: LauncherViewModel,
    modifier: Modifier,
    c: Config,
) {
    PanelCard(title, icon, modifier, c.darkPanels) {
        LazyRow(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            contentPadding = PaddingValues(2.dp),
        ) {
            items(entities, key = { it.id }) { entity ->
                Tile(
                    {
                        model.service(
                            entity,
                            if (entity.domain == "scene") "turn_on"
                            else if (entity.domain == "cover")
                                if (entity.state == "closed") "open_cover" else "close_cover"
                            else "toggle",
                        )
                    },
                    Modifier.width(60.dp).fillMaxHeight(),
                    selected = false,
                    enabled = entity.available,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(
                            if (entity.domain == "scene") Icons.Outlined.PlayCircle
                            else if (entity.domain == "cover") Icons.Outlined.Blinds
                            else Icons.Outlined.Lightbulb,
                            null,
                            Modifier.size(21.dp),
                            tint = if (entity.state == "on") Color(0xFFFFD151) else Muted,
                        )
                        Label(entity.name, size = 9, bold = true)
                        if (entity.domain != "scene")
                            Label(
                                if (!entity.available) stringResource(R.string.unavailable)
                                else if (entity.state == "on") stringResource(R.string.on)
                                else stringResource(R.string.off),
                                size = 9,
                                color = Muted,
                            )
                    }
                }
            }
            if (entities.isEmpty())
                item { Label(stringResource(R.string.entities), size = 10, color = Muted) }
        }
    }
}

@Composable
private fun ClimateCard(entity: Entity?, model: LauncherViewModel, modifier: Modifier, c: Config) {
    PanelCard(stringResource(R.string.climate), Icons.Outlined.Thermostat, modifier, c.darkPanels) {
        val target = entity?.number("temperature")
        val serverF by model.serverTemperatureF.collectAsStateWithLifecycle()
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Action(
                "−",
                { entity?.let { adjustClimate(model, it, -1) } },
                Modifier.width(36.dp).height(36.dp),
                enabled = entity?.available == true && target != null,
            )
            Label(
                formatTemperature(target, if (c.demo) false else serverF, c.fahrenheit),
                size = 21,
                bold = true,
            )
            Action(
                "+",
                { entity?.let { adjustClimate(model, it, 1) } },
                Modifier.width(36.dp).height(36.dp),
                enabled = entity?.available == true && target != null,
            )
        }
        Label(entity?.name ?: stringResource(R.string.unavailable), size = 11, color = Muted)
        Label(entity?.state ?: "—", size = 10, color = Green)
    }
}

fun adjustClimate(model: LauncherViewModel, entity: Entity, direction: Int) {
    val target = entity.number("temperature") ?: return
    val next =
        (target + (entity.number("target_temp_step") ?: .5) * direction).coerceIn(
            entity.number("min_temp") ?: 5.0,
            entity.number("max_temp") ?: 35.0,
        )
    model.service(entity, "set_temperature", JSONObject().put("temperature", next))
}

@Composable
private fun CameraCard(
    model: LauncherViewModel,
    c: Config,
    entities: Map<String, Entity>,
    modifier: Modifier,
    openCamera: (CameraConfig) -> Unit,
) {
    val cameras =
        if (c.demo) listOf(CameraConfig("camera.harbor"), CameraConfig("camera.rhine"))
        else c.cameras.take(2)
    PanelCard(stringResource(R.string.cameras), Icons.Outlined.Videocam, modifier, c.darkPanels) {
        cameras.forEach { camera ->
            Tile(
                { openCamera(camera) },
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                color = Ink,
            ) {
                if (!c.demo && c.livePreviews) CameraPlayer(camera, model, Modifier.fillMaxSize())
                else
                    Column(
                        Modifier.padding(7.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Outlined.Videocam, null, Modifier.size(20.dp), tint = Muted)
                        Label(
                            stringResource(
                                if (c.demo) R.string.camera_demo else R.string.camera_open
                            ),
                            size = 9,
                            color = Muted,
                        )
                    }
                Label(
                    entities[camera.entityId]?.name ?: camera.entityId,
                    Modifier.align(Alignment.BottomStart)
                        .background(Ink.copy(alpha = .8f))
                        .padding(5.dp),
                    size = 9,
                )
            }
        }
        if (cameras.isEmpty()) Label(stringResource(R.string.entities), size = 11, color = Muted)
    }
}

@Composable
private fun AppsPage(model: LauncherViewModel, c: Config) {
    val apps by model.installed.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label(stringResource(R.string.all_apps), size = 24, bold = true)
        LazyVerticalGrid(
            columns = GridCells.Fixed(5),
            contentPadding = PaddingValues(7.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            items(apps, key = { it.id }) { app ->
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    AppBanner(app, app.name, "", Modifier.fillMaxWidth().aspectRatio(16f / 9)) {
                        model.launchApp(app.id)
                    }
                    Label(app.name, size = 12)
                    val pinned = c.apps.any { it.id == app.id }
                    Action(
                        stringResource(if (pinned) R.string.unpin_app else R.string.pin_app),
                        {
                            model.update(
                                c.copy(
                                    apps =
                                        if (pinned) c.apps.filterNot { it.id == app.id }
                                        else c.apps + AppShortcut(app.id)
                                )
                            )
                        },
                        selected = pinned,
                    )
                }
            }
        }
    }
}

@Composable
private fun DevicePage(
    model: LauncherViewModel,
    c: Config,
    entities: Map<String, Entity>,
    scenes: Boolean,
) {
    val selected =
        (if (c.demo || scenes) entities.values else c.controls.mapNotNull { entities[it] }).filter {
            if (scenes) it.domain == "scene"
            else it.domain in listOf("light", "switch", "cover", "climate")
        }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(5.dp),
    ) {
        item {
            Label(
                stringResource(if (scenes) R.string.scenes else R.string.devices),
                size = 24,
                bold = true,
            )
        }
        items(selected, key = { it.id }) { entity ->
            Column(
                Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Label(entity.name, bold = true)
                Label(
                    if (entity.available) entity.state else stringResource(R.string.unavailable),
                    size = 11,
                    color = Muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    when (entity.domain) {
                        "cover" ->
                            listOf(
                                    R.string.open_cover to "open_cover",
                                    R.string.stop_cover to "stop_cover",
                                    R.string.close_cover to "close_cover",
                                )
                                .forEach { (label, action) ->
                                    Action(
                                        stringResource(label),
                                        { model.service(entity, action) },
                                        enabled = entity.available,
                                    )
                                }
                        "climate" -> {
                            Action(
                                "−",
                                { adjustClimate(model, entity, -1) },
                                enabled = entity.available,
                            )
                            Label("${entity.number("temperature") ?: "—"}°", size = 24)
                            Action(
                                "+",
                                { adjustClimate(model, entity, 1) },
                                enabled = entity.available,
                            )
                        }
                        else ->
                            Action(
                                stringResource(
                                    if (entity.domain == "scene") R.string.activate
                                    else if (entity.state == "on") R.string.off else R.string.on
                                ),
                                {
                                    model.service(
                                        entity,
                                        if (entity.domain == "scene") "turn_on" else "toggle",
                                    )
                                },
                                enabled = entity.available,
                            )
                    }
                }
            }
        }
        if (selected.isEmpty()) item { Label(stringResource(R.string.entities), color = Muted) }
    }
}

@Composable
private fun CamerasPage(
    model: LauncherViewModel,
    c: Config,
    entities: Map<String, Entity>,
    openCamera: (CameraConfig) -> Unit,
) {
    val cameras =
        if (c.demo) listOf(CameraConfig("camera.harbor"), CameraConfig("camera.rhine"))
        else c.cameras
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(5.dp),
    ) {
        item { Label(stringResource(R.string.cameras), size = 24, bold = true) }
        items(cameras, key = { it.entityId }) { camera ->
            Action(
                entities[camera.entityId]?.name ?: camera.entityId,
                { openCamera(camera) },
                Modifier.fillMaxWidth().height(90.dp),
            )
        }
        if (cameras.isEmpty()) item { Label(stringResource(R.string.entities), color = Muted) }
    }
}

@Composable
private fun weatherCondition(condition: String?): String =
    when (condition) {
        "partlycloudy" -> stringResource(R.string.partly_cloudy)
        "sunny" -> stringResource(R.string.sunny)
        "cloudy" -> stringResource(R.string.cloudy)
        "rainy",
        "pouring" -> stringResource(R.string.rainy)
        "clear-night" -> stringResource(R.string.clear_night)
        "snowy",
        "snowy-rainy" -> stringResource(R.string.snowy)
        "windy",
        "windy-variant" -> stringResource(R.string.windy)
        null,
        "unavailable",
        "unknown" -> stringResource(R.string.unavailable)
        else -> condition.replace('-', ' ')
    }
