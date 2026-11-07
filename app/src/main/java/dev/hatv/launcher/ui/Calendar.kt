package dev.hatv.launcher.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hatv.launcher.LauncherViewModel
import dev.hatv.launcher.R
import dev.hatv.launcher.data.*
import dev.hatv.launcher.ha.HomeAssistantClient
import java.time.*
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@Composable
fun CalendarPage(model: LauncherViewModel, config: Config) {
    val states by model.entities.collectAsStateWithLifecycle()
    val catalog by model.catalog.collectAsStateWithLifecycle()
    val status by model.ha.status.collectAsStateWithLifecycle()
    val zone by model.serverZone.collectAsStateWithLifecycle()
    val calendars =
        states.values.filter { it.domain == "calendar" && catalog.visible(it) }.sortedBy { it.name }
    val ids = calendars.map { it.id }
    var selected by remember { mutableStateOf<String?>(null) }
    var events by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failures by remember { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<CalendarEvent?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(config.demo, config.server, status, ids, refresh) {
        if (config.demo) {
            val now = Instant.now()
            events =
                listOf(
                    CalendarEvent(
                        "calendar.demo",
                        "Family dinner",
                        now.plusSeconds(3600),
                        now.plusSeconds(7200),
                        false,
                        "Home",
                    ),
                    CalendarEvent(
                        "calendar.demo",
                        "Weekend",
                        LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant(),
                        LocalDate.now(zone).plusDays(2).atStartOfDay(zone).toInstant(),
                        true,
                    ),
                )
            return@LaunchedEffect
        }
        if (status != HomeAssistantClient.Status.ONLINE) return@LaunchedEffect
        while (isActive) {
            loading = true
            failures = 0
            val start = Instant.now()
            val end = LocalDate.now(zone).plusDays(7).atStartOfDay(zone).toInstant()
            val slots = Semaphore(3)
            try {
                events = coroutineScope {
                    ids.map { id ->
                            async {
                                slots.withPermit {
                                    try {
                                        model.ha.calendarEvents(id, start, end, zone)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        failures++
                                        emptyList()
                                    }
                                }
                            }
                        }
                        .awaitAll()
                        .flatten()
                        .sortedBy { it.start }
                        .take(1000)
                }
            } finally {
                loading = false
            }
            delay(15 * 60_000L)
        }
    }
    val focusedEvent = detail
    if (focusedEvent != null) {
        androidx.activity.compose.BackHandler { detail = null }
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val focus = remember { androidx.compose.ui.focus.FocusRequester() }
            var ready by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(ready) { if (ready) focus.requestFocus() }
            Action(
                stringResource(R.string.back),
                { detail = null },
                Modifier.focusRequester(focus).onGloballyPositioned { ready = true },
            )
            Label(focusedEvent.title, size = 30, bold = true)
            Label(
                focusedEvent.start
                    .atZone(zone)
                    .format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy")),
                size = 22,
            )
            Label(
                if (focusedEvent.allDay) stringResource(R.string.all_day)
                else
                    focusedEvent.start.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")) +
                        " – " +
                        focusedEvent.end.atZone(zone).format(DateTimeFormatter.ofPattern("HH:mm")),
                size = 22,
            )
            Label(
                calendars.find { it.id == focusedEvent.calendarId }?.name ?: "Demo",
                color = Accent,
            )
            if (focusedEvent.location.isNotBlank())
                Label(focusedEvent.location, size = 20, color = Muted)
        }
        return
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(stringResource(R.string.calendar), size = 26, bold = true)
            Action(stringResource(R.string.refresh), { refresh++ }, enabled = !loading)
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(4.dp),
        ) {
            item {
                Action(
                    stringResource(R.string.calendar_all),
                    { selected = null },
                    selected = selected == null,
                )
            }
            items(calendars, key = { it.id }) { calendar ->
                Action(
                    calendar.name,
                    { selected = calendar.id },
                    selected = selected == calendar.id,
                )
            }
        }
        if (loading) Label(stringResource(R.string.calendar_loading), size = 12, color = Muted)
        if (failures > 0) Label(stringResource(R.string.calendar_error), size = 12, color = Muted)
        if (!config.demo && status != HomeAssistantClient.Status.ONLINE)
            Label(stringResource(R.string.offline), color = Muted)
        val shown = events.filter { selected == null || it.calendarId == selected }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(shown) { event ->
                Tile(
                    { detail = event },
                    Modifier.fillMaxWidth()
                        .height(if (event.location.isBlank()) 110.dp else 135.dp),
                    color = androidx.compose.ui.graphics.Color.Transparent,
                ) {
                    PanelCard(
                        event.title,
                        Icons.Outlined.CalendarMonth,
                        Modifier.fillMaxSize(),
                        config.darkPanels,
                    ) {
                        val start = event.start.atZone(zone)
                        Label(
                            start.format(DateTimeFormatter.ofPattern("EEE, d MMM")) +
                                " · " +
                                if (event.allDay) stringResource(R.string.all_day)
                                else
                                    start.format(DateTimeFormatter.ofPattern("HH:mm")) +
                                        " – " +
                                        event.end
                                            .atZone(zone)
                                            .format(DateTimeFormatter.ofPattern("HH:mm")),
                            size = 18,
                        )
                        Label(
                            calendars.find { it.id == event.calendarId }?.name ?: "Demo",
                            color = Accent,
                            size = 12,
                        )
                        if (event.location.isNotBlank())
                            Label(event.location, color = Muted, size = 12)
                    }
                }
            }
            if (!loading && shown.isEmpty())
                item { Label(stringResource(R.string.calendar_empty), color = Muted) }
        }
    }
}
