package ai.nuxie.sdk.experiences

import java.io.Closeable
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class AcquiredJourneyReleaseTest {
    @Test fun `cancelled presentation retains artifacts until its last native owner retires`() {
        var closes = 0
        val release = AcquiredJourneyRelease(
            JourneyReleaseIdentity("app", "production", "experience", "version", "build", 1, "published", 1),
            emptyMap(), File("unused.nux"), protection = Closeable { closes++ },
        )
        val source = release.retain()
        val destination = release.retain()
        release.close()
        release.close()
        source.close()
        source.close()
        assertEquals(0, closes)
        destination.close()
        destination.close()
        assertEquals(1, closes)
    }
}
