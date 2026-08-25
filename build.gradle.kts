import groovy.json.JsonSlurper
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.binary.compatibility.validator)
}

group = "ai.nuxie"
version = "0.1.0-SNAPSHOT"

apiValidation {
  ignoredProjects.add("example-app")
}

subprojects {
  group = rootProject.group
  version = rootProject.version
}

val runtimeDirectory = layout.projectDirectory.dir("runtime").asFile
val runtimeArtifactPin = runtimeDirectory.resolve("artifact.json")
val runtimeSizeBudget = runtimeDirectory.resolve("size-budget.json")
val runtimePrebuilt = runtimeDirectory.resolve("prebuilt")
val runtimeChecksumManifest = runtimePrebuilt.resolve(".artifact-checksum")
val requiredRuntimeArtifacts = setOf(
  "include/nux_capi.generated.h",
  "jniLibs/arm64-v8a/libc++_shared.so",
  "jniLibs/arm64-v8a/libnux_capi.so",
  "jniLibs/x86_64/libc++_shared.so",
  "jniLibs/x86_64/libnux_capi.so",
)

fun readJsonObject(file: File): Map<*, *> {
  val value = runCatching { JsonSlurper().parse(file) }
    .getOrElse { error -> throw GradleException("Could not read ${file.relativeTo(rootDir)}: ${error.message}", error) }
  return value as? Map<*, *>
    ?: throw GradleException("${file.relativeTo(rootDir)} must contain a JSON object.")
}

fun requiredString(json: Map<*, *>, field: String, file: File): String =
  (json[field] as? String)?.takeIf { it.isNotBlank() }
    ?: throw GradleException("${file.relativeTo(rootDir)} must contain a non-empty '$field' string.")

fun sha256(file: Path): String {
  val digest = MessageDigest.getInstance("SHA-256")
  Files.newInputStream(file).buffered().use { input ->
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    while (true) {
      val count = input.read(buffer)
      if (count < 0) break
      digest.update(buffer, 0, count)
    }
  }
  return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

fun deleteRecursively(path: Path) {
  if (!Files.exists(path)) return
  Files.walk(path).use { paths ->
    paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
  }
}

fun moveDirectory(source: Path, destination: Path) {
  try {
    Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
  } catch (_: AtomicMoveNotSupportedException) {
    Files.move(source, destination)
  }
}

fun runtimeArtifactFiles(directory: Path): Set<String> {
  if (!Files.isDirectory(directory)) return emptySet()
  return Files.walk(directory).use { paths ->
    paths
      .filter(Files::isRegularFile)
      .map { directory.relativize(it).toString().replace(File.separatorChar, '/') }
      .filter { it != ".artifact-checksum" }
      .toList()
      .toSet()
  }
}

fun runtimeArtifactSize(directory: Path): Long = requiredRuntimeArtifacts.sumOf { relativePath ->
  val artifact = directory.resolve(relativePath)
  if (!Files.isRegularFile(artifact)) {
    throw GradleException("Runtime artifact is missing $relativePath.")
  }
  Files.size(artifact)
}

fun verifyArtifactSet(directory: Path, maximumBytes: Long) {
  val actualFiles = runtimeArtifactFiles(directory)
  if (actualFiles != requiredRuntimeArtifacts) {
    val missing = (requiredRuntimeArtifacts - actualFiles).sorted()
    val unexpected = (actualFiles - requiredRuntimeArtifacts).sorted()
    val details = buildList {
      if (missing.isNotEmpty()) add("missing: ${missing.joinToString()}")
      if (unexpected.isNotEmpty()) add("unexpected: ${unexpected.joinToString()}")
    }.joinToString("; ")
    throw GradleException("Runtime artifact set is invalid ($details).")
  }

  val extractedBytes = runtimeArtifactSize(directory)
  if (extractedBytes > maximumBytes) {
    throw GradleException(
      "Runtime artifact size $extractedBytes bytes exceeds the committed budget of $maximumBytes bytes.",
    )
  }
}

val runtimeFetch by tasks.registering {
  group = "runtime"
  description = "Fetches and verifies the pinned Nuxie runtime artifact."
  inputs.file(runtimeArtifactPin)
  inputs.file(runtimeSizeBudget)
  // Deliberately do not declare prebuilt/ as a Gradle output: Gradle creates
  // declared output directories before task actions, which would leave an
  // empty prebuilt/ after a checksum failure. The local checksum manifest is
  // the task's cheap freshness check instead.

  doLast {
    when (val localSelection = providers.environmentVariable("NUXIE_RUNTIME_USE_LOCAL").orNull) {
      "1" -> {
        if (!runtimePrebuilt.isDirectory) {
          throw GradleException(
            "NUXIE_RUNTIME_USE_LOCAL=1 requires runtime/prebuilt/. " +
              "Run scripts/stage-runtime.sh <path-to-nuxie-runtime-checkout> first.",
          )
        }
        logger.lifecycle("Using locally staged Nuxie runtime from runtime/prebuilt/.")
        return@doLast
      }
      null -> Unit
      else -> throw GradleException("NUXIE_RUNTIME_USE_LOCAL must be unset or 1 (was '$localSelection').")
    }

    val pin = readJsonObject(runtimeArtifactPin)
    val release = requiredString(pin, "release", runtimeArtifactPin)
    val url = requiredString(pin, "url", runtimeArtifactPin)
    val expectedChecksum = requiredString(pin, "checksum", runtimeArtifactPin)
    if (!expectedChecksum.matches(Regex("[0-9a-f]{64}"))) {
      throw GradleException("runtime/artifact.json checksum must be a lowercase SHA-256 digest.")
    }

    val budget = readJsonObject(runtimeSizeBudget)
    val maximumBytes = (budget["maximumBytes"] as? Number)?.toLong()?.takeIf { it > 0L }
      ?: throw GradleException("runtime/size-budget.json must contain a positive 'maximumBytes' number.")

    val recordedChecksum = runtimeChecksumManifest.takeIf(File::isFile)?.readText()?.trim()
    if (recordedChecksum == expectedChecksum) {
      verifyArtifactSet(runtimePrebuilt.toPath(), maximumBytes)
      logger.lifecycle("Using verified Nuxie runtime $release from runtime/prebuilt/.")
      return@doLast
    }

    val token = UUID.randomUUID().toString()
    val downloadedZip = runtimeDirectory.resolve(".runtime-$token.zip").toPath()
    val extractedDirectory = runtimeDirectory.resolve(".prebuilt-$token.tmp").toPath()
    val backupDirectory = runtimeDirectory.resolve(".prebuilt-$token.backup").toPath()

    try {
      logger.lifecycle("Downloading pinned Nuxie runtime $release.")
      URI(url).toURL().openConnection().apply {
        connectTimeout = 30_000
        readTimeout = 60_000
      }.getInputStream().buffered().use { input ->
        Files.newOutputStream(downloadedZip).buffered().use(input::copyTo)
      }

      val actualChecksum = sha256(downloadedZip)
      if (actualChecksum != expectedChecksum) {
        throw GradleException(
          "Nuxie runtime checksum mismatch for $release: expected $expectedChecksum, got $actualChecksum. " +
            "The archive was not extracted.",
        )
      }

      Files.createDirectories(extractedDirectory)
      ZipInputStream(Files.newInputStream(downloadedZip).buffered()).use { archive ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
          val entry = archive.nextEntry ?: break
          if (entry.name.contains('\\')) {
            throw GradleException("Runtime archive contains an invalid path: ${entry.name}")
          }
          val destination = extractedDirectory.resolve(entry.name).normalize()
          if (!destination.startsWith(extractedDirectory)) {
            throw GradleException("Runtime archive entry escapes the extraction directory: ${entry.name}")
          }
          if (entry.isDirectory) {
            Files.createDirectories(destination)
          } else {
            Files.createDirectories(destination.parent)
            Files.newOutputStream(destination).buffered().use { output ->
              while (true) {
                val count = archive.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
              }
            }
          }
          archive.closeEntry()
        }
      }

      verifyArtifactSet(extractedDirectory, maximumBytes)
      Files.writeString(extractedDirectory.resolve(".artifact-checksum"), "$expectedChecksum\n")

      val prebuiltPath = runtimePrebuilt.toPath()
      if (Files.exists(prebuiltPath)) moveDirectory(prebuiltPath, backupDirectory)
      try {
        moveDirectory(extractedDirectory, prebuiltPath)
      } catch (error: Exception) {
        if (Files.exists(backupDirectory) && !Files.exists(prebuiltPath)) {
          moveDirectory(backupDirectory, prebuiltPath)
        }
        throw error
      }
      deleteRecursively(backupDirectory)
      logger.lifecycle(
        "Verified and extracted Nuxie runtime $release " +
          "(${runtimeArtifactSize(prebuiltPath)} / $maximumBytes bytes).",
      )
    } finally {
      Files.deleteIfExists(downloadedZip)
      deleteRecursively(extractedDirectory)
      // A backup is removed on successful install (or consumed by a
      // successful restore). If restoration itself fails, leave it in place
      // so the previous prebuilt remains recoverable.
    }
  }
}

