package me.rerere.rikkahub.data.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteSpeechInputStateTest {
    private class Capture : SpeechCaptureSource {
        val ended = CompletableDeferred<Unit>()
        var stopped = false
        var duration = 0
        var bytes = 0
        override suspend fun capture(recording: AtomicBoolean, maxDurationMs: Int, maxAudioBytes: Int, onStarted: suspend () -> Unit): CapturedSpeech {
            duration = maxDurationMs; bytes = maxAudioBytes
            onStarted()
            ended.await()
            return CapturedSpeech(byteArrayOf(1, 0, 2, 0), false)
        }
        override fun stop() { stopped = true; ended.complete(Unit) }
    }

    @Test fun `recording ends as editable text and only after explicit stop`() = runTest {
        var draft = "原草稿"
        val source = Capture()
        var audio: ByteArray? = null
        val input = RemoteSpeechInputState(backgroundScope,
            { RemoteSpeechAvailability(true, 1000, 32044) },
            { audio = it; "识别文字" }, { source }, { draft }, { draft = it },
            StandardTestDispatcher(testScheduler))
        input.start(true); runCurrent()
        assertTrue(input.snapshot.value.capturing)
        assertNull(audio)
        assertEquals(1000, source.duration)
        assertEquals(32044, source.bytes)
        input.stop(); runCurrent()
        assertEquals("RIFF", audio!!.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("原草稿 识别文字", draft)
        assertFalse(input.snapshot.value.busy)
    }

    @Test fun `cancelled late result cannot replace another take or owner draft`() = runTest {
        var draft = "保留"
        val oldResult = CompletableDeferred<String>()
        val captures = mutableListOf<Capture>()
        var calls = 0
        val input = RemoteSpeechInputState(backgroundScope,
            { RemoteSpeechAvailability(true, 1000, 32044) },
            { calls++; withContext(NonCancellable) { oldResult.await() } },
            { Capture().also { captures += it } }, { draft }, { draft = it },
            StandardTestDispatcher(testScheduler))
        input.start(true); runCurrent(); input.stop(); runCurrent()
        assertEquals(1, calls)
        input.cancel()
        draft = "新会话草稿"
        input.start(true); runCurrent()
        oldResult.complete("旧结果"); runCurrent()
        assertEquals("新会话草稿", draft)
        assertTrue(input.snapshot.value.capturing)
        input.cancel(); runCurrent()
        assertTrue(captures.all { it.stopped })
        assertFalse(input.snapshot.value.busy)
    }

    @Test fun `denied permission and unavailable service preserve the original draft`() = runTest {
        var draft = "保留"
        var recorded = false
        val input = RemoteSpeechInputState(backgroundScope,
            { RemoteSpeechAvailability(false, 1000, 32044) }, { "不应调用" },
            { recorded = true; Capture() }, { draft }, { draft = it },
            StandardTestDispatcher(testScheduler))
        input.start(false)
        assertEquals("麦克风权限被拒绝", input.snapshot.value.error)
        input.start(true); runCurrent()
        assertEquals("语音输入不可用", input.snapshot.value.error)
        assertEquals("保留", draft)
        assertFalse(recorded)
        assertFalse(input.snapshot.value.busy)
    }
    @Test fun `empty and failed transcription leave editable draft intact`() = runTest {
        var draft = "不要丢失"
        var transcript = ""
        var failure: Exception? = null
        var capture = Capture()
        val input = RemoteSpeechInputState(backgroundScope,
            { RemoteSpeechAvailability(true, 1000, 32044) },
            { failure?.let { throw it }; transcript }, { capture }, { draft }, { draft = it },
            StandardTestDispatcher(testScheduler))
        input.start(true); runCurrent(); input.stop(); runCurrent()
        assertEquals("未识别到文字", input.snapshot.value.error)
        assertEquals("不要丢失", draft)
        capture = Capture()
        failure = IdentityRefused(504, "speech/timeout", "语音识别超时")
        input.start(true); runCurrent(); input.stop(); runCurrent()
        assertEquals("语音识别超时", input.snapshot.value.error)
        assertEquals("不要丢失", draft)
        assertFalse(input.snapshot.value.busy)
    }

}
