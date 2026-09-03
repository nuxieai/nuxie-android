package ai.nuxie.sdk

import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class NuxieTest {
    @org.junit.Before
    fun installFakeTransport() {
        Nuxie.overridesForTesting = NuxieCore.Overrides(transport = FakeTransport())
    }

    @After
    fun tearDown() {
        Nuxie.resetForTesting()
        Nuxie.overridesForTesting = null
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

    @Test
    fun hasFeatureBeforeSetupThrows() {
        assertThrows(IllegalStateException::class.java) {
            runBlocking { Nuxie.hasFeature("premium") }
        }
    }

    @Test
    fun dismissBeforeSetupIsANoop() = runBlocking {
        Nuxie.dismiss()
    }

    @Test
    fun runtimeLocaleChangesWaitForTheNextProfileSyncPoint() = runBlocking {
        val transport = FakeTransport()
        Nuxie.overridesForTesting = NuxieCore.Overrides(
            transport = transport,
            registerLifecycle = false,
            deviceLocaleIdentifier = { "device_TEST" },
        )
        val configuration = NuxieConfiguration("pk_test_locale").apply {
            localeIdentifier = "en_US"
        }
        Nuxie.setup(RuntimeEnvironment.getApplication(), configuration)
        val core = requireNotNull(Nuxie.core)

        // Queue behind setup's initial refresh so every inspected request is complete.
        assertTrue(core.profile.refreshAndWait())
        assertEquals("en_US", lastProfileLocale(transport))

        val initialRequestCount = profileRequestCount(transport)
        Nuxie.setLocaleIdentifier("fr_FR")
        assertEquals(initialRequestCount, profileRequestCount(transport))
        assertTrue(core.profile.refreshAndWait())
        assertEquals("fr_FR", lastProfileLocale(transport))

        val frenchRequestCount = profileRequestCount(transport)
        Nuxie.setLocaleIdentifier(null)
        assertEquals(frenchRequestCount, profileRequestCount(transport))
        assertTrue(core.profile.refreshAndWait())
        assertEquals("device_TEST", lastProfileLocale(transport))
    }

    private fun profileRequestCount(transport: FakeTransport): Int =
        transport.requests.count { it.url.path == "/profile" }

    private fun lastProfileLocale(transport: FakeTransport): String {
        val body = transport.requests.last { it.url.path == "/profile" }.body.decodeToString()
        return Regex("\\\"locale\\\":\\\"([^\\\"]+)\\\"")
            .find(body)
            ?.groupValues
            ?.get(1)
            ?: error("Profile request did not contain a locale: $body")
    }
}
