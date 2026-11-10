package dev.hatv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hatv.launcher.LauncherViewModel
import dev.hatv.launcher.R
import dev.hatv.launcher.camera.CameraPlayer
import dev.hatv.launcher.data.*

@Composable
internal fun CameraFeedTile(
    camera: CameraConfig,
    model: LauncherViewModel,
    config: Config,
    name: String,
    play: Boolean,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    var playing by remember(camera) { mutableStateOf(false) }
    Tile(onClick, modifier.clip(RoundedCornerShape(12.dp)), color = Panel, focusedScale = 1f) {
        if (!config.demo && play)
            CameraPlayer(camera, model, Modifier.fillMaxSize()) { playing = it }
        else
            Column(
                Modifier.padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Outlined.Videocam, null, Modifier.size(38.dp), tint = Color(0xFFA5C9EF))
                Label(
                    stringResource(if (config.demo) R.string.camera_demo else R.string.camera_open),
                    size = 12,
                    color = Muted,
                )
            }
        Row(
            Modifier.align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = .65f))
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Label(name, Modifier.weight(1f), size = if (compact) 11 else 15, bold = true)
            if (playing && play && !config.demo)
                Label(
                    stringResource(R.string.camera_live),
                    Modifier.background(Color(0xFF207D46), RoundedCornerShape(5.dp)).padding(5.dp),
                    size = 10,
                    bold = true,
                )
        }
    }
}

@Composable
internal fun CamerasPage(
    model: LauncherViewModel,
    config: Config,
    entities: Map<String, Entity>,
    grid: LazyGridState,
    restoreCameraId: String?,
    onFocusRestored: () -> Unit,
    openCamera: (CameraConfig) -> Unit,
) {
    val cameras = config.selectedCameras().distinctBy { it.entityId }
    // Lazy grids may compose prefetched items. Keep at most four visible players, prioritizing
    // the most exposed cards when a scroll temporarily intersects three rows.
    val visibleIds by
        remember(grid) {
            derivedStateOf {
                val layout = grid.layoutInfo
                layout.visibleItemsInfo
                    .filter {
                        it.offset.y < layout.viewportEndOffset &&
                            it.offset.y + it.size.height > layout.viewportStartOffset
                    }
                    .sortedByDescending {
                        minOf(it.offset.y + it.size.height, layout.viewportEndOffset) -
                            maxOf(it.offset.y, layout.viewportStartOffset)
                    }
                    .take(4)
                    .mapNotNull { it.key as? String }
                    .toSet()
            }
        }
    LaunchedEffect(restoreCameraId, cameras) {
        val index = cameras.indexOfFirst { it.entityId == restoreCameraId }
        if (index >= 0 && restoreCameraId != null && restoreCameraId !in visibleIds)
            grid.scrollToItem(index)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Label(
            stringResource(R.string.cameras),
            Modifier.padding(start = 6.dp),
            size = 24,
            bold = true,
        )
        if (cameras.isEmpty()) Label(stringResource(R.string.entities), color = Muted)
        else
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                // Fit two complete rows at 16:9 TV sizes; video crops to fill the card.
                val height = minOf((maxWidth - 24.dp) / 2 * 9 / 16, (maxHeight - 24.dp) / 2)
                LazyVerticalGrid(
                    GridCells.Fixed(2),
                    Modifier.fillMaxSize().testTag("camera-grid"),
                    state = grid,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(6.dp),
                ) {
                    items(cameras, key = { it.entityId }) { camera ->
                        val focus = remember { FocusRequester() }
                        val visible = camera.entityId in visibleIds
                        LaunchedEffect(restoreCameraId, visible) {
                            if (visible && camera.entityId == restoreCameraId) {
                                focus.requestFocus()
                                onFocusRestored()
                            }
                        }
                        CameraFeedTile(
                            camera,
                            model,
                            config,
                            entities[camera.entityId]?.name ?: camera.entityId,
                            play = visible,
                            modifier =
                                Modifier.fillMaxWidth()
                                    .height(height)
                                    .focusRequester(focus)
                                    .testTag("grid-camera:${camera.entityId}"),
                            onClick = { openCamera(camera) },
                        )
                    }
                }
            }
    }
}
