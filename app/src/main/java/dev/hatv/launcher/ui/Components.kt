package dev.hatv.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.*

val Ink = Color(0xFF090F19)
val Panel = Color(0xD9192736)
val Muted = Color(0xFFAFBDCB)
val Accent = Color(0xFF42C9EC)
val Green = Color(0xFF55D990)

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    size: Int = 14,
    color: Color = Color.White,
    bold: Boolean = false,
    maxLines: Int = 2,
) {
    Text(
        text,
        modifier,
        color = color,
        fontSize = size.sp,
        lineHeight = (size * 1.25f).sp,
        maxLines = maxLines,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
fun Tile(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    color: Color = Color(0xFF233140),
    enabled: Boolean = true,
    focusedScale: Float = 1.025f,
    focusColor: Color = Accent,
    content: @Composable BoxScope.() -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        colors =
            ClickableSurfaceDefaults.colors(
                containerColor = if (selected) Color(0xFF225CE7) else color,
                focusedContainerColor = Color(0xFF214D65),
                contentColor = Color.White,
                focusedContentColor = Color.White,
                disabledContainerColor = color.copy(alpha = 0.45f),
            ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = focusedScale),
        border =
            ClickableSurfaceDefaults.border(
                focusedBorder =
                    Border(BorderStroke(2.dp, focusColor), shape = RoundedCornerShape(12.dp))
            ),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = content)
    }
}

@Composable
fun Action(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    Tile(
        onClick,
        modifier.height(36.dp).width(IntrinsicSize.Max).widthIn(min = 36.dp),
        selected,
        enabled = enabled,
    ) {
        Label(text, Modifier.padding(horizontal = 14.dp, vertical = 6.dp), size = 13)
    }
}

@Composable
fun PanelCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    dark: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .background(if (dark) Ink.copy(alpha = 0.95f) else Panel, RoundedCornerShape(16.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(icon, null, Modifier.size(17.dp), tint = Color.White)
            Label(title, size = 14, bold = true)
        }
        content()
    }
}

// Cancels work directly from lifecycle events, even while Compose's frame clock is paused.
@Composable
fun StartedEffect(vararg keys: Any?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(*keys, lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED, block)
    }
}
