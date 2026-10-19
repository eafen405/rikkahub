package me.rerere.rikkahub.utils

import androidx.compose.ui.graphics.Color

fun Color.toCssHex(): String {
    val alpha = (alpha * 255).toInt()
    val red = (red * 255).toInt()
    val green = (green * 255).toInt()
    val blue = (blue * 255).toInt()
    return "#${String.format("%02X%02X%02X%02X", red, green, blue, alpha)}"
}
