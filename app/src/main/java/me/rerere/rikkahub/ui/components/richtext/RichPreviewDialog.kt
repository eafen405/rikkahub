package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import me.rerere.ui.webview.WEB_VIEW_BASE_URL
import me.rerere.ui.webview.WebView
import me.rerere.ui.webview.rememberWebViewState

@Composable
internal fun RichPreviewDialog(html: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column {
                TextButton(onClick = onDismiss) { Text("关闭预览") }
                WebView(
                    state = rememberWebViewState(data = html, baseUrl = WEB_VIEW_BASE_URL, settings = {
                        builtInZoomControls = true
                        displayZoomControls = false
                        useWideViewPort = true
                        loadWithOverviewMode = true
                    }),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
