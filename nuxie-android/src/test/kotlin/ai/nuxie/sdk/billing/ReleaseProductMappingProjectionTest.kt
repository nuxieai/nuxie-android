package ai.nuxie.sdk.billing

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.core.NuxieCore
import ai.nuxie.sdk.experiences.AuthenticatedRelease
import ai.nuxie.sdk.experiences.ExperienceReleaseIdentity
import ai.nuxie.sdk.experiences.SupportedRuntime
import ai.nuxie.sdk.features.FeatureInfo
import ai.nuxie.sdk.identity.IdentityService
import ai.nuxie.sdk.network.HttpTransport
import ai.nuxie.sdk.testsupport.FakeTransport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ReleaseProductMappingProjectionTest {
    @Test
    fun retainedEvidenceProjectsWhenReleaseAdmissionDeliversItsDescriptor() = runBlocking {
        val application = RuntimeEnvironment.getApplication()
        val apiKey = "pk_test_release_mapping_${System.nanoTime()}"
        val owner = "customer-release-mapping-${System.nanoTime()}"
        val identity = IdentityService(application).also { it.setDistinctId(owner) }
        val release = authenticatedRelease()
        val profile = releaseProfile(release.identity)
        val transport = FakeTransport().apply {
            respond = { request ->
                if (request.url.path == "/profile") {
                    HttpTransport.Response(200, profile.toString().encodeToByteArray())
                } else {
                    HttpTransport.Response(200, ByteArray(0))
                }
            }
        }
        val store = InMemoryPurchaseEvidenceStore().also {
            it.upsert(
                PurchaseEvidence(
                    purchaseToken = "restored-token",
                    packageName = "com.example.app",
                    storeProductIds = listOf("play-credit-pack"),
                    nuxieProductId = null,
                    purchaseState = StoredPurchaseState.PURCHASED,
                    syncAttributionDistinctId = owner,
                    ownerDistinctId = owner,
                    acknowledged = false,
                    firstSeenMillis = 1L,
                    signatureVerificationRequired = false,
                    signatureVerified = false,
                    authorityScope = purchaseAuthorityScope(apiKey, NuxieEnvironment.DEVELOPMENT),
                ),
            )
        }
        val core = NuxieCore(
            context = application,
            apiKey = apiKey,
            environment = NuxieEnvironment.DEVELOPMENT,
            logLevel = LogLevel.NONE,
            beforeSend = null,
            overrides = NuxieCore.Overrides(
                transport = transport,
                identity = identity,
                purchaseEvidenceStore = store,
                journeySupportedRuntime = { supportedRuntime() },
                authenticateRelease = { _, _ -> release },
                registerLifecycle = false,
            ),
        )
        try {
            assertTrue(core.profile.refreshAndWait())

            withTimeout(5_000) {
                core.featureInfo.all.first { it["credits"]?.balance == 15.0 }
            }
            assertEquals(FeatureInfo.State.Reconciling, core.featureInfo.state.value)
            assertEquals(
                10.0,
                store.load().getValue("restored-token").pinnedFeatureAllowances
                    ?.single()?.allowance!!,
                0.0,
            )
        } finally {
            core.stop()
        }
    }

    private fun authenticatedRelease(): AuthenticatedRelease {
        val identity = ExperienceReleaseIdentity(
            appId = "app",
            environment = "development",
            experienceId = "experience-1",
            experienceVersionId = "version-1",
            buildId = "build-1",
            versionNumber = 1,
            publishedAt = "2026-01-01T00:00:00.000Z",
            publishedAtSeq = 1,
        )
        val descriptor = buildJsonObject {
            put("products", JsonArray(listOf(buildJsonObject {
                put("id", JsonPrimitive("credit-pack"))
                put("type", JsonPrimitive("nonConsumable"))
                put("store", buildJsonObject {
                    put("platform", JsonPrimitive("google_play"))
                    put("productId", JsonPrimitive("play-credit-pack"))
                })
                put("entitlements", JsonArray(listOf(buildJsonObject {
                    put("id", JsonPrimitive("credits"))
                    put("featureId", JsonPrimitive("credits"))
                    put("allowanceType", JsonPrimitive("fixed"))
                    put("allowance", JsonPrimitive(10.0))
                })))
            })))
            put("enrollment", buildJsonObject {
                put("trigger", buildJsonObject {
                    put("type", JsonPrimitive("event"))
                    put("eventName", JsonPrimitive("opened"))
                })
            })
            put("lifecycle", buildJsonObject {
                put("reentry", buildJsonObject { put("type", JsonPrimitive("every_time")) })
                put("exitPolicy", JsonPrimitive("manual"))
            })
        }
        return AuthenticatedRelease(
            keyId = "test",
            descriptorSha256 = "sha",
            identity = identity,
            descriptorBytes = ByteArray(0),
            descriptor = descriptor,
            publishedAtSeqToPromote = null,
        )
    }

    private fun releaseProfile(identity: ExperienceReleaseIdentity) = buildJsonObject {
        put("segments", JsonArray(emptyList()))
        put("features", JsonArray(listOf(buildJsonObject {
            put("id", JsonPrimitive("credits"))
            put("type", JsonPrimitive("metered"))
            put("balance", JsonPrimitive(5.0))
            put("unlimited", JsonPrimitive(false))
        })))
        put("releases", buildJsonObject {
            put("delivery", buildJsonObject {
                put("renderBaseUrl", JsonPrimitive("https://example.test/renders/"))
                put("assetBaseUrl", JsonPrimitive("https://example.test/assets/"))
            })
            put("active", JsonArray(listOf(buildJsonObject {
                put("locator", buildJsonObject {
                    put("appId", JsonPrimitive(identity.appId))
                    put("environment", JsonPrimitive(identity.environment))
                    put("experienceId", JsonPrimitive(identity.experienceId))
                    put("experienceVersionId", JsonPrimitive(identity.experienceVersionId))
                    put("buildId", JsonPrimitive(identity.buildId))
                    put("versionNumber", JsonPrimitive(identity.versionNumber))
                    put("publishedAt", JsonPrimitive(identity.publishedAt))
                    put("publishedAtSeq", JsonPrimitive(identity.publishedAtSeq))
                })
                put("descriptorSha256", JsonPrimitive("sha"))
                put("envelopeBytesBase64", JsonPrimitive("eA=="))
            })))
            put("pinned", JsonArray(emptyList()))
        })
    }

    private fun supportedRuntime() = SupportedRuntime(
        currentSdkVersion = "1.0.0",
        supportedRuntimeRevisions = emptySet(),
        supportedLuauRevisions = emptyMap(),
        sceneFormatMajor = 0,
        sceneFormatMinor = 0,
        timezoneDataRevision = "",
        timezoneDataSha256 = "",
        supportedCapabilities = emptySet(),
    )
}
