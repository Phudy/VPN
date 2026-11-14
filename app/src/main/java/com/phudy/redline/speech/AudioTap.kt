package com.phudy.redline.speech

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Taps the decoded audio inside ExoPlayer, converts it to 16 kHz mono float
 * and hands out fixed-length chunks for live speech translation.
 * [onChunk] gets the chunk and its start time in media milliseconds (relative to [baseMs]).
 */
@UnstableApi
class AudioTap(private val chunkSec: Int, private val onChunk: (FloatArray, Long) -> Unit) : TeeAudioProcessor.AudioBufferSink {
    @Volatile var enabled = false
    @Volatile var baseMs = 0L          // media position at the last flush (set from the player thread)
    @Volatile var generation = 0       // bumps on every seek, so stale results can be dropped

    private var srcRate = 44100
    private var channels = 2
    private var encoding = C.ENCODING_PCM_16BIT
    private var pos = 0.0               // resampler read position (in source frames)
    private var step = 44100 / 16000.0
    private var prev = 0f
    private var emitted = 0L            // 16 kHz samples handed out since flush
    private val buf = FloatArray(16000 * 30)
    private var n = 0

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        srcRate = sampleRateHz; channels = channelCount; this.encoding = encoding
        step = srcRate / 16000.0; pos = 0.0; n = 0; emitted = 0; prev = 0f
        generation++
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!enabled) return
        val b = buffer.duplicate().order(ByteOrder.nativeOrder())
        val bytesPerSample = when (encoding) { C.ENCODING_PCM_FLOAT -> 4; C.ENCODING_PCM_16BIT -> 2; else -> return }
        val frames = b.remaining() / (bytesPerSample * channels)
        val chunkLen = 16000 * chunkSec
        var frame = 0
        while (frame < frames) {
            var sum = 0f
            for (c in 0 until channels) {
                sum += if (bytesPerSample == 4) b.getFloat() else b.getShort() / 32768f
            }
            val cur = sum / channels
            // linear-interpolating resampler: output samples that fall between prev and cur
            while (pos < 1.0) {
                val s = prev + (cur - prev) * pos.toFloat()
                buf[n++] = s
                if (n >= chunkLen) {
                    val start = baseMs + emitted * 1000 / 16000
                    onChunk(buf.copyOf(n), start)
                    emitted += n; n = 0
                }
                pos += step
            }
            pos -= 1.0
            prev = cur
            frame++
        }
    }
}
