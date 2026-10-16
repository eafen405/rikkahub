package me.rerere.rikkahub.ui.identity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.rerere.rikkahub.data.datastore.ChatFontFamily
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.hooks.rememberColorMode
import me.rerere.rikkahub.ui.theme.ColorMode
import org.koin.compose.koinInject

/** Reuses the app's device-local settings; no account/profile copy. */
@Composable
internal fun RemoteDisplayPreferences() {
    val settings = LocalSettings.current.displaySetting
    val store = koinInject<SettingsStore>()
    val scope = rememberCoroutineScope()
    var mode by rememberColorMode()
    Column(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                mode = when (mode) {
                    ColorMode.SYSTEM -> ColorMode.LIGHT
                    ColorMode.LIGHT -> ColorMode.DARK
                    ColorMode.DARK -> ColorMode.SYSTEM
                }
            }) {
                Text("显示：" + when (mode) {
                    ColorMode.SYSTEM -> "跟随系统"
                    ColorMode.LIGHT -> "浅色"
                    ColorMode.DARK -> "深色"
                })
            }
            TextButton(onClick = {
                scope.launch {
                    store.update { current ->
                        current.copy(displaySetting = current.displaySetting.copy(
                            fontSizeRatio = when (current.displaySetting.fontSizeRatio) {
                                1f -> 1.15f
                                1.15f -> 1.3f
                                else -> 1f
                            },
                        ))
                    }
                }
            }) { Text("字号 ${(settings.fontSizeRatio * 100).toInt()}%") }
        }
        TextButton(onClick = {
            scope.launch {
                store.update { current ->
                    current.copy(displaySetting = current.displaySetting.copy(
                        chatFontFamily = if (current.displaySetting.chatFontFamily == ChatFontFamily.SERIF) {
                            ChatFontFamily.DEFAULT
                        } else {
                            ChatFontFamily.SERIF
                        },
                    ))
                }
            }
        }) { Text(if (settings.chatFontFamily == ChatFontFamily.SERIF) "字体：衬线" else "字体：默认") }
    }
}
