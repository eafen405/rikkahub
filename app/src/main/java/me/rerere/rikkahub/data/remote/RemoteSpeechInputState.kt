package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

internal data class RemoteSpeechInputSnapshot(
    val busy: Boolean = false,
    val capturing: Boolean = false,
    val error: String? = null,
)

/**
 * One composer owns one take. Capture and HTTP are cancelled together; only the
 * current take may append editable text. No chat operation belongs to this state.
 * The recording/WAV path follows Fairybot's CloudSpeechRecognitionEngine.
 */
internal class RemoteSpeechInputState(
    private val scope: CoroutineScope,
    private val availability: suspend () -> RemoteSpeechAvailability,
    private val transcribe: suspend (ByteArray) -> String,
    private val captureFactory: () -> SpeechCaptureSource,
    private val readDraft: () -> String,
    private val writeDraft: (String) -> Unit,
    private val captureDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutable = MutableStateFlow(RemoteSpeechInputSnapshot())
    val snapshot = mutable.asStateFlow()
    private class Take(val draft: String) {
        val recording = AtomicBoolean(true)
        var source: SpeechCaptureSource? = null
        var job: Job? = null
    }
    private var active: Take? = null

    fun start(permissionGranted: Boolean) {
        if (active != null) return
        if (!permissionGranted) {
            mutable.value = RemoteSpeechInputSnapshot(error = "麦克风权限被拒绝")
            return
        }
        val take = Take(readDraft())
        active = take
        mutable.value = RemoteSpeechInputSnapshot(busy = true)
        take.job = scope.launch {
            try {
                val limits = availability()
                if (active !== take) return@launch
                if (!limits.available) {
                    mutable.value = RemoteSpeechInputSnapshot(error = "语音输入不可用")
                    return@launch
                }
                val source = captureFactory()
                take.source = source
                val captured = withContext(captureDispatcher) {
                    source.capture(take.recording, limits.maxDurationMs, limits.maxAudioBytes) {
                        withContext(scope.coroutineContext.minusKey(Job)) {
                            if (active === take) mutable.value = RemoteSpeechInputSnapshot(busy = true, capturing = true)
                        }
                    }
                }
                if (active !== take) return@launch
                mutable.value = RemoteSpeechInputSnapshot(busy = true)
                if (captured.pcm.isEmpty()) {
                    mutable.value = RemoteSpeechInputSnapshot(error = "未识别到文字")
                    return@launch
                }
                val text = transcribe(speechWav(captured.pcm))
                if (active !== take) return@launch
                if (text.isBlank()) mutable.value = RemoteSpeechInputSnapshot(error = "未识别到文字")
                else {
                    val base = if (take.draft.isEmpty() || take.draft.last().isWhitespace()) take.draft else "${take.draft} "
                    writeDraft(base + text)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (active === take) mutable.value = RemoteSpeechInputSnapshot(error = when (failure) {
                    is SecurityException -> "麦克风权限被拒绝"
                    is SpeechCaptureException -> "录音失败，原草稿已保留"
                    is IdentityRefused -> failure.message ?: "语音识别失败，原草稿已保留"
                    is IdentityUnreachable -> "语音服务连接失败，原草稿已保留"
                    else -> "语音识别失败，原草稿已保留"
                })
            } finally {
                if (active === take) {
                    active = null
                    take.recording.set(false)
                    mutable.value = mutable.value.copy(busy = false, capturing = false)
                }
            }
        }
    }

    /** Ending capture asks for a transcript; cancellation never asks for one. */
    fun stop() {
        val take = active ?: return
        take.recording.set(false)
        take.source?.stop()
        mutable.value = mutable.value.copy(capturing = false)
    }

    /** Also used when the account/session/input owner leaves composition. */
    fun cancel() {
        val take = active
        active = null
        take?.recording?.set(false)
        take?.source?.stop()
        take?.job?.cancel()
        mutable.value = RemoteSpeechInputSnapshot()
    }
}
