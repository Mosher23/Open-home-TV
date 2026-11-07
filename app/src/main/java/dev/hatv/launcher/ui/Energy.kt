package dev.hatv.launcher.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hatv.launcher.LauncherViewModel
import dev.hatv.launcher.R
import dev.hatv.launcher.data.*
import dev.hatv.launcher.ha.HomeAssistantClient
import java.time.*
import java.util.Locale
import kotlin.math.abs
import kotlinx.coroutines.*

private val loadColors =
    listOf(
        Color(0xFFFF986F),
        Color(0xFFF16F9D),
        Color(0xFFF5D35E),
        Color(0xFF8C90EE),
        Color(0xFF49C9BB),
        Color(0xFF45C4ED),
        Color(0xFFBB77D7),
    )

private data class Reading(
    val id: String,
    val name: String,
    val value: Double?,
    val color: Color,
    val other: Boolean = false,
)

fun powerLabel(value: Double?): String =
    value?.let {
        if (abs(it) < 1) String.format(Locale.getDefault(), "%.0f W", it * 1000)
        else String.format(Locale.getDefault(), "%.2f kW", it)
    } ?: "—"

private fun energyLabel(value: Double?): String =
    value?.let {
        if (it > 0 && it < .1) "< " + String.format(Locale.getDefault(), "%.1f kWh", .1)
        else String.format(Locale.getDefault(), "%.1f kWh", it)
    } ?: "—"

private fun readings(
    plan: EnergyPlan,
    states: Map<String, Entity>,
    today: Boolean,
    values: Map<String, Double?>,
): List<Reading> =
    plan.loads
        .filter { (if (today) it.energyId else it.powerId).isNotBlank() }
        .map {
            Reading(
                it.id,
                it.name,
                if (today) values[it.energyId] else powerKw(states[it.powerId]),
                loadColors[(it.id.hashCode() and Int.MAX_VALUE) % loadColors.size],
            )
        }
        .filter { it.value != 0.0 }
        .sortedByDescending { it.value ?: -Double.MAX_VALUE }

private fun remainder(total: Double?, loads: List<Reading>): Double? =
    total?.let { t ->
        (t - loads.sumOf { it.value ?: 0.0 }).takeIf { it >= -1e-6 }?.coerceAtLeast(0.0)
    }

