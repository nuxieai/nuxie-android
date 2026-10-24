package ai.nuxie.sdk.fixtures

import kotlinx.serialization.json.*

/** Explicit test-only migration of old UI fixtures. Signed admission tests use the original bytes. */
internal fun pageDescriptorFixture(value: JsonObject): JsonObject {
    fun migrate(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map(::migrate))
        is JsonObject -> JsonObject(value.filterKeys { it != "incomingOnTop" && it != "layerHost" }.mapValues { (key, child) ->
            when {
                key == "kind" && child is JsonPrimitive && child.content in setOf("layer", "choreographed") -> JsonPrimitive("page")
                key == "type" && child == JsonPrimitive("custom") -> JsonPrimitive("magic-move")
                key == "reverse" && child is JsonObject && "durationMs" !in child ->
                    migrate(JsonObject(child + ("durationMs" to value.getValue("durationMs"))))
                else -> migrate(child)
            }
        })
        else -> value
    }
    return migrate(value).jsonObject
}

/** Test-generated signature for migrated page metadata; never substitutes for original-signature checks. */
internal fun signPageDescriptorFixture(envelope: JsonObject, descriptor: JsonObject,
    pair: java.security.KeyPair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair(),
    keyId: String = "TEST_ONLY_PAGE"): Pair<JsonObject, Map<String, ByteArray>> {
    val bytes = descriptor.toString().encodeToByteArray()
    val signature = java.security.Signature.getInstance("Ed25519").run {
        initSign(pair.private)
        update(ai.nuxie.sdk.experiences.JourneyReleaseLimits.SIGNATURE_DOMAIN.encodeToByteArray() + bytes)
        sign()
    }
    val encoder = java.util.Base64.getEncoder()
    val signed = JsonObject(envelope + mapOf(
        "descriptorBytesBase64" to JsonPrimitive(encoder.encodeToString(bytes)),
        "descriptorSizeBytes" to JsonPrimitive(bytes.size),
        "descriptorSha256" to JsonPrimitive(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }),
        "signature" to buildJsonObject {
            put("version", 1); put("algorithm", "ed25519"); put("keyId", keyId)
            put("signatureBase64", encoder.encodeToString(signature))
        },
    ))
    return signed to mapOf(keyId to pair.public.encoded.takeLast(32).toByteArray())
}

/** Journey integration fixtures still pass production signature and schema admission. */
internal fun pageReleaseCorpusFixture(corpus: JsonObject): JsonObject {
    val decoder = java.util.Base64.getDecoder()
    val originalKeys = mapOf("TEST_ONLY_DEV_KEYPAIR" to decoder.decode(corpus.getValue("publicKeyBase64").jsonPrimitive.content))
    val pair = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val entries = listOf("entry", "renderedEntry").associateWith { name ->
        val entry = corpus.getValue(name).jsonObject
        val envelope = entry.getValue("envelope").jsonObject
        val original = ai.nuxie.sdk.experiences.JourneyReleaseEnvelope.authenticate(envelope.toString().encodeToByteArray(), originalKeys)
        val descriptor = pageDescriptorFixture(Json.parseToJsonElement(original.descriptorBytes.decodeToString()).jsonObject)
        JsonObject(entry + ("envelope" to signPageDescriptorFixture(envelope, descriptor, pair, "TEST_ONLY_DEV_KEYPAIR").first))
    }
    return JsonObject(corpus + entries + ("publicKeyBase64" to JsonPrimitive(
        java.util.Base64.getEncoder().encodeToString(pair.public.encoded.takeLast(32).toByteArray()))))
}