val runtimeBoundary by tasks.registering {
  group = "verification"
  description = "Checks that direct NuxieRuntimeBridge callers stay inside the runtime boundary."
  val sources = fileTree("nuxie-android/src") {
    include("**/*.kt", "**/*.java")
  }
  inputs.files(sources)

  doLast {
    // Presentation is the current surface host. Shrinking this allowlist is
    // the goal as the typed runtime layer grows.
    val allowedPackages = setOf(
      "ai.nuxie.sdk.runtime",
      "ai.nuxie.sdk.presentation",
    )
    val packagePattern = Regex("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)")
    val referencePattern = Regex("\\bNuxieRuntimeBridge\\b")
    val violations = sources.files.sortedBy { it.relativeTo(rootDir).path }.mapNotNull { source ->
      val text = source.readText()
      if (!referencePattern.containsMatchIn(text)) return@mapNotNull null
      val packageName = packagePattern.find(text)?.groupValues?.get(1).orEmpty()
      val allowed = allowedPackages.any { packageName == it || packageName.startsWith("$it.") }
      if (allowed) return@mapNotNull null
      val lines = text.lineSequence().mapIndexedNotNull { index, line ->
        (index + 1).takeIf { referencePattern.containsMatchIn(line) }
      }.toList()
      "${source.relativeTo(rootDir)}:${lines.joinToString()} (package '$packageName')"
    }

    if (violations.isNotEmpty()) {
      throw GradleException(
        "Direct NuxieRuntimeBridge references are outside the runtime boundary:\n" +
          violations.joinToString("\n") { "  $it" },
      )
    }
  }
}

project(":runtime") {
  tasks.register("fetch") {
    group = "runtime"
    description = "Fetches and verifies the pinned Nuxie runtime artifact."
    dependsOn(runtimeFetch)
  }
  tasks.register("boundary") {
    group = "verification"
    description = "Checks that direct NuxieRuntimeBridge callers stay inside the runtime boundary."
    dependsOn(runtimeBoundary)
  }
}

project(":nuxie-android") {
  tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(project(":runtime").tasks.named("fetch"))
  }
  tasks.matching { it.name == "check" }.configureEach {
    dependsOn(project(":runtime").tasks.named("boundary"))
  }
}
