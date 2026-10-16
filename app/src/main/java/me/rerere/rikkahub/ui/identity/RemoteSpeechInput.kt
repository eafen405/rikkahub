package me.rerere.rikkahub.ui.identity

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import me.rerere.rikkahub.data.remote.AndroidSpeechCaptureSource
import me.rerere.rikkahub.data.remote.RemoteChatState
import me.rerere.rikkahub.data.remote.RemoteSpeechInputState

/** A take freezes this composer; completion returns editable text, never sends it. */
@Composable
internal fun RemoteSpeechInput(
    state: RemoteChatState,
    epoch: Long,
    enabled: Boolean,
    readDraft: () -> String,
    writeDraft: (String) -> Unit,
    onBusy: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val read by rememberUpdatedState(readDraft)
    val write by rememberUpdatedState(writeDraft)
    val input = remember(state, epoch) {
        RemoteSpeechInputState(scope, { state.speechAvailability(epoch) },
            { state.transcribeSpeech(epoch, it) }, { AndroidSpeechCaptureSource() },
            { read() }, { write(it) })
    }
    val snapshot by input.snapshot.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // A permission dialog ends the interaction. Grant readies the next tap.
        if (!granted) input.start(false)
    }
    LaunchedEffect(input, snapshot.busy) { onBusy(snapshot.busy) }
    DisposableEffect(input) { onDispose { input.cancel() } }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = enabled || snapshot.capturing, onClick = {
            if (snapshot.capturing) input.stop()
            else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.RECORD_AUDIO)
            } else input.start(true)
        }) {
            Text(if (snapshot.capturing) "结束录音并转写" else if (snapshot.busy) "正在转写…" else "语音输入")
        }
        if (snapshot.busy) OutlinedButton(onClick = input::cancel) { Text("取消") }
    }
    snapshot.error?.let { Text(it) }
}
