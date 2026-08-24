package ai.nuxie.sdk

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieTest {
    @After
    fun tearDown() {
        Nuxie.resetForTesting()
    }

    @Test
    fun setupRejectsABlankKeyWithoutInitializing() {
        assertFalse(Nuxie.isSetup)
        assertThrows(IllegalArgumentException::class.java) {
            Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("   "))
        }
        assertFalse(Nuxie.isSetup)
    }

    @Test
    fun setupInitializesOnceAndIgnoresRepeatedCalls() {
        Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("pk_test_first"))
        assertTrue(Nuxie.isSetup)
        val core = Nuxie.core

        // A repeated call is a warning no-op — even with an invalid key.
        Nuxie.setup(RuntimeEnvironment.getApplication(), NuxieConfiguration("   "))
        assertTrue(Nuxie.isSetup)
        assertTrue(core === Nuxie.core)
    }

    @Test
    fun versionIsExposed() {
        assertTrue(Nuxie.version.isNotBlank())
    }
}
