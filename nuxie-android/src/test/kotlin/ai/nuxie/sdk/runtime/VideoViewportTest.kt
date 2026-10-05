package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test

class VideoViewportTest {
    @Test fun viewPixelsBecomeLayoutPoints() {
        assertEquals(VideoViewport(0f, 0f, 393f, 852f), VideoViewport.layout(1179, 2556, 3f))
        assertEquals(VideoViewport(10f, 20f, 410f, 820f), VideoViewport.layout(1050, 2100, 2.625f, 10f, 20f))
        assertEquals(VideoViewport(0f, 0f, 852f, 393f), VideoViewport.layout(2556, 1179, 3f))
    }

    @Test fun emptySurfaceHasNoDemandAndInvalidDensityIsRejected() {
        assertEquals(VideoViewport(0f, 0f, 0f, 0f), VideoViewport.layout(0, 200, 3f))
        for (density in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { VideoViewport.layout(100, 200, density) }
        }
    }
}
