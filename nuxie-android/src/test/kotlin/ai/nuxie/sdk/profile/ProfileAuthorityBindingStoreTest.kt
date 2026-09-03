package ai.nuxie.sdk.profile

import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.network.ProfileDeliveryAuthority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ProfileAuthorityBindingStoreTest {
    @Test
    fun bindingPersistsAndRejectsAReplacementAuthority() {
        val context = RuntimeEnvironment.getApplication()
        val scope = ProfileStorageScope(
            "pk_test_authority_binding",
            NuxieEnvironment.DEVELOPMENT,
        )
        val bindingFile = context.filesDir.resolve(
            "nuxie/profile-authorities-v1/${scope.authorityBindingFilename}",
        )
        bindingFile.delete()
        val authority = ProfileDeliveryAuthority("app-1", "test")

        assertTrue(ProfileAuthorityBindingStore(context, scope).bind(authority))
        assertEquals(authority, ProfileAuthorityBindingStore(context, scope).authority())
        assertFalse(
            ProfileAuthorityBindingStore(context, scope).bind(
                authority.copy(appId = "app-2"),
            ),
        )
        assertEquals(authority, ProfileAuthorityBindingStore(context, scope).authority())
        bindingFile.delete()
    }

    @Test
    fun profileBootstrapStorageIsScopedByCredentialAndEndpoint() {
        val first = ProfileStorageScope("pk_one", NuxieEnvironment.DEVELOPMENT)
        val same = ProfileStorageScope("pk_one", NuxieEnvironment.DEVELOPMENT)
        val rotated = ProfileStorageScope("pk_two", NuxieEnvironment.DEVELOPMENT)
        val production = ProfileStorageScope("pk_one", NuxieEnvironment.PRODUCTION)

        assertEquals(first.cacheSubdirectory, same.cacheSubdirectory)
        assertEquals(first.authorityBindingFilename, same.authorityBindingFilename)
        assertNotEquals(first.cacheSubdirectory, rotated.cacheSubdirectory)
        assertNotEquals(first.cacheSubdirectory, production.cacheSubdirectory)
    }
}
