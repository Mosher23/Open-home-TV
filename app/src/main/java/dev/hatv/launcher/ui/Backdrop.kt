package dev.hatv.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path

// Static vector landscape: no network wallpaper, bitmap allocation, blur or per-frame animation.
@Composable
fun Backdrop(modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        drawRect(
            Brush.linearGradient(
                listOf(Color(0xFF10283F), Color(0xFF4D3957), Color(0xFFEF9475)),
                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                end = androidx.compose.ui.geometry.Offset(w, h * .32f),
            )
        )
        fun mountain(points: List<Pair<Float, Float>>, color: Color) {
            val p =
                Path().apply {
                    moveTo(0f, h)
                    points.forEach { (x, y) -> lineTo(w * x, h * y) }
                    lineTo(w, h)
                    close()
                }
            drawPath(p, color)
        }
        mountain(
            listOf(
                0f to .28f,
                .12f to .23f,
                .24f to .27f,
                .38f to .13f,
                .46f to .18f,
                .51f to .15f,
                .63f to .27f,
                .72f to .22f,
                .84f to .29f,
                .96f to .18f,
                1f to .22f,
            ),
            Color(0xFF122135),
        )
        mountain(
            listOf(
                0f to .32f,
                .18f to .30f,
                .3f to .34f,
                .48f to .25f,
                .61f to .34f,
                .8f to .28f,
                1f to .34f,
            ),
            Color(0xFF101A26),
        )
        drawRect(
            Brush.verticalGradient(listOf(Color(0x22070D16), Color(0xED070D16), Color(0xFF070C13)))
        )
    }
}
