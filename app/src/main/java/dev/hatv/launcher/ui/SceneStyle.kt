package dev.hatv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hatv.launcher.LauncherViewModel
import dev.hatv.launcher.R
import dev.hatv.launcher.data.*

private data class SceneStyle(val icon: ImageVector, val color: Color)

private fun sceneStyle(name: String, hint: String = ""): SceneStyle {
    val text = "$hint $name".lowercase()
    return when {
        listOf("morning", "morgen", "sunrise", "sunny").any { it in text } ->
            SceneStyle(Icons.Outlined.WbSunny, Color(0xFFFFBD66))
        listOf("night", "sleep", "nacht", "schlaf", "moon", "abend", "evening").any {
            it in text
        } -> SceneStyle(Icons.Outlined.NightsStay, Color(0xFFA9A3FF))
        listOf("cinema", "movie", "film", "theater").any { it in text } ->
            SceneStyle(Icons.Outlined.Movie, Color(0xFFDA8CF5))
        listOf("eco", "energy", "energie", "save", "sparen", "leaf").any { it in text } ->
            SceneStyle(Icons.Outlined.Eco, Color(0xFF7EE1A0))
        listOf("away", "abwesend", "urlaub", "vacation").any { it in text } ->
            SceneStyle(Icons.Outlined.FlightTakeoff, Color(0xFF67DCD5))
        listOf("privacy", "privat", "shield", "security").any { it in text } ->
            SceneStyle(Icons.Outlined.Shield, Color(0xFF91B8FF))
        listOf("boost", "heat", "heiz", "fire").any { it in text } ->
            SceneStyle(Icons.Outlined.Whatshot, Color(0xFFFF987E))
        listOf("comfort", "komfort", "relax").any { it in text } ->
            SceneStyle(Icons.Outlined.Weekend, Color(0xFFF1C17D))
        listOf("home", "hause", "house").any { it in text } ->
            SceneStyle(Icons.Outlined.Home, Color(0xFF70C9FF))
        else ->
            SceneStyle(
                Icons.Outlined.AutoAwesome,
                listOf(Color(0xFF95BEFF), Color(0xFFF39FC0), Color(0xFFA8DFB1), Color(0xFFC5A0F2))[
                    (name.hashCode() and Int.MAX_VALUE) % 4],
            )
    }
}

@Composable
fun SceneTile(
    entity: Entity,
    model: LauncherViewModel,
    modifier: Modifier,
    compact: Boolean = false,
) {
    val style = sceneStyle(entity.name, entity.attributes.optString("icon"))
    Tile(
        { model.service(entity, "turn_on") },
        modifier.testTag("scene:${entity.id}"),
        color = style.color.copy(alpha = .13f),
        enabled = entity.available,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(if (compact) 5.dp else 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (compact) 7.dp else 12.dp),
        ) {
            Box(
                Modifier.size(if (compact) 34.dp else 60.dp)
                    .background(
                        style.color.copy(alpha = .14f),
                        RoundedCornerShape(if (compact) 11.dp else 18.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    style.icon,
                    null,
                    Modifier.size(if (compact) 24.dp else 38.dp),
                    tint = if (entity.available) style.color else Muted,
                )
            }
            Label(entity.name, size = if (compact) 10 else 15, bold = true)
            if (!entity.available)
                Label(stringResource(R.string.unavailable), size = 10, color = Muted)
        }
    }
}

@Composable
fun PresetChip(preset: String, selected: Boolean, enabled: Boolean, action: () -> Unit) {
    val style = sceneStyle(preset)
    Tile(
        action,
        Modifier.height(44.dp).width(IntrinsicSize.Max).widthIn(min = 85.dp),
        selected = selected,
        color = style.color.copy(alpha = .14f),
        enabled = enabled,
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(style.icon, null, Modifier.size(22.dp), tint = if (enabled) style.color else Muted)
            Label(preset.replace('_', ' ').replaceFirstChar { it.titlecase() }, size = 13)
        }
    }
}

@Composable
fun ScenesPage(model: LauncherViewModel, entities: Map<String, Entity>) {
    val scenes = entities.values.filter { it.domain == "scene" }.sortedBy { it.name }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Label(stringResource(R.string.scenes), size = 24, bold = true)
        LazyVerticalGrid(
            GridCells.Fixed(4),
            contentPadding = PaddingValues(6.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(scenes, key = { it.id }) {
                SceneTile(it, model, Modifier.fillMaxWidth().height(140.dp))
            }
        }
        if (scenes.isEmpty()) Label(stringResource(R.string.no_entities), color = Muted)
    }
}
