package ai.nuxie.sdk.runtime

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.io.FileInputStream
import kotlin.math.ceil

/** Inspection of an acquired immutable file; does not create a decoder or copy its payload. */
internal object ExperienceVideoDecodeCost {
    fun read(file: File): Long {
        val extractor = MediaExtractor()
        try {
            FileInputStream(file).use { extractor.setDataSource(it.fd) }
            require(extractor.trackCount in 1..16) { "Invalid video track count" }
            val tracks = (0 until extractor.trackCount).filter {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
            require(tracks.size == 1) { "A single video track is required" }
            val track = tracks.single()
            val format = extractor.getTrackFormat(track)
            val width = format.getInteger(MediaFormat.KEY_WIDTH)
            val height = format.getInteger(MediaFormat.KEY_HEIGHT)
            // Use the documented extractor keys directly; the corresponding
            // MediaFormat constants require API 30.
            val hasAspect = format.containsKey("sar-width") && format.containsKey("sar-height")
            val aspectWidth = if (hasAspect) format.getInteger("sar-width") else 1
            val aspectHeight = if (hasAspect) format.getInteger("sar-height") else 1
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            require(width in 1..16384 && height in 1..16384 && durationUs > 0) { "Invalid video dimensions or duration" }
            extractor.selectTrack(track)
            val timestamps = mutableListOf<Long>()
            while (extractor.sampleTrackIndex >= 0) {
                require(timestamps.size < 100_000) { "Video sample limit exceeded" }
                val timestamp = extractor.sampleTime
                require(timestamp >= 0) { "Invalid video sample timestamp" }
                timestamps.add(timestamp)
                if (!extractor.advance()) break
            }
            return pixelsPerSecond(width, height, durationUs, timestamps, aspectWidth, aspectHeight)
        } finally { extractor.release() }
    }

    /** Shortest presentation interval is conservative for variable-rate and looping clips. */
    internal fun pixelsPerSecond(width: Int, height: Int, durationUs: Long, timestamps: List<Long>,
                                 pixelAspectWidth: Int = 1, pixelAspectHeight: Int = 1): Long {
        require(width in 1..16384 && height in 1..16384 && durationUs > 0)
        require(pixelAspectWidth > 0 && pixelAspectHeight > 0) { "Invalid video pixel aspect ratio" }
        // MediaPlayer expands the reported display width for non-square pixels.
        // Budget both coded decode work and RGBA delivery; never discount coded
        // work when the display is narrower. Round up before allocating capacity.
        val displayWidth = (width.toLong() * pixelAspectWidth + pixelAspectHeight - 1) / pixelAspectHeight
        require(displayWidth in 1..16384) { "Video display dimensions exceed limits" }
        val workWidth = maxOf(width.toLong(), displayWidth)
        require(timestamps.isNotEmpty() && timestamps.size <= 100_000 && timestamps.all { it >= 0 })
        val sorted = timestamps.sorted()
        val span = sorted.last() - sorted.first()
        require(span < durationUs) { "Video samples exceed track duration" }
        var intervalUs = durationUs - span
        for (index in 1 until sorted.size) {
            val delta = sorted[index] - sorted[index - 1]
            require(delta > 0) { "Duplicate video presentation timestamp" }
            intervalUs = minOf(intervalUs, delta)
        }
        val rate = ceil(1_000_000.0 / intervalUs).toLong()
        return Math.multiplyExact(Math.multiplyExact(workWidth, height.toLong()), rate)
    }
}
