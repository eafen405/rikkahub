package me.rerere.rikkahub.data.remote

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** PCM16 little endian mono 16kHz, the only shape the remote speech service accepts. */
private const val SPEECH_SAMPLE_RATE = 16_000
private const val SPEECH_CHUNK_BYTES = 3_200
private const val SPEECH_WAV_HEADER_BYTES = 44

/** Recording itself failed; the owner maps this to an audio-input failure. */
internal class SpeechCaptureException : Exception()

internal data class CapturedSpeech(val pcm: ByteArray, val limitReached: Boolean)

internal interface SpeechCaptureSource {
    suspend fun capture(
        recording: AtomicBoolean,
        maxDurationMs: Int,
        maxAudioBytes: Int,
        onStarted: suspend () -> Unit,
    ): CapturedSpeech

    fun stop()
}

/** Wraps PCM16 as a mono 16kHz WAV. The size budget belongs to the capture loop, not here. */
internal fun speechWav(pcm: ByteArray): ByteArray {
    require(pcm.isNotEmpty() && pcm.size % 2 == 0)
    val out = ByteArrayOutputStream(pcm.size + SPEECH_WAV_HEADER_BYTES)
    fun word(value: Int) {
        repeat(4) { out.write(value ushr (it * 8) and 0xff) }
    }

    fun half(value: Int) {
        out.write(value and 0xff)
        out.write(value ushr 8 and 0xff)
    }

    out.write("RIFF".toByteArray(Charsets.US_ASCII))
    word(pcm.size + 36)
    out.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
    word(16)
    half(1)
    half(1)
    word(SPEECH_SAMPLE_RATE)
    word(SPEECH_SAMPLE_RATE * 2)
    half(2)
    half(16)
    out.write("data".toByteArray(Charsets.US_ASCII))
    word(pcm.size)
    out.write(pcm)
    return out.toByteArray()
}

/** Records one take, bounded by the duration and byte budget the backend advertised. */
internal class AndroidSpeechCaptureSource : SpeechCaptureSource {
    @Volatile private var recorder: AudioRecord? = null

    override suspend fun capture(
        recording: AtomicBoolean,
        maxDurationMs: Int,
        maxAudioBytes: Int,
        onStarted: suspend () -> Unit,
    ): CapturedSpeech {
        val minBuffer = AudioRecord.getMinBufferSize(
            SPEECH_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw SpeechCaptureException()
        val audio = AudioRecord(
            MediaRecorder.AudioSource.MIC, SPEECH_SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, SPEECH_CHUNK_BYTES),
        )
        recorder = audio
        try {
            if (!recording.get()) return CapturedSpeech(byteArrayOf(), false)
            if (audio.state != AudioRecord.STATE_INITIALIZED) throw SpeechCaptureException()
            audio.startRecording()
            onStarted()
            // The service budget covers the whole WAV payload, header included.
            val budget = (maxAudioBytes - SPEECH_WAV_HEADER_BYTES).coerceAtLeast(0) / 2 * 2
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(SPEECH_CHUNK_BYTES)
            val deadline = SystemClock.elapsedRealtime() + maxDurationMs
            while (recording.get() && SystemClock.elapsedRealtime() < deadline && output.size() < budget) {
                val count = audio.read(buffer, 0, minOf(buffer.size, budget - output.size()))
                if (count < 0) {
                    if (!recording.get()) break
                    throw SpeechCaptureException()
                }
                if (count > 0) output.write(buffer, 0, count - count % 2)
            }
            return CapturedSpeech(output.toByteArray(), recording.get())
        } finally {
            recorder = null
            runCatching { audio.stop() }
            audio.release()
        }
    }

    override fun stop() {
        // AudioRecord.stop unblocks a blocking read without holding the caller thread.
        val active = recorder
        if (active != null) Thread { runCatching { active.stop() } }.start()
    }
}
