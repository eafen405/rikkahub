package me.rerere.rikkahub.ui.identity

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import me.rerere.rikkahub.data.datastore.DisplayPreferencesStore
import me.rerere.rikkahub.data.datastore.LocalDisplayPreferences
import org.koin.compose.koinInject

@Composable
internal fun RemoteDisplayPreferences() {
    val preferences = LocalDisplayPreferences.current
    val store = koinInject<DisplayPreferencesStore>()
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = {
            store.update { current -> current.copy(fontSizeRatio = when (current.fontSizeRatio) {
                1f -> 1.15f
                1.15f -> 1.3f
                else -> 1f
            }) }
        }) { Text("字号 ${(preferences.fontSizeRatio * 100).toInt()}%") }
        TextButton(onClick = { store.update { it.copy(serif = !it.serif) } }) {
            Text(if (preferences.serif) "字体：衬线" else "字体：默认")
        }
    }
}
