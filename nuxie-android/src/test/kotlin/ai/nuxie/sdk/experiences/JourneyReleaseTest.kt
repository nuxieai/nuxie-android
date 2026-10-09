package ai.nuxie.sdk.experiences

import ai.nuxie.sdk.fixtures.FixtureRunner
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.int
import kotlinx.serialization.json.double
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class JourneyReleaseTest {
    @Test fun signedFormQualifiedConditionAuthenticates() {
        val envelope = fixture.getValue("entry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val leg = JsonObject(source.getValue("leg").jsonObject + mapOf(
            "entryStepId" to JsonPrimitive("read-form"),
            "routes" to JsonArray(emptyList()),
            "steps" to Json.parseToJsonElement("""
                [{"kind":"action","id":"read-form","action":{"type":"condition","branches":[{"id":"long","condition":{"type":"Compare","op":">","left":{"type":"Response.Field","form":"onboarding","key":"trip_days"},"right":{"type":"Number","value":14}}}]},"outlets":{"long":"done","default":"done"}},{"kind":"complete","id":"done","outcome":"continue"}]
            """),
        ))
        val root = JsonObject(source + ("leg" to leg))
        val bytes = root.toString().encodeToByteArray()
        val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signature = java.security.Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(JourneyReleaseLimits.SIGNATURE_DOMAIN.encodeToByteArray() + bytes)
            sign()
        }
        val signed = JsonObject(envelope + mapOf(
            "descriptorBytesBase64" to JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)),
            "descriptorSizeBytes" to JsonPrimitive(bytes.size),
            "descriptorSha256" to JsonPrimitive(java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) }),
            "signature" to buildJsonObject {
                put("version", 1); put("algorithm", "ed25519"); put("keyId", "TEST_ONLY_C11")
                put("signatureBase64", Base64.encodeToString(signature, Base64.NO_WRAP))
            },
        )).toString().encodeToByteArray()
        val trusted = mapOf("TEST_ONLY_C11" to pair.public.encoded.takeLast(32).toByteArray())
        val identity = requireNotNull(JourneyReleaseIdentity.fromJson(root.getValue("identity").jsonObject))
        assertArrayEquals(bytes, JourneyReleaseVerifier.authenticate(signed, trusted, identity,
            leg.getValue("id").jsonPrimitive.content, runtime(root), JourneyReleaseReplayPolicy.Active(0)).descriptorBytes)
    }

    @Test fun qualifiedIrFieldAcceptsUnderscoreKey() {
        val condition = Json.parseToJsonElement("""
            {"type":"Compare","op":">","left":{"type":"Response.Field","form":"onboarding","key":"_days"},"right":{"type":"Number","value":14}}
        """)
        DeviceEntryIrSchema.validate(condition)
        ExperiencePolicySchema.validate(buildJsonObject {
            putJsonObject("entry") {
                putJsonObject("trigger") { put("type", "api") }
                putJsonObject("frequency") { put("type", "every_match") }
                putJsonObject("eligibility") { put("ir_version", 1); put("expr", condition) }
            }
            put("exitWhenAny", JsonArray(emptyList()))
        })
    }

    @Test fun qualifiedIrFieldKeepsCanonicalKeysAndLegacyStateRule() {
        fun field(key: String, qualified: Boolean) = buildJsonObject {
            put("type", "Response.Field"); put("key", key)
            if (qualified) put("form", "onboarding")
        }
        for (key in listOf("_", "_days", "days_2")) DeviceEntryIrSchema.validate(field(key, true))
        for (key in listOf("true", "false", "null", "7days", "bad-key", "days\n", "é", "")) {
            assertThrows(JourneyReleaseAuthenticationException::class.java) {
                DeviceEntryIrSchema.validate(field(key, true))
            }
        }
        for (key in listOf("days", "days_2", "true", "false", "null")) DeviceEntryIrSchema.validate(field(key, false))
        for (key in listOf("_days", "_", "7days")) {
            assertThrows(JourneyReleaseAuthenticationException::class.java) {
                DeviceEntryIrSchema.validate(field(key, false))
            }
        }
    }

    @Test fun formSelectorRejectsMalformedNamesAndOtherFieldKinds() {
        fun field(type: String = "Response.Field", form: kotlinx.serialization.json.JsonElement) = buildJsonObject {
            put("type", type); put("key", "trip_days"); put("form", form)
        }
        fun validate(field: JsonObject) = JourneyGrammar.action(buildJsonObject {
            put("type", "send_event"); put("eventName", "trip")
            putJsonObject("payload") { put("days", field) }
        }, emptySet(), emptySet())
        for (form in listOf(JsonNull, JsonPrimitive(12), JsonPrimitive(""), JsonPrimitive("bad-form"), JsonPrimitive("form\n"), JsonPrimitive("é"))) {
            assertThrows(JourneyReleaseAuthenticationException::class.java) { validate(field(form = form)) }
            assertThrows(JourneyReleaseAuthenticationException::class.java) { DeviceEntryIrSchema.validate(field(form = form)) }
        }
        for (type in listOf("Event.Field", "Customer.Field")) {
            assertThrows(JourneyReleaseAuthenticationException::class.java) { validate(field(type, JsonPrimitive("onboarding"))) }
        }
        for (form in listOf("onboarding", "7_form", "true", "x".repeat(257))) {
            validate(field(form = JsonPrimitive(form)))
            DeviceEntryIrSchema.validate(field(form = JsonPrimitive(form)))
        }
    }

    @Test fun `published F5 authenticates exact response policy`() {
        val folder = FixtureRunner.fixturesRoot().resolve("runtime/forms-saves")
        val bytes = folder.resolve("release.json").readBytes()
        val entry = Json.parseToJsonElement(folder.resolve("profile-entry.json").readText()).jsonObject
        val source = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val locator = entry.getValue("locator").jsonObject
        val release = JourneyReleaseVerifier.authenticate(
            entry.getValue("envelope").toString().encodeToByteArray(), keys,
            requireNotNull(JourneyReleaseIdentity.fromJson(JsonObject(locator - "legId"))),
            locator.getValue("legId").jsonPrimitive.content, runtime(source), JourneyReleaseReplayPolicy.Active(0))
        assertArrayEquals(bytes, release.descriptorBytes)
        val expected = Json.parseToJsonElement(folder.resolve("expectations.json").readText())
            .jsonObject.getValue("release").jsonObject
        for (name in listOf("state", "responses", "ruleGroups")) {
            assertEquals(name, expected.getValue(name), release.descriptor.getValue(name))
        }
        val render = release.descriptor.getValue("render").jsonObject.getValue("nux").jsonObject
        val scene = folder.resolve(render.getValue("key").jsonPrimitive.content).readBytes()
        assertArrayEquals(folder.resolve("screen.riv").readBytes(), scene)
        assertEquals(render.getValue("sha256").jsonPrimitive.content,
            java.security.MessageDigest.getInstance("SHA-256").digest(scene).joinToString("") { "%02x".format(it) })
    }

    @Test fun `published F4 authenticates exact version three values`() {
        val folder = FixtureRunner.fixturesRoot().resolve("runtime/run-values")
        val bytes = folder.resolve("release.json").readBytes()
        val entry = Json.parseToJsonElement(folder.resolve("profile-entry.json").readText()).jsonObject
        val source = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val locator = entry.getValue("locator").jsonObject
        val release = JourneyReleaseVerifier.authenticate(
            entry.getValue("envelope").toString().encodeToByteArray(), keys,
            requireNotNull(JourneyReleaseIdentity.fromJson(JsonObject(locator - "legId"))),
            locator.getValue("legId").jsonPrimitive.content, runtime(source), JourneyReleaseReplayPolicy.Active(0))
        assertArrayEquals(bytes, release.descriptorBytes)
        assertEquals(mapOf("trip_days" to "number", "level" to "number"),
            release.descriptor.getValue("state").jsonObject.mapValues { it.value.jsonObject.getValue("type").jsonPrimitive.content })
        assertEquals(JsonObject(emptyMap()), release.descriptor.getValue("responses"))
        assertEquals(JsonArray(emptyList()), release.descriptor.getValue("ruleGroups"))
        assertEquals(2, release.leg.getValue("screens").jsonArray.size)
        val render = release.descriptor.getValue("render").jsonObject.getValue("nux").jsonObject
        val scene = folder.resolve(render.getValue("key").jsonPrimitive.content).readBytes()
        assertArrayEquals(folder.resolve("screen.riv").readBytes(), scene)
        assertEquals(render.getValue("sha256").jsonPrimitive.content,
            java.security.MessageDigest.getInstance("SHA-256").digest(scene).joinToString("") { "%02x".format(it) })
    }

    @Test fun `version three requires native value policy sections`() {
        val envelope = fixture.getValue("entry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val root = JsonObject(source + mapOf(
            "state" to Json.parseToJsonElement("""{"days":{"type":"number"},"goals":{"type":"list","items":{"title":{"type":"string"}}}}"""),
            "responses" to JsonObject(emptyMap()), "ruleGroups" to JsonArray(emptyList()),
        ))
        JourneySchemaValidator.validate(root)
        for (key in listOf("state", "responses", "ruleGroups")) {
            assertThrows(JourneyReleaseAuthenticationException::class.java) { JourneySchemaValidator.validate(JsonObject(root - key)) }
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(JsonObject(root + ("state" to Json.parseToJsonElement("""{"days":{"type":"number","rules":[]}}"""))))
        }
    }

    @Test fun `version three requires native input names and rejects retired identities`() {
        val corpus = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/native-input-admission.json").readText()).jsonObject
        val envelope = fixture.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val render = source.getValue("render").jsonObject
        val removed = corpus.getValue("removedFields").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val inputs = render.getValue("textInputs").jsonArray.map {
            JsonObject(it.jsonObject - removed + ("textInputName" to JsonPrimitive("Email editable value")))
        }
        fun candidate(input: JsonObject = inputs.first(), version: String = "nuxie.journey-release.v3") =
            JsonObject(source + mapOf("schemaVersion" to JsonPrimitive(version),
                "render" to JsonObject(render + ("textInputs" to JsonArray(listOf(input) + inputs.drop(1))))))
        JourneySchemaValidator.validate(candidate())
        for (version in corpus.getValue("retiredVersions").jsonArray) {
            assertThrows(JourneyReleaseAuthenticationException::class.java) {
                JourneySchemaValidator.validate(candidate(version = version.jsonPrimitive.content))
            }
        }
        for (field in removed) assertThrows(field, JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(candidate(JsonObject(inputs.first() + (field to JsonPrimitive("retired")))))
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(candidate(JsonObject(inputs.first() - "textInputName")))
        }
        for (item in corpus.getValue("cases").jsonArray.map { it.jsonObject }) {
            val input = JsonObject(inputs.first() + ("textInputName" to item.getValue("value")))
            if (item.getValue("valid").jsonPrimitive.boolean) JourneySchemaValidator.validate(candidate(input))
            else assertThrows(item.getValue("name").jsonPrimitive.content, JourneyReleaseAuthenticationException::class.java) {
                JourneySchemaValidator.validate(candidate(input))
            }
        }
    }

    @Test fun `version three signatures reject the retired domain`() {
        val envelope = fixture.getValue("entry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val root = JsonObject(source + ("schemaVersion" to JsonPrimitive("nuxie.journey-release.v3")))
        val bytes = root.toString().encodeToByteArray()
        val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val trusted = mapOf("TEST_ONLY_V3" to pair.public.encoded.takeLast(32).toByteArray())
        fun signed(domain: String): ByteArray {
            val signature = java.security.Signature.getInstance("Ed25519").run {
                initSign(pair.private)
                update(domain.encodeToByteArray() + bytes)
                sign()
            }
            return JsonObject(envelope + mapOf(
                "descriptorBytesBase64" to JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)),
                "descriptorSizeBytes" to JsonPrimitive(bytes.size),
                "descriptorSha256" to JsonPrimitive(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes).joinToString("") { "%02x".format(it) }),
                "signature" to buildJsonObject {
                    put("version", 1); put("algorithm", "ed25519"); put("keyId", "TEST_ONLY_V3")
                    put("signatureBase64", Base64.encodeToString(signature, Base64.NO_WRAP))
                },
            )).toString().encodeToByteArray()
        }
        // Literal domains are independent of the production constant.
        assertArrayEquals(bytes, JourneyReleaseEnvelope.authenticate(signed("nuxie.journey-release.v3\u0000"), trusted).descriptorBytes)
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseEnvelope.authenticate(signed("nuxie.journey-release.v2\u0000"), trusted)
        }
    }

    @Test fun `open links allow host dismissal and screenless legs while screen actions do not`() {
        for (entry in listOf("entry", "renderedEntry")) {
            val envelope = fixture.getValue(entry).jsonObject.getValue("envelope").jsonObject
            val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
            val leg = JsonObject(source.getValue("leg").jsonObject + mapOf(
                "entryStepId" to JsonPrimitive("link"),
                "steps" to Json.parseToJsonElement("""[{"kind":"action","id":"link","action":{"type":"open_link","url":{"type":"String","value":"https://example.test"},"target":"in_app"},"outlets":{"next":"done"}},{"kind":"complete","id":"done","outcome":"continue"}]"""),
                "routes" to Json.parseToJsonElement("""[{"host":{"kind":"journey"},"eventName":"host_dismissed","entryStepId":"link"}]""")
            ))
            JourneySchemaValidator.validate(JsonObject(source + ("leg" to leg)))
            val dismiss = Json.parseToJsonElement("""[{"kind":"action","id":"link","action":{"type":"dismiss"},"outlets":{"next":"done"}},{"kind":"complete","id":"done","outcome":"continue"}]""")
            assertThrows(JourneyReleaseAuthenticationException::class.java) {
                JourneySchemaValidator.validate(JsonObject(source + ("leg" to JsonObject(leg + ("steps" to dismiss)))))
            }
            if (entry == "entry") assertThrows(JourneyReleaseAuthenticationException::class.java) {
                JourneySchemaValidator.validate(JsonObject(source + ("leg" to JsonObject(leg + mapOf(
                    "steps" to dismiss, "routes" to JsonArray(emptyList()))))))
            }
        }
    }

    @Test fun `retired release wire version is rejected`() {
        val envelope = fixture.getValue("entry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val retired = JsonObject(source + ("schemaVersion" to JsonPrimitive("nuxie.journey-release.v1")))
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(retired)
        }
    }

    @Test fun `scene admission accepts only the Nuxie format`() {
        val corpus = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/scene-admission.json").readText()).jsonObject
        val envelope = fixture.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        for (value in corpus.getValue("cases").jsonArray) {
            val item = value.jsonObject
            val render = source.getValue("render").jsonObject
            val scene = render.getValue("nux").jsonObject
            val digest = scene.getValue("sha256").jsonPrimitive.content
            val extension = item.getValue("extension").jsonPrimitive.content
            val candidate = JsonObject(source + ("render" to JsonObject(render - "nux" + mapOf(
                "renderer" to item.getValue("renderer"),
                item.getValue("field").jsonPrimitive.content to JsonObject(scene + mapOf(
                    "key" to JsonPrimitive("renders/sha256/$digest.$extension"),
                    "contentType" to item.getValue("contentType"),
                )),
            ))))
            if (item.getValue("valid").jsonPrimitive.boolean) JourneySchemaValidator.validate(candidate)
            else assertThrows(item.getValue("name").jsonPrimitive.content, JourneyReleaseAuthenticationException::class.java) {
                JourneySchemaValidator.validate(candidate)
            }
        }
    }

    @Test fun `video commands match shared wire grammar and runtime operation codes`() {
        val corpus = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/video-actions.json").readText()).jsonObject
        for (item in corpus.getValue("cases").jsonArray) {
            val case = item.jsonObject
            val action = case.getValue("action")
            if (case.getValue("valid").jsonPrimitive.boolean) {
                JourneyGrammar.action(action, emptySet(), emptySet())
                val parsed = JourneyVideoAction.parse(action)
                assertEquals(case.getValue("kind").jsonPrimitive.int, parsed.commandKind)
                assertEquals(case.getValue("value").jsonPrimitive.double, parsed.commandValue, 0.0)
                assertEquals("frame", parsed.artboardId)
                assertEquals("greeting", parsed.viewNodeId)
            } else {
                assertThrows(case.getValue("name").jsonPrimitive.content, Exception::class.java) {
                    JourneyGrammar.action(action, emptySet(), emptySet())
                }
            }
        }
    }
    private val fixture = Json.parseToJsonElement(
        FixtureRunner.fixturesRoot().resolve("journeys/planes/release.json").readText()).jsonObject
    private val keys = mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
        fixture.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP))

    @Test fun `published font fixtures authenticate with explicit sources`() {
        for (name in listOf("rendered-text-input", "rendered-custom-transition", "rendered-semantic-roles")) {
            val directory = FixtureRunner.fixturesRoot().resolve("journeys/$name")
            val entry = Json.parseToJsonElement(directory.resolve("release-entry.json").readText()).jsonObject
            val provenance = Json.parseToJsonElement(directory.resolve("provenance.json").readText()).jsonObject
            val trusted = mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(
                provenance.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP))
            val envelope = entry.getValue("envelope").jsonObject
            val encoded = envelope.toString().encodeToByteArray()
            val bytes = JourneyReleaseEnvelope.authenticate(encoded, trusted).descriptorBytes
            val source = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val identity = requireNotNull(JourneyReleaseIdentity.fromJson(source.getValue("identity").jsonObject))
            val legId = source.getValue("leg").jsonObject.getValue("id").jsonPrimitive.content
            assertArrayEquals(name, bytes, JourneyReleaseVerifier.authenticate(encoded, trusted, identity, legId,
                runtime(source), JourneyReleaseReplayPolicy.Active(0)).descriptorBytes)
            assertEquals(provenance.getValue("descriptorSha256"), envelope.getValue("descriptorSha256"))
            val fonts = source.getValue("render").jsonObject.getValue("assets").jsonArray
                .filter { it.jsonObject.getValue("kind").jsonPrimitive.content == "font" }
            assertEquals("$name must exercise font admission", 1, fonts.size)
            assertEquals(if (name == "rendered-text-input") "system" else "cdn",
                fonts.single().jsonObject.getValue("location").jsonPrimitive.content)
        }
    }

    @Test fun `current SDK authenticates signed System release and unsupported consumers reject it`() {
        val supported = requireNotNull(ai.nuxie.sdk.core.supportedRuntimeForEmbeddedRuntime("native"))
        val corpus = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/system-font-declarations.json").readText()).jsonObject
        val capabilities = corpus.getValue("consumerCapabilities").jsonArray
        val envelope = fixture.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val luau = supported.supportedLuauRevisions.entries.single()
        val requirements = buildJsonObject {
            put("minimumSdkVersion", supported.currentSdkVersion)
            put("runtimeRevision", supported.supportedRuntimeRevisions.single())
            put("luau", buildJsonObject {
                put("revision", luau.key)
                put("bytecodeVersions", JsonArray(luau.value.sorted().map(::JsonPrimitive)))
            })
            put("sceneFormat", buildJsonObject {
                put("major", supported.sceneFormatMajor); put("minor", supported.sceneFormatMinor)
            })
            put("timezoneData", buildJsonObject {
                put("format", "iana-tzdb"); put("revision", supported.timezoneDataRevision)
                put("sha256", supported.timezoneDataSha256)
            })
            put("requiredCapabilities", capabilities)
        }
        val root = JsonObject(source + mapOf(
            "requirements" to requirements,
            "render" to JsonObject(source.getValue("render").jsonObject +
                ("assets" to corpus.getValue("cases").jsonArray.first().jsonObject.getValue("assets"))),
        ))
        val bytes = root.toString().encodeToByteArray()
        val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val signature = java.security.Signature.getInstance("Ed25519").run {
            initSign(pair.private)
            update(JourneyReleaseLimits.SIGNATURE_DOMAIN.encodeToByteArray() + bytes)
            sign()
        }
        val signed = JsonObject(envelope + mapOf(
            "descriptorBytesBase64" to JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP)),
            "descriptorSizeBytes" to JsonPrimitive(bytes.size),
            "descriptorSha256" to JsonPrimitive(java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) }),
            "signature" to buildJsonObject {
                put("version", 1); put("algorithm", "ed25519"); put("keyId", "TEST_ONLY_SYSTEM_FONT")
                put("signatureBase64", Base64.encodeToString(signature, Base64.NO_WRAP))
            },
        )).toString().encodeToByteArray()
        val keys = mapOf("TEST_ONLY_SYSTEM_FONT" to pair.public.encoded.takeLast(32).toByteArray())
        val identity = requireNotNull(JourneyReleaseIdentity.fromJson(root.getValue("identity").jsonObject))
        val legId = root.getValue("leg").jsonObject.getValue("id").jsonPrimitive.content
        assertArrayEquals(bytes, JourneyReleaseVerifier.authenticate(signed, keys, identity, legId,
            supported, JourneyReleaseReplayPolicy.Active(0)).descriptorBytes)
        val error = assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseVerifier.authenticate(signed, keys, identity, legId,
                supported.copy(supportedCapabilities = supported.supportedCapabilities - "system-fonts"),
                JourneyReleaseReplayPolicy.Active(0))
        }
        assertEquals("unsupported capabilities: [system-fonts]", error.message)
    }

    @Test fun `shared System font declarations preserve the source contract`() {
        val corpus = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/system-font-declarations.json").readText()).jsonObject
        val envelope = fixture.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        for (entry in corpus.getValue("cases").jsonArray) {
            val case = entry.jsonObject
            val root = JsonObject(source + mapOf(
                "render" to JsonObject(source.getValue("render").jsonObject + ("assets" to case.getValue("assets"))),
                "requirements" to JsonObject(source.getValue("requirements").jsonObject +
                    ("requiredCapabilities" to case.getValue("requiredCapabilities"))),
            ))
            if (case.getValue("valid").jsonPrimitive.content == "true") {
                JourneyReleaseSchema.validate(root)
            } else {
                assertThrows(case.getValue("name").jsonPrimitive.content, JourneyReleaseAuthenticationException::class.java) {
                    JourneyReleaseSchema.validate(root)
                }
            }
        }
    }

    @Test fun `shared video admission cases match publisher and Apple contract`() {
        val corpus = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/video-admission.json").readText()).jsonObject
        val envelope = fixture.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        for (item in corpus.getValue("cases").jsonArray) {
            val case = item.jsonObject
            val renderer = case["renderer"]?.jsonPrimitive?.content ?: "nux"
            val sceneField = if (renderer == "nux") "nux" else "riv"
            val oldRender = source.getValue("render").jsonObject
            val oldScene = oldRender.getValue("nux").jsonObject
            val digest = oldScene.getValue("sha256").jsonPrimitive.content
            val scene = JsonObject(oldScene + mapOf(
                "key" to JsonPrimitive("renders/sha256/$digest.$sceneField"),
                "contentType" to JsonPrimitive(if (renderer == "nux") "application/vnd.nuxie.scene" else "application/vnd.rive"),
            ))
            val asset = JsonObject(corpus.getValue("videoAsset").jsonObject + (case["assetPatch"]?.jsonObject ?: emptyMap()))
            val render = JsonObject(oldRender - "nux" + mapOf(
                "renderer" to JsonPrimitive(renderer), sceneField to scene,
                "assets" to JsonArray(listOf(asset) + (case["additionalAssets"]?.jsonArray ?: emptyList())),
            ) + (case["videoElements"]?.let { mapOf("videoElements" to it) } ?: emptyMap()))
            val requirements = JsonObject(source.getValue("requirements").jsonObject +
                ("requiredCapabilities" to (case["capabilities"] ?: JsonArray(listOf(JsonPrimitive("video.playback.v1"))))))
            var root = JsonObject(source + mapOf("render" to render, "requirements" to requirements))
            case["action"]?.let { action ->
                val leg = root.getValue("leg").jsonObject
                val steps = leg.getValue("steps").jsonArray.toMutableList()
                val index = steps.indexOfFirst { it.jsonObject["kind"]?.jsonPrimitive?.content == "action" }
                steps[index] = JsonObject(steps[index].jsonObject + ("action" to action))
                root = JsonObject(root + ("leg" to JsonObject(leg + ("steps" to JsonArray(steps)))))
            }
            val name = case.getValue("name").jsonPrimitive.content
            if (case.getValue("valid").jsonPrimitive.content == "true") {
                try { JourneySchemaValidator.validate(root) } catch (error: Exception) { throw AssertionError(name, error) }
            } else {
                assertThrows(name, JourneyReleaseAuthenticationException::class.java) { JourneySchemaValidator.validate(root) }
            }
        }
    }

    @Test fun `signed behavior ordering matches the wire contract`() {
        val corpus = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/behavior-ordering.json").readText()).jsonObject
        val trusted = mapOf("TEST_ONLY_DEV_KEYPAIR" to Base64.decode(corpus.getValue("publicKeyBase64").jsonPrimitive.content, Base64.NO_WRAP))
        for (item in corpus.getValue("cases").jsonArray) {
            val case = item.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val entry = case.getValue("entry").jsonObject
            val envelope = entry.getValue("envelope").jsonObject.toString().encodeToByteArray()
            // Prove every malformed case has an authentic signature before testing schema admission.
            val bytes = JourneyReleaseEnvelope.authenticate(envelope, trusted).descriptorBytes
            val source = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val identity = requireNotNull(JourneyReleaseIdentity.fromJson(source.getValue("identity").jsonObject))
            val legId = source.getValue("leg").jsonObject.getValue("id").jsonPrimitive.content
            fun authenticate() = JourneyReleaseVerifier.authenticate(envelope, trusted, identity, legId,
                runtime(source), JourneyReleaseReplayPolicy.Active(0))
            if (case.getValue("valid").jsonPrimitive.content == "true") {
                assertArrayEquals(name, bytes, authenticate().descriptorBytes)
            } else {
                assertThrows(name, JourneyReleaseAuthenticationException::class.java) { authenticate() }
            }
        }
    }

    @Test fun `shared admission cases reject invalid local programs before execution`() {
        val cases = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/admission.json").readText()).jsonObject.getValue("cases").jsonArray
        for (item in cases) {
            val case = item.jsonObject
            val entry = fixture.getValue(case.getValue("entry").jsonPrimitive.content).jsonObject
            val envelope = entry.getValue("envelope").jsonObject
            val bytes = Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP)
            val source = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val leg = JsonObject(source.getValue("leg").jsonObject + case.getValue("leg").jsonObject)
            val root = JsonObject(source + ("leg" to leg) + case.getValue("descriptor").jsonObject)
            val name = case.getValue("name").jsonPrimitive.content
            if (case.getValue("valid").jsonPrimitive.content == "true") {
                try { JourneySchemaValidator.validate(root) } catch (error: Exception) { throw AssertionError(name, error) }
            } else {
                assertThrows(name, JourneyReleaseAuthenticationException::class.java) { JourneySchemaValidator.validate(root) }
            }
        }
    }

    @Test fun `shared native line height admission accepts only natural or positive bounded values`() {
        val typography = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/text-input-typography.json").readText()).jsonObject
        val navigation = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/text-input-navigation.json").readText()).jsonObject
        val envelope = navigation.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        for (item in typography.getValue("lineHeightAdmission").jsonArray) {
            val case = item.jsonObject
            val render = source.getValue("render").jsonObject
            val inputs = render.getValue("textInputs").jsonArray.toMutableList()
            val input = inputs.first().jsonObject
            val style = JsonObject(input.getValue("style").jsonObject + ("lineHeight" to case.getValue("value")))
            inputs[0] = JsonObject(input + ("style" to style))
            val root = JsonObject(source + ("render" to JsonObject(render +
                ("textInputs" to kotlinx.serialization.json.JsonArray(inputs)))))
            val name = case.getValue("name").jsonPrimitive.content
            if (case.getValue("valid").jsonPrimitive.content == "true") {
                try { JourneySchemaValidator.validate(root) } catch (error: Exception) { throw AssertionError(name, error) }
            } else {
                assertThrows(name, JourneyReleaseAuthenticationException::class.java) { JourneySchemaValidator.validate(root) }
            }
        }
    }

    @Test fun `text response capture admission requires an explicit safe binding source`() {
        val navigation = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/text-input-navigation.json").readText()).jsonObject
        val envelope = navigation.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        for (mode in listOf("text", "binding", "invalid")) {
            for (secure in listOf(false, true)) {
                for (field in listOf(false, true)) {
                    val render = source.getValue("render").jsonObject
                    val inputs = render.getValue("textInputs").jsonArray.toMutableList()
                    val input = inputs.first().jsonObject.toMutableMap()
                    input["responseCapture"] = JsonPrimitive(mode)
                    input["secureTextEntry"] = JsonPrimitive(secure)
                    if (field) input["responseFieldKey"] = JsonPrimitive("answer") else input.remove("responseFieldKey")
                    inputs[0] = JsonObject(input)
                    val root = JsonObject(source + ("render" to JsonObject(render + ("textInputs" to JsonArray(inputs)))))
                    val valid = field && (mode == "text" || mode == "binding")
                    if (valid) JourneySchemaValidator.validate(root)
                    else assertThrows("$mode secure=$secure field=$field", JourneyReleaseAuthenticationException::class.java) {
                        JourneySchemaValidator.validate(root)
                    }
                }
            }
        }
    }

    @Test fun `native editable input admission validates endpoint and completion fields strictly`() {
        val navigation = Json.parseToJsonElement(FixtureRunner.fixturesRoot()
            .resolve("journeys/planes/text-input-navigation.json").readText()).jsonObject
        val envelope = navigation.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val render = source.getValue("render").jsonObject
        fun validate(fields: Map<String, kotlinx.serialization.json.JsonElement>) {
            val inputs = render.getValue("textInputs").jsonArray.toMutableList()
            inputs[0] = JsonObject(inputs.first().jsonObject + fields)
            JourneySchemaValidator.validate(JsonObject(source + ("render" to JsonObject(render +
                ("textInputs" to JsonArray(inputs))))))
        }
        validate(emptyMap())
        for (event in listOf("editing-ended", "return")) {
            validate(mapOf("textInputName" to JsonPrimitive("duration-input"),
                "actionEvent" to JsonPrimitive(event), "declarativeActionId" to JsonPrimitive("finish-input")))
        }
        validate(mapOf("textInputName" to JsonPrimitive("x".repeat(256))))
        for (key in listOf("textInputName", "actionEvent", "declarativeActionId")) {
            for (invalid in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(false), JsonPrimitive(""), JsonArray(emptyList()))) {
                assertThrows("$key=$invalid", JourneyReleaseAuthenticationException::class.java) {
                    validate(mapOf(key to invalid))
                }
            }
        }
        for ((key, invalid) in listOf("textInputName" to "x".repeat(257),
            "actionEvent" to "change", "unknownInputField" to "value")) {
            assertThrows(key, JourneyReleaseAuthenticationException::class.java) {
                validate(mapOf(key to JsonPrimitive(invalid)))
            }
        }
    }

    @Test fun `admits signed local programs with and without a render closure`() {
        for (key in listOf("entry", "renderedEntry")) {
            val entry = fixture.getValue(key).jsonObject
            val envelope = entry.getValue("envelope").jsonObject
            val bytes = Base64.decode(envelope.getValue("descriptorBytesBase64").jsonPrimitive.content, Base64.NO_WRAP)
            val source = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            val identity = requireNotNull(
                JourneyReleaseIdentity.fromJson(
                    entry.getValue("locator").jsonObject,
                    setOf("legId"),
                ),
            )
            val release = JourneyReleaseVerifier.authenticate(envelope.toString().encodeToByteArray(), keys,
                identity, "a".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Active(0))
            assertArrayEquals(bytes, release.descriptorBytes)
            assertEquals(identity.publishedAtSeq, release.publishedAtSeqToPromote)
            assertEquals("a".repeat(64), release.leg.getValue("id").jsonPrimitive.content)
            if (key == "entry") assertEquals(JsonNull, release.descriptor["render"])
            val pinned = JourneyReleaseVerifier.authenticate(envelope.toString().encodeToByteArray(), keys,
                identity, "a".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Pinned(identity.experienceVersionId,
                    identity.buildId, release.descriptorSha256))
            assertNull(pinned.publishedAtSeqToPromote)
        }
    }

    @Test fun `release admission rejects unsafe artifact paths and mismatched script exports`() {
        val envelope = fixture.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val render = source.getValue("render").jsonObject
        val riv = JsonObject(render.getValue("nux").jsonObject + ("key" to JsonPrimitive("../outside.riv")))
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(JsonObject(source + ("render" to JsonObject(render + ("nux" to riv)))))
        }
        val invalid = source.toString().replace("\"kind\":\"declarative\"", "\"kind\":\"script\"")
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(Json.parseToJsonElement(invalid).jsonObject)
        }
    }

    @Test fun `authenticated identity leg and replay policies cannot be substituted`() {
        val envelope = fixture.getValue("entry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val identity = requireNotNull(JourneyReleaseIdentity.fromJson(source.getValue("identity").jsonObject))
        val bytes = envelope.toString().encodeToByteArray()
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseVerifier.authenticate(bytes, keys, identity.copy(experienceId = "other"), "a".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Active(0))
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseVerifier.authenticate(bytes, keys, identity, "b".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Active(0))
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseVerifier.authenticate(bytes, keys, identity, "a".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Active(identity.publishedAtSeq + 1))
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyReleaseVerifier.authenticate(bytes, keys, identity, "a".repeat(64), runtime(source), JourneyReleaseReplayPolicy.Pinned(identity.experienceVersionId, identity.buildId, "b".repeat(64)))
        }
    }

    @Test fun `shared script bytes count once and field limits use the canonical byte units`() {
        val envelope = fixture.getValue("renderedEntry").jsonObject.getValue("envelope").jsonObject
        val source = Json.parseToJsonElement(Base64.decode(envelope.getValue("descriptorBytesBase64")
            .jsonPrimitive.content, Base64.NO_WRAP).decodeToString()).jsonObject
        val leg = source.getValue("leg").jsonObject
        val render = source.getValue("render").jsonObject
        val legScreen = leg.getValue("screens").jsonArray.single().jsonObject
        val renderScreen = render.getValue("screens").jsonArray.single().jsonObject
        val names = listOf("screen_welcome", "screen_2", "screen_3", "screen_4", "screen_5").sorted()
        val scripts = names.map { name -> Json.parseToJsonElement("""{
            "screenId":"$name", "controls":[{"actionId":"continue", "behavior":{"kind":"script"}}],
            "script":{"protocol":"screen-actions", "exportedActionIds":["continue"],
              "artifact":{"key":"screen-behavior/sha256/${"b".repeat(64)}.bin", "sha256":"${"b".repeat(64)}",
                          "sizeBytes":4194304, "contentType":"application/octet-stream"}}
        }""") }
        JourneySchemaValidator.validate(JsonObject(source + mapOf(
            "leg" to JsonObject(leg + ("screens" to JsonArray(names.map { JsonObject(legScreen + ("id" to JsonPrimitive(it))) }))),
            "render" to JsonObject(render + ("screens" to JsonArray(names.map { JsonObject(renderScreen + ("id" to JsonPrimitive(it))) }))),
            "screenBehaviors" to JsonArray(scripts),
        )))
        val longCapture = JsonObject(legScreen + ("responseCaptures" to JsonArray(listOf(JsonPrimitive("x".repeat(129))))))
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(JsonObject(source + ("leg" to JsonObject(leg + ("screens" to JsonArray(listOf(longCapture)))))))
        }
        val steps = leg.getValue("steps").jsonArray.toMutableList()
        steps[0] = JsonObject(steps[0].jsonObject + ("action" to buildJsonObject {
            put("type", "send_event"); put("eventName", "é".repeat(200))
        }))
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneySchemaValidator.validate(JsonObject(source + ("leg" to JsonObject(leg + ("steps" to JsonArray(steps))))))
        }
    }

    @Test fun `experiment actions require an authored available fallback`() {
        fun action(fallback: String?) = buildJsonObject {
            put("type", "experiment")
            put("experimentId", "checkout-copy")
            fallback?.let { put("fallbackVariantId", it) }
            put(
                "variants",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", "control")
                            put("isHoldout", false)
                        },
                        buildJsonObject {
                            put("id", "treatment")
                            put("isHoldout", false)
                        },
                    ),
                ),
            )
        }

        JourneyGrammar.action(action("control"), emptySet(), emptySet())
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyGrammar.action(action(null), emptySet(), emptySet())
        }
        assertThrows(JourneyReleaseAuthenticationException::class.java) {
            JourneyGrammar.action(action("missing"), emptySet(), emptySet())
        }
    }

    private fun runtime(source: JsonObject): JourneyReleaseSupportedRuntime {
        val requirements = source["requirements"] as? JsonObject
            ?: return JourneyReleaseSupportedRuntime("0.1.0", emptySet(), emptyMap(), 1, 0, "unused", "unused", emptySet())
        fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
        val luau = requirements.getValue("luau").jsonObject
        val scene = requirements.getValue("sceneFormat").jsonObject
        val timezone = requirements.getValue("timezoneData").jsonObject
        return JourneyReleaseSupportedRuntime(requirements.string("minimumSdkVersion"), setOf(requirements.string("runtimeRevision")),
            mapOf(luau.string("revision") to luau.getValue("bytecodeVersions").jsonArray.map { it.jsonPrimitive.int }.toSet()),
            scene.getValue("major").jsonPrimitive.int, scene.getValue("minor").jsonPrimitive.int,
            timezone.string("revision"), timezone.string("sha256"),
            requirements.getValue("requiredCapabilities").jsonArray.map { it.jsonPrimitive.content }.toSet())
    }
}