@Composable
fun EnergyCard(
    model: LauncherViewModel,
    config: Config,
    modifier: Modifier = Modifier,
    open: (() -> Unit)? = null,
) {
    val plan by model.loadPlan.collectAsStateWithLifecycle()
    val states by model.loadStates.collectAsStateWithLifecycle()
    val today by model.showEnergyToday.collectAsStateWithLifecycle()
    val data by model.todayEnergy.collectAsStateWithLifecycle()
    val status by model.ha.status.collectAsStateWithLifecycle()
    val zone by model.serverZone.collectAsStateWithLifecycle()
    var day by remember(zone) { mutableStateOf(LocalDate.now(zone)) }
    StartedEffect(today, plan.statisticIds, status, zone, config.demo) {
        if (!today) return@StartedEffect
        while (isActive) {
            day = LocalDate.now(zone)
            if (!config.demo && status == HomeAssistantClient.Status.ONLINE)
                model.refreshTodayEnergy(plan.statisticIds)
            val midnight = day.plusDays(1).atStartOfDay(zone).toInstant()
            delay(
                minOf(
                    5 * 60_000L,
                    Duration.between(Instant.now(), midnight).toMillis().coerceAtLeast(1000),
                )
            )
        }
    }
    val values =
        if (config.demo) DemoLoads.today else if (data.day == day) data.values else emptyMap()
    val nodes = readings(plan, states, today, values)
    val total = if (today) values[plan.homeEnergy] else powerKw(states[plan.homePower])
    val hasTotal = (if (today) plan.homeEnergy else plan.homePower).isNotBlank()
    val other =
        if (hasTotal)
            Reading(
                "__other",
                stringResource(R.string.power_other),
                remainder(total, nodes),
                Color(0xFFA0ADB4),
                true,
            )
        else null
    Tile(
        { model.showEnergyToday.value = !today },
        modifier.testTag("energy-card"),
        color = Color.Transparent,
    ) {
        Column(
            Modifier.fillMaxSize()
                .background(
                    if (config.darkPanels) Ink else Color(0xEB24252E),
                    RoundedCornerShape(16.dp),
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    if (today) Icons.Outlined.BarChart else Icons.Outlined.Bolt,
                    null,
                    Modifier.size(17.dp),
                    tint = Color(0xFFF5BB47),
                )
                Label(
                    stringResource(if (today) R.string.energy_consumption else R.string.energy),
                    Modifier.weight(1f),
                    size = 14,
                    bold = true,
                    maxLines = 1,
                )
                if (open != null)
                    Tile(
                        open,
                        Modifier.width(54.dp).height(24.dp).testTag("energy-details"),
                        color = Color(0xFF343640),
                    ) {
                        Label(stringResource(R.string.details), size = 10)
                    }
            }
            if (nodes.isEmpty() && !hasTotal)
                Label(
                    stringResource(R.string.load_setup_hint),
                    Modifier.weight(1f),
                    size = 12,
                    color = Muted,
                )
            else
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val capacity = (maxHeight / 16.dp).toInt().coerceIn(3, 10)
                    val room = capacity - if (other != null) 1 else 0
                    val chartNodes =
                        if (nodes.size <= room) nodes
                        else
                            nodes.take(room - 1) +
                                Reading(
                                    "__more",
                                    stringResource(R.string.more_loads, nodes.size - room + 1),
                                    nodes
                                        .drop(room - 1)
                                        .takeIf { list -> list.all { it.value != null } }
                                        ?.sumOf { it.value!! },
                                    Muted,
                                )
                    val chart = chartNodes + listOfNotNull(other)
                    LoadLines(chart, !today && config.animate, Modifier.matchParentSize())
                    Column(
                        Modifier.align(Alignment.CenterStart).width(maxWidth * .14f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Home,
                            null,
                            Modifier.size(26.dp),
                            tint = Color(0xFFF4AF2D),
                        )
                        if (hasTotal)
                            Label(
                                if (today) energyLabel(total) else powerLabel(total),
                                size = 11,
                                bold = true,
                                maxLines = 1,
                            )
                    }
                    Column(
                        Modifier.fillMaxSize().padding(start = maxWidth * .34f),
                        verticalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        chart.forEach { node ->
                            Row(
                                Modifier.fillMaxWidth()
                                    .weight(1f)
                                    .background(
                                        if (node.other) Color.White.copy(alpha = .07f)
                                        else Color.Transparent,
                                        RoundedCornerShape(7.dp),
                                    )
                                    .padding(horizontal = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Icon(
                                    loadIcon(node.name, node.other),
                                    null,
                                    Modifier.size(13.dp),
                                    tint = node.color,
                                )
                                Label(
                                    node.name,
                                    Modifier.weight(1f),
                                    size = 11,
                                    color = if (node.other) Muted else Color(0xFFE9E7EE),
                                    maxLines = 1,
                                )
                                Label(
                                    if (today) energyLabel(node.value) else powerLabel(node.value),
                                    size = 11,
                                    color = if (node.other) Muted else Color(0xFFE9E7EE),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            Label(
                if (today)
                    stringResource(R.string.today) +
                        " · " +
                        stringResource(
                            if (data.failed && !config.demo) R.string.energy_unavailable
                            else R.string.select_power
                        )
                else stringResource(R.string.now) + " · " + stringResource(R.string.select_energy),
                size = 9,
                color = Muted,
                maxLines = 1,
            )
        }
    }
}

private fun loadIcon(name: String, other: Boolean) =
    when {
        other -> Icons.Outlined.MoreHoriz
        name.contains("light", true) || name.contains("licht", true) -> Icons.Outlined.Lightbulb
        name.contains("Home Assistant", true) -> Icons.Outlined.Home
        name.contains("server", true) -> Icons.Outlined.Dns
        name.contains("desk", true) || name.contains("schreibtisch", true) ->
            Icons.Outlined.Computer
        name.contains("washer", true) ||
            name.contains("washing", true) ||
            name.contains("dryer", true) -> Icons.Outlined.LocalLaundryService
        name.contains("fridge", true) || name.contains("refrigerator", true) ->
            Icons.Outlined.Kitchen
        name.contains("coffee", true) -> Icons.Outlined.Coffee
        name.contains("media", true) -> Icons.Outlined.Tv
        else -> Icons.Outlined.Bolt
    }

@Composable
private fun LoadLines(nodes: List<Reading>, animate: Boolean, modifier: Modifier) {
    val running = animate && nodes.any { (it.value ?: 0.0) > .001 }
    val phase =
        if (running)
            rememberInfiniteTransition(label = "loads")
                .animateFloat(
                    0f,
                    1f,
                    infiniteRepeatable(tween(3000, easing = LinearEasing)),
                    label = "flow",
                )
                .value
        else .45f
    Canvas(modifier) {
        nodes.forEachIndexed { index, node ->
            val start = Offset(size.width * .14f, size.height * .5f)
            val end = Offset(size.width * .34f, size.height * (index + .5f) / nodes.size)
            val path =
                Path().apply {
                    moveTo(start.x, start.y)
                    cubicTo(size.width * .24f, start.y, size.width * .21f, end.y, end.x, end.y)
                }
            drawPath(
                path,
                node.color.copy(alpha = .85f),
                style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round),
            )
            if ((node.value ?: 0.0) > 0) {
                val measure = PathMeasure().apply { setPath(path, false) }
                val t = (phase + (index % 4) * .17f) % 1f
                val point = measure.getPosition(measure.length * t)
                drawCircle(node.color.copy(alpha = .09f), 7.dp.toPx(), point)
                drawCircle(node.color.copy(alpha = .22f), 4.dp.toPx(), point)
                drawCircle(node.color, 2.dp.toPx(), point)
            }
        }
    }
}

@Composable
fun PowerPage(model: LauncherViewModel, config: Config) {
    val plan by model.loadPlan.collectAsStateWithLifecycle()
    val states by model.loadStates.collectAsStateWithLifecycle()
    val today by model.showEnergyToday.collectAsStateWithLifecycle()
    val data by model.todayEnergy.collectAsStateWithLifecycle()
    val zone by model.serverZone.collectAsStateWithLifecycle()
    var day by remember(zone) { mutableStateOf(LocalDate.now(zone)) }
    StartedEffect(zone) {
        while (isActive) {
            day = LocalDate.now(zone)
            delay(
                Duration.between(Instant.now(), day.plusDays(1).atStartOfDay(zone).toInstant())
                    .toMillis()
                    .coerceAtLeast(1000)
            )
        }
    }
    val values =
        if (config.demo) DemoLoads.today else if (data.day == day) data.values else emptyMap()
    val nodes = readings(plan, states, today, values)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Action(
                stringResource(R.string.energy),
                { model.showEnergyToday.value = false },
                selected = !today,
            )
            Action(
                stringResource(R.string.energy_consumption) +
                    " · " +
                    stringResource(R.string.today),
                { model.showEnergyToday.value = true },
                selected = today,
            )
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(9.dp),
            contentPadding = PaddingValues(5.dp),
        ) {
            item { EnergyCard(model, config, Modifier.fillMaxWidth().height(250.dp)) }
            items(nodes, key = { it.id }) { node ->
                Tile(
                    {
                        model.message.value =
                            node.name +
                                " · " +
                                if (today) energyLabel(node.value) else powerLabel(node.value)
                    },
                    Modifier.fillMaxWidth().height(54.dp),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            loadIcon(node.name, false),
                            null,
                            Modifier.size(22.dp),
                            tint = node.color,
                        )
                        Label(node.name, Modifier.weight(1f), size = 15)
                        Label(
                            if (today) energyLabel(node.value) else powerLabel(node.value),
                            size = 20,
                        )
                    }
                }
            }
        }
    }
}
