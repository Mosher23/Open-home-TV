package dev.hatv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hatv.launcher.LauncherViewModel
import dev.hatv.launcher.R
import dev.hatv.launcher.data.*
import dev.hatv.launcher.ha.HomeAssistantClient
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
internal fun GalleryClockWeather(
    model: LauncherViewModel,
    config: Config,
    entities: Map<String, Entity>,
    modifier: Modifier,
    forecast: () -> Unit,
) {
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    StartedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now()
            delay(1000L * (60 - now.second))
        }
    }
    val status by model.ha.status.collectAsStateWithLifecycle()
    val weather = entities[if (config.demo) "weather.home" else config.weather]
    val temperature =
        formatTemperature(
            weather?.number("temperature"),
            weather?.attributes?.optString("temperature_unit") == "°F",
            config.fahrenheit,
        )
    val forecastLabel = stringResource(R.string.forecast)
    Tile(
        forecast,
        modifier.testTag("home-weather").semantics { contentDescription = forecastLabel },
        color = if (config.darkPanels) Ink else Panel,
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Label(
                    now.format(DateTimeFormatter.ofPattern("HH:mm")),
                    size = 61,
                    bold = true,
                    maxLines = 1,
                )
                Label(
                    now.format(DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())),
                    size = 18,
                    color = Muted,
                )
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Divider))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(56.dp)) {
                    Icon(
                        if (weather?.state == "clear-night") Icons.Outlined.NightsStay
                        else Icons.Outlined.WbSunny,
                        null,
                        Modifier.size(40.dp).align(Alignment.TopEnd),
                        tint =
                            if (weather?.state == "clear-night") Color(0xFFB8A2EE)
                            else Color(0xFFF5C84E),
                    )
                    if (weather?.state != "sunny" && weather?.state != "clear-night")
                        Icon(
                            Icons.Outlined.Cloud,
                            null,
                            Modifier.size(47.dp).align(Alignment.BottomStart),
                            tint = Color(0xFFBCD4EA),
                        )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Label(
                        temperature,
                        size = if (temperature.length > 5) 30 else 36,
                        bold = true,
                        maxLines = 1,
                    )
                    Label(config.location, size = 15, color = Muted)
                }
            }
            val connected = status == HomeAssistantClient.Status.ONLINE
            Row(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(
                    "●",
                    size = 9,
                    color = if (connected) Green else if (config.demo) Accent else Color(0xFFF5C84E),
                )
                Label(
                    stringResource(
                        if (config.demo) R.string.demo
                        else if (connected) R.string.online else R.string.offline
                    ),
                    size = 9,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
internal fun GalleryControls(
    title: String,
    entities: List<Entity>,
    model: LauncherViewModel,
    scenes: Boolean,
    dark: Boolean,
    modifier: Modifier,
) {
    BoxWithConstraints(
        modifier
            .background(if (dark) Ink else Panel, RoundedCornerShape(16.dp))
            .padding(9.dp)
            .semantics { contentDescription = title }
    ) {
        val visible = if (scenes) 4 else 2
        val width =
            ((maxWidth - 6.dp - 8.dp * (visible - 1)) / visible).coerceAtLeast(
                if (scenes) 70.dp else 100.dp
            )
        LazyRow(
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(3.dp),
        ) {
            items(entities, key = { it.id }) { entity ->
                if (scenes)
                    SceneTile(entity, model, Modifier.width(width).fillMaxHeight(), compact = true)
                else
                    Tile(
                        { model.service(entity, "toggle") },
                        Modifier.width(width).fillMaxHeight().testTag("home-control:${entity.id}"),
                        color = if (entity.state == "on") Color(0xFF494949) else Raised,
                        enabled = entity.available,
                    ) {
                        Column(
                            Modifier.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                if (entity.domain == "switch") Icons.Outlined.PowerSettingsNew
                                else Icons.Outlined.Lightbulb,
                                null,
                                Modifier.size(28.dp),
                                tint =
                                    if (entity.state == "on") Color(0xFFF5C84E)
                                    else Color(0xFFA5B6CD),
                            )
                            Label(entity.name, size = 12, maxLines = 2)
                            Label(
                                stringResource(
                                    if (!entity.available) R.string.unavailable
                                    else if (entity.state == "on") R.string.on else R.string.off
                                ),
                                size = 10,
                                color = Muted,
                            )
                        }
                    }
            }
            if (entities.isEmpty())
                item { Label(stringResource(R.string.entities), size = 12, color = Muted) }
        }
    }
}
