package dev.hatv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Flat Gallery grey: no wallpaper bitmap, blur or per-frame background rendering.
@Composable
fun Backdrop(modifier: Modifier) {
    Box(modifier.background(Ink))
}
