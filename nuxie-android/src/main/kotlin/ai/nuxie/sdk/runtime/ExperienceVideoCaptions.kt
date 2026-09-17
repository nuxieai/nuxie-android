package ai.nuxie.sdk.runtime

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded extraction from the already authenticated, retained MP4. */
internal object ExperienceVideoCaptions {
    fun read(file: File, streamIndex: Int): List<NuxieVideoCaptionCue> {
        val extractor = MediaExtractor()
        try {
            FileInputStream(file).use { extractor.setDataSource(it.fd) }
            require(streamIndex in 0 until extractor.trackCount) { "Signed caption track is unavailable" }
            val format = extractor.getTrackFormat(streamIndex)
            require(format.getString(MediaFormat.KEY_MIME) == "text/3gpp-tt") { "Caption track is not mov_text: $format" }
            val duration = format.getLong(MediaFormat.KEY_DURATION) / 1_000_000.0
            require(duration.isFinite() && duration > 0)
            extractor.selectTrack(streamIndex)
            val buffer = ByteBuffer.allocate(1024 * 1024)
            val cues = mutableListOf<NuxieVideoCaptionCue>()
            var pending: Pair<Double, String>? = null
            var totalBytes = 0L
            var samples = 0
            while (true) {
                buffer.clear()
                val length = extractor.readSampleData(buffer, 0)
                if (length < 0) break
                require(++samples <= 100_000 && length in 2..buffer.capacity()) { "Caption sample limit exceeded" }
                totalBytes += length
                require(totalBytes <= 8 * 1024 * 1024) { "Caption track exceeds text budget" }
                val seconds = extractor.sampleTime / 1_000_000.0
                require(seconds >= 0 && seconds <= duration)
                pending?.let { (start, text) ->
                    require(seconds >= start) { "Caption samples are out of order" }
                    if (seconds > start && text.isNotEmpty()) cues += NuxieVideoCaptionCue(start, seconds, text)
                }
                val textLength = ((buffer.get(0).toInt() and 255) shl 8) or (buffer.get(1).toInt() and 255)
                require(textLength <= length - 2) { "Truncated mov_text sample" }
                buffer.position(2)
                buffer.limit(2 + textLength)
                val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(buffer).toString()
                pending = seconds to text
                if (!extractor.advance()) break
            }
            pending?.let { (start, text) ->
                if (duration > start && text.isNotEmpty()) cues += NuxieVideoCaptionCue(start, duration, text)
            }
            return cues
        } finally { extractor.release() }
    }
}
