package ai.nuxie.sdk.fixtures

import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Loads a versioned fixture suite and delegates each vector to an adapter. */
internal object FixtureRunner {
    data class Vector(
        val suite: String,
        val name: String,
        val body: JsonObject,
    )

    fun run(
        relativePath: String,
        expectedSuite: String,
        supportedVersion: Int = 1,
        assertVector: (Vector) -> Unit,
    ) {
        val fixture = fixtureRoot().resolve(relativePath).normalize()
        require(fixture.startsWith(fixtureRoot())) { "Fixture path escapes the fixtures directory." }
        val fixtureText = String(Files.readAllBytes(fixture), StandardCharsets.UTF_8)
        val root = Json.parseToJsonElement(fixtureText).jsonObject
        val suite = root.getValue("suite").jsonPrimitive.content
        val version = root.getValue("version").jsonPrimitive.int
        require(suite == expectedSuite) {
            "Expected fixture suite '$expectedSuite', found '$suite'."
        }
        require(version == supportedVersion) {
            "Unsupported fixture version $version in $suite (runner supports $supportedVersion)."
        }

        root.getValue("vectors").jsonArray.forEach { element ->
            val body = element.jsonObject
            val name = body.getValue("name").jsonPrimitive.content
            try {
                assertVector(Vector(suite = suite, name = name, body = body))
            } catch (failure: AssertionError) {
                throw AssertionError("Fixture vector '$suite/$name' failed: ${failure.message}", failure)
            }
        }
    }

    /** Exposed for suites that read fixture files directly. */
    fun fixturesRoot(): java.io.File = fixtureRoot().toFile()

    private fun fixtureRoot(): Path {
        var directory: Path? = Path.of("").toAbsolutePath().normalize()
        while (directory != null) {
            val candidate = directory.resolve("fixtures")
            if (Files.isDirectory(candidate)) return candidate
            directory = directory.parent
        }
        error("Could not find the repository fixtures directory from ${Path.of("").toAbsolutePath()}.")
    }
}
