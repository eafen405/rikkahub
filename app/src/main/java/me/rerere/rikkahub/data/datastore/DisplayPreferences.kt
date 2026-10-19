package me.rerere.rikkahub.data.datastore

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class DisplayPreferences(val fontSizeRatio: Float = 1f, val serif: Boolean = false)

class DisplayPreferencesStore(context: Context) {
    private val preferences = context.getSharedPreferences("ourfairy.display", Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(DisplayPreferences(
        fontSizeRatio = preferences.getFloat("fontSizeRatio", 1f),
        serif = preferences.getBoolean("serif", false),
    ))
    val state = mutableState.asStateFlow()

    fun update(transform: (DisplayPreferences) -> DisplayPreferences) {
        val next = transform(mutableState.value)
        preferences.edit().putFloat("fontSizeRatio", next.fontSizeRatio)
            .putBoolean("serif", next.serif).apply()
        mutableState.value = next
    }
}

val LocalDisplayPreferences = compositionLocalOf { DisplayPreferences() }
