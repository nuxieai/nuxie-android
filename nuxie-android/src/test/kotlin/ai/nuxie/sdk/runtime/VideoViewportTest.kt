package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class VideoViewportTest {
    @Test fun mapsLetterboxingAndPreservesDensityIndependence() {
        assertEquals(VideoViewport(-50f, 0f, 150f, 200f), VideoViewport.contain(100f, 200f, 200, 200))
        assertEquals(VideoViewport(0f, -50f, 200f, 150f), VideoViewport.contain(200f, 100f, 200, 200))
        assertEquals(VideoViewport(0f, 0f, 100f, 200f), VideoViewport.contain(100f, 200f, 300, 600))
        assertEquals(VideoViewport.contain(100f, 200f, 200, 200), VideoViewport.contain(100f, 200f, 600, 600))
    }

    @Test fun emptySurfaceHasNoDemandAndInvalidArtboardIsRejected() {
        assertEquals(VideoViewport(0f, 0f, 0f, 0f), VideoViewport.contain(100f, 200f, 0, 200))
        for (width in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { VideoViewport.contain(width, 200f, 100, 200) }
        }
    }
}
