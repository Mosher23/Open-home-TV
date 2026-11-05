package dev.hatv.launcher.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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

@Composable
fun EnergyCard(config: Config, entities: Map<String, Entity>, modifier: Modifier = Modifier) {
    val keys = listOf("solar", "battery", "grid", "heat_pump", "ev", "household")
    val labels =
        listOf(
            R.string.solar,
            R.string.battery,
            R.string.grid,
            R.string.heat_pump,
            R.string.ev,
            R.string.household,
        )
    val colors =
        listOf(
            Color(0xFFF0BF30),
            Color(0xFF5DD163),
            Color(0xFF55BDD4),
            Color(0xFFBB6CDB),
            Color(0xFF2FC3E9),
            Color(0xFF55BDD4),
        )
    val values =
        keys.map { powerKw(entities[if (config.demo) "sensor.$it" else config.energy[it]]) }
    PanelCard(
        stringResource(R.string.energy) + " · " + stringResource(R.string.now),
        Icons.Outlined.Bolt,
        modifier,
        config.darkPanels,
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            FlowLines(values, colors, config.animate, Modifier.matchParentSize())
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.width(88.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    (0..2).forEach {
                        EnergyNode(
                            stringResource(labels[it]),
                            values[it],
                            colors[it],
                            Modifier.weight(1f),
                        )
                    }
                }
                Column(
                    Modifier.background(Color(0xF0152432), RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.Home, null, Modifier.size(24.dp), tint = Accent)
                    Label(
                        if (values.take(3).all { it != null })
                            String.format(
                                Locale.getDefault(),
                                "%.1f kW",
                                values.take(3).sumOf { it!! },
                            )
                        else "—",
                        size = 12,
                        bold = true,
                    )
                }
                Column(Modifier.width(94.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    (3..5).forEach {
                        EnergyNode(
                            stringResource(labels[it]),
                            values[it],
                            colors[it],
                            Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EnergyNode(title: String, value: Double?, color: Color, modifier: Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .background(color.copy(alpha = .12f), RoundedCornerShape(9.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Label(title, size = 10, color = color)
        Label(
            value?.let { String.format(Locale.getDefault(), "%.1f kW", it) } ?: "—",
            size = 12,
            bold = true,
        )
    }
}

@Composable
private fun FlowLines(
    values: List<Double?>,
    colors: List<Color>,
    animate: Boolean,
    modifier: Modifier,
) {
    val running = animate && values.any { it != null && abs(it) > .01 }
    val phase =
        if (running) {
            val transition = rememberInfiniteTransition(label = "energy")
            transition
                .animateFloat(
                    0f,
                    1f,
                    infiniteRepeatable(tween(2400, easing = LinearEasing)),
                    label = "flow",
                )
                .value
        } else 0f
    Canvas(modifier) {
        values.forEachIndexed { i, value ->
            val left = i < 3
            val start =
                Offset(
                    if (left) size.width * .24f else size.width * .55f,
                    if (left) size.height * ((i % 3 + .5f) / 3) else size.height * .5f,
                )
            val end =
                Offset(
                    if (left) size.width * .45f else size.width * .76f,
                    if (left) size.height * .5f else size.height * ((i % 3 + .5f) / 3),
                )
            val p =
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
            val width = (5f + (abs(value ?: 0.0).coerceAtMost(8.0) * 2).toFloat()).dp.toPx()
            drawPath(
                p,
                colors[i].copy(alpha = if (value == null || abs(value) < .01) .10f else .48f),
                style = Stroke(width, cap = StrokeCap.Round),
            )
            if (running && value != null && abs(value) > .01) {
                val distance = if (value < 0) 1f - phase else phase
                val measure = PathMeasure().apply { setPath(p, false) }
                drawCircle(colors[i], 2.dp.toPx(), measure.getPosition(measure.length * distance))
            }
        }
    }
}
