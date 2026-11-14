package com.phudy.redline.speech

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * "Translate the whole clip first": decodes the audio track of a local file or direct
 * video link (MP4/MKV/WEBM) in the background, runs speech→Thai on 30 s windows and
 * streams the timed cues back, so subtitles line up exactly with the picture.
 * (HLS streams aren't supported by MediaExtractor — those use live mode.)
 */
object FullClipTranslator {

    suspend fun run(
        ctx: Context, url: String, headers: Map<String, String>, fromMs: Long,
        onProgress: (Float) -> Unit, onCues: (List<TimedCue>) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val ex = MediaExtractor()
        if (url.startsWith("http")) ex.setDataSource(url, headers) else ex.setDataSource(ctx, Uri.parse(url), headers)
        val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("ไม่พบเสียงในคลิปนี้")
        ex.selectTrack(track)
        // Start where the viewer is, so the first subtitles are ready within ~30 s
        if (fromMs > 2000) ex.seekTo((fromMs - 1000) * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val startMs = (ex.sampleTime / 1000).coerceAtLeast(0)
        val fmt = ex.getTrackFormat(track)
        val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()

        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val window = 16000 * 30
        val buf = FloatArray(window)
        var n = 0
        var chunkStartMs = startMs
        var floatPcm = false
        var pos = 0.0
        var prev = 0f
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        suspend fun flushWindow() {
            if (n < 16000) { n = 0; return }
            val pcm = buf.copyOf(n)
            val start = chunkStartMs
            chunkStartMs += n * 1000L / 16000
            n = 0
            onCues(runCatching { SpeechTranslator.process(pcm, start) }.getOrDefault(emptyList()))
        }

        try {
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val size = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, size, ex.sampleTime, 0); ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        floatPcm = f.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val ob = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        ob.position(info.offset); ob.limit(info.offset + info.size)
                        val step = rate / 16000.0
                        val sb = ob.asShortBuffer()
                        val fb = ob.asFloatBuffer()
                        while ((if (floatPcm) fb.remaining() else sb.remaining()) >= ch) {
                            var sum = 0f
                            repeat(ch) { sum += if (floatPcm) fb.get() else sb.get() / 32768f }
                            val cur = sum / ch
                            while (pos < 1.0) {
                                buf[n++] = prev + (cur - prev) * pos.toFloat()
                                if (n == window) flushWindow()
                                pos += step
                            }
                            pos -= 1.0; prev = cur
                        }
                        if (durationUs > 0) onProgress(((info.presentationTimeUs - startMs * 1000).toFloat() / (durationUs - startMs * 1000)).coerceIn(0f, 1f))
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            flushWindow()
            onProgress(1f)
        } finally {
            runCatching { codec.stop() }; codec.release(); ex.release()
        }
    }

    /** Writes cues as an .srt so the subtitles can be reused in other players. */
    fun toSrt(cues: List<TimedCue>): String = buildString {
        fun ts(ms: Long) = "%02d:%02d:%02d,%03d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
        cues.sortedBy { it.startMs }.forEachIndexed { i, c ->
            append(i + 1).append('\n').append(ts(c.startMs)).append(" --> ").append(ts(c.endMs)).append('\n')
            append(c.thai).append("\n\n")
        }
    }
}
