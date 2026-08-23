package ai.nuxie.sdk

import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NuxieTest {
    @Test
    fun setupValidatesTheInitialKeyAndOnlyInitializesOnce() {
        assertFalse(Nuxie.isSetup)

        assertThrows(IllegalArgumentException::class.java) {
            Nuxie.setup(TestContext(), NuxieConfiguration("   "))
        }
        assertFalse(Nuxie.isSetup)

        Nuxie.setup(TestContext(), NuxieConfiguration("pk_test_first"))
        assertTrue(Nuxie.isSetup)

        Nuxie.setup(UnusedContext(), NuxieConfiguration("   "))
        assertTrue(Nuxie.isSetup)
    }

    private class TestContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }

    private class UnusedContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context =
            error("A repeated setup call must not read its context.")
    }
}
