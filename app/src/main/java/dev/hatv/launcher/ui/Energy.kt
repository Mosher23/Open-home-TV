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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hatv.launcher.R
import dev.hatv.launcher.data.*
import java.util.Locale
import kotlin.math.abs

private data class PowerNode(val title: String, val value: Double?, val color: Color)

private val powerColors =
    listOf(
        Color(0xFFF2BE53),
        Color(0xFF62D89D),
        Color(0xFF59BCEC),
        Color(0xFFB79AF8),
        Color(0xFF54D2D1),
    )

private fun powerEntities(config: Config, entities: Map<String, Entity>) =
    config.powerDevices
        .distinct()
        .filter { it !in config.energy.values }
        .mapNotNull { entities[it] }
        .sortedByDescending { powerKw(it) ?: -1.0 }

fun powerLabel(value: Double?): String =
    value?.let {
        if (abs(it) < 1) String.format(Locale.getDefault(), "%.0f W", it * 1000)
        else String.format(Locale.getDefault(), "%.2f kW", it)
    } ?: "—"

@Composable
fun EnergyCard(
    config: Config,
    entities: Map<String, Entity>,
    modifier: Modifier = Modifier,
    open: () -> Unit = {},
) {
    val keys = listOf("solar", "battery", "grid", "heat_pump", "ev")
    val names =
        listOf(R.string.solar, R.string.battery, R.string.grid, R.string.heat_pump, R.string.ev)
    val configured =
        keys.mapIndexedNotNull { i, key ->
            val id =
                if (config.demo) "sensor.$key" else config.energy[key]?.takeIf { it.isNotBlank() }
            id?.let {
                key to PowerNode(stringResource(names[i]), powerKw(entities[it]), powerColors[i])
            }
        }
    val sources =
        configured.filter { it.first in listOf("solar", "battery", "grid") }.map { it.second }
    val consumers =
        configured.filter { it.first in listOf("heat_pump", "ev") }.map { it.second } +
            powerEntities(config, entities).mapIndexed { i, e ->
                PowerNode(e.name, powerKw(e), powerColors[(i + 2) % powerColors.size])
            }
    val homeId = if (config.demo) "sensor.household" else config.energy["household"]
    // Whole-home measurement is authoritative; never invent a total from an incomplete source set.
    val home = powerKw(entities[homeId])
    Tile(open, modifier, color = Color.Transparent) {
        PanelCard(
            stringResource(R.string.energy),
            Icons.Outlined.Bolt,
            Modifier.fillMaxSize(),
            config.darkPanels,
        ) {
            if (configured.isEmpty() && homeId.isNullOrBlank() && consumers.isEmpty())
                Label(stringResource(R.string.power_empty), color = Muted, size = 12)
            else
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    val right = consumers.take(3)
                    FlowLines(sources, right, config.animate, Modifier.matchParentSize())
                    Row(
                        Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        if (sources.isNotEmpty())
                            Column(
                                Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                sources.forEach { PowerNodeView(it) }
                            }
                        Column(
                            Modifier.weight(1.2f)
                                .padding(horizontal = 7.dp)
                                .background(Color(0xFF193142), RoundedCornerShape(16.dp))
                                .padding(vertical = 14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Icon(Icons.Outlined.Home, null, Modifier.size(28.dp), tint = Accent)
                            if (!homeId.isNullOrBlank())
                                Label(powerLabel(home), size = 23, bold = true)
                            Label(stringResource(R.string.household), size = 10, color = Muted)
                        }
                        if (right.isNotEmpty())
                            Column(
                                Modifier.weight(1.1f),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                right.forEach { PowerNodeView(it) }
                                if (consumers.size > right.size)
                                    Label(
                                        "+${consumers.size - right.size}",
                                        size = 10,
                                        color = Muted,
                                    )
                            }
                    }
                }
        }
    }
}

@Composable
private fun PowerNodeView(node: PowerNode) {
    Column(
        Modifier.fillMaxWidth()
            .background(node.color.copy(alpha = .12f), RoundedCornerShape(10.dp))
            .padding(7.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Label(node.title, size = 10, color = node.color)
        Label(powerLabel(node.value), size = 13, bold = true)
    }
}

@Composable
private fun FlowLines(
    left: List<PowerNode>,
    right: List<PowerNode>,
    animate: Boolean,
    modifier: Modifier,
) {
    val nodes = left + right
    val running = animate && nodes.any { abs(it.value ?: 0.0) > .01 }
    val phase =
        if (running)
            rememberInfiniteTransition(label = "power")
                .animateFloat(
                    0f,
                    1f,
                    infiniteRepeatable(tween(2600, easing = LinearEasing)),
                    label = "flow",
                )
                .value
        else 0f
    Canvas(modifier) {
        nodes.forEachIndexed { i, node ->
            val source = i < left.size
            val count = if (source) left.size else right.size
            val index = if (source) i else i - left.size
            val y = size.height * ((index + .5f) / count)
            val start =
                Offset(
                    if (source) size.width * .22f else size.width * .54f,
                    if (source) y else size.height * .5f,
                )
            val end =
                Offset(
                    if (source) size.width * .46f else size.width * .78f,
                    if (source) size.height * .5f else y,
                )
            val path =
                Path().apply {
                    moveTo(start.x, start.y)
                    cubicTo(
                        (start.x + end.x) / 2,
                        start.y,
                        (start.x + end.x) / 2,
                        end.y,
                        end.x,
                        end.y,
                    )
                }
            val width = (2 + abs(node.value ?: 0.0).coerceAtMost(8.0) * 1.6).toFloat().dp.toPx()
            drawPath(
                path,
                node.color.copy(alpha = if (node.value == null) .08f else .4f),
                style = Stroke(width, cap = StrokeCap.Round),
            )
            if (running && abs(node.value ?: 0.0) > .01) {
                val measure = PathMeasure().apply { setPath(path, false) }
                val t = if ((node.value ?: 0.0) < 0) 1 - phase else phase
                drawCircle(node.color, 2.dp.toPx(), measure.getPosition(measure.length * t))
            }
        }
    }
}

@Composable
fun PowerPage(config: Config, entities: Map<String, Entity>) {
    LazyColumn(
        contentPadding = PaddingValues(6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { EnergyCard(config, entities, Modifier.fillMaxWidth().height(240.dp)) }
        items(powerEntities(config, entities), key = { it.id }) { entity ->
            var expanded by remember(entity.id) { mutableStateOf(false) }
            Tile(
                { expanded = !expanded },
                Modifier.fillMaxWidth().height(if (expanded) 100.dp else 68.dp),
            ) {
                Row(
                    Modifier.fillMaxSize().padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Label(entity.name, bold = true)
                        if (expanded) Label(stringResource(R.string.now), color = Muted, size = 12)
                    }
                    Label(powerLabel(powerKw(entity)), size = 24, color = Accent)
                }
            }
        }
    }
}
