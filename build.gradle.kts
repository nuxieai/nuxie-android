import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import java.util.UUID
import java.util.zip.ZipInputStream

plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.binary.compatibility.validator)
}

group = "ai.nuxie"
version = "0.1.0"

val androidToolchainFile = layout.projectDirectory.file("runtime/android-toolchain.properties").asFile
val androidToolchain = Properties().apply {
  androidToolchainFile.inputStream().use(::load)
}
fun requiredToolchainVersion(name: String): String =
  androidToolchain.getProperty(name)?.takeIf(String::isNotBlank)
    ?: throw GradleException("runtime/android-toolchain.properties must contain a non-empty '$name'.")

extra["nuxieNdkVersion"] = requiredToolchainVersion("ndkVersion")
extra["nuxieBuildToolsVersion"] = requiredToolchainVersion("buildToolsVersion")
val bundletoolVersion = requiredToolchainVersion("bundletoolVersion")
val bundletoolCli = configurations.create("bundletoolCli") {
  isCanBeConsumed = false
  isCanBeResolved = true
}
dependencies.add(bundletoolCli.name, "com.android.tools.build:bundletool:$bundletoolVersion")

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

fun atomicMoveDirectory(source: Path, destination: Path) {
  try {
    Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE)
  } catch (error: AtomicMoveNotSupportedException) {
    throw GradleException(
      "Runtime installation requires atomic sibling-directory renames, but ${source.parent} does not support them.",
      error,
    )
  }
}

fun runtimeInstallSiblings(suffix: String): List<Path> =
  Files.list(runtimeDirectory.toPath()).use { paths ->
    paths
      .filter { path ->
        val name = path.fileName.toString()
        name.startsWith(".prebuilt-") && name.endsWith(suffix)
      }
      .toList()
      .sortedBy { it.fileName.toString() }
  }

fun recoverRuntimeInstall(validationFailure: (Path) -> String?) {
  val prebuiltPath = runtimePrebuilt.toPath()
  val backups = runtimeInstallSiblings(".backup")
  if (backups.isNotEmpty()) {
    // Never prune the last good tree on the strength of a directory merely
    // existing: a corrupted or tampered live tree would otherwise destroy
    // the only recoverable copy. Validate first; an invalid live tree is
    // discarded and the newest valid backup restored instead.
    val liveValid = validationFailure(prebuiltPath) == null
    if (liveValid) {
      backups.forEach(::deleteRecursively)
    } else {
      val recovery = backups
        .sortedWith(
          compareByDescending<Path> { Files.getLastModifiedTime(it).toMillis() }
            .thenByDescending { it.fileName.toString() },
        )
        .firstOrNull { backup -> validationFailure(backup) == null }
      if (recovery != null) {
        if (Files.exists(prebuiltPath)) {
          deleteRecursively(prebuiltPath)
          logger.lifecycle("Discarded an invalid runtime/prebuilt/ tree in favor of its backup.")
        }
        atomicMoveDirectory(recovery, prebuiltPath)
        backups.filter { it != recovery }.forEach(::deleteRecursively)
        logger.lifecycle("Recovered runtime/prebuilt/ from an interrupted installation.")
      }
    }
  }
  runtimeInstallSiblings(".tmp").forEach(::deleteRecursively)
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

fun runtimeArtifactDigests(directory: Path): Map<String, String> =
  requiredRuntimeArtifacts.sorted().associateWith { relativePath -> sha256(directory.resolve(relativePath)) }

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

fun writeChecksumManifest(directory: Path, archiveChecksum: String) {
  val manifest = linkedMapOf(
    "archiveChecksum" to archiveChecksum,
    "files" to runtimeArtifactDigests(directory),
  )
  Files.writeString(
    directory.resolve(".artifact-checksum"),
    JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n",
  )
}

fun cachedRuntimeValidationFailure(
  directory: Path,
  expectedArchiveChecksum: String,
  maximumBytes: Long,
): String? {
  if (!Files.isDirectory(directory)) return "runtime/prebuilt/ is missing"
  val manifestFile = directory.resolve(".artifact-checksum").toFile()
  if (!manifestFile.isFile) return "the checksum manifest is missing"

  val manifest = runCatching { readJsonObject(manifestFile) }
    .getOrElse { return "the checksum manifest is invalid: ${it.message}" }
  if (manifest["archiveChecksum"] != expectedArchiveChecksum) {
    return "the archive checksum pin changed"
  }

  val recordedFiles = manifest["files"] as? Map<*, *>
    ?: return "the checksum manifest has no per-file digests"
  if (recordedFiles.keys != requiredRuntimeArtifacts) {
    return "the checksum manifest does not describe the complete runtime artifact set"
  }
  val invalidDigest = recordedFiles.entries.firstOrNull { (path, digest) ->
    path !is String || digest !is String || !digest.matches(Regex("[0-9a-f]{64}"))
  }
  if (invalidDigest != null) return "the checksum manifest contains an invalid per-file digest"

  runCatching { verifyArtifactSet(directory, maximumBytes) }
    .exceptionOrNull()
    ?.let { return it.message ?: "the runtime artifact set is invalid" }

  for (relativePath in requiredRuntimeArtifacts.sorted()) {
    val expectedDigest = recordedFiles[relativePath] as String
    val actualDigest = sha256(directory.resolve(relativePath))
    if (actualDigest != expectedDigest) {
      return "digest mismatch for $relativePath (expected $expectedDigest, got $actualDigest)"
    }
  }
  return null
}

val runtimeFetch by tasks.registering {
  group = "runtime"
  description = "Fetches and verifies the pinned Nuxie runtime artifact."
  inputs.file(runtimeArtifactPin)
  inputs.file(runtimeSizeBudget)
  // Deliberately do not declare prebuilt/ as a Gradle output: Gradle creates
  // declared output directories before task actions, which would leave an
  // empty prebuilt/ after a checksum failure. The local checksum manifest is
  // the task's content-integrity freshness check instead.

  doLast {
    val budget = readJsonObject(runtimeSizeBudget)
    val maximumBytes = (budget["maximumBytes"] as? Number)?.toLong()?.takeIf { it > 0L }
      ?: throw GradleException("runtime/size-budget.json must contain a positive 'maximumBytes' number.")

    when (val localSelection = providers.environmentVariable("NUXIE_RUNTIME_USE_LOCAL").orNull) {
      "1" -> {
        recoverRuntimeInstall { candidate ->
          runCatching { verifyArtifactSet(candidate, maximumBytes) }
            .exceptionOrNull()
            ?.let { error -> error.message ?: "the runtime artifact set is invalid" }
        }
        runCatching { verifyArtifactSet(runtimePrebuilt.toPath(), maximumBytes) }
          .getOrElse { error ->
            throw GradleException(
              "NUXIE_RUNTIME_USE_LOCAL=1 requires a complete runtime/prebuilt/ artifact set. " +
                "Run scripts/stage-runtime.sh <path-to-nuxie-runtime-checkout> first. ${error.message}",
              error,
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
    // Parse the URL before recovery: every pin field must prove valid before
    // any destructive step, or a malformed pin can consume install backups.
    val pinnedUrl = runCatching { URI(url).toURL() }.getOrElse {
      throw GradleException("runtime/artifact.json url must be a valid absolute URL.", it)
    }

    recoverRuntimeInstall { candidate ->
      cachedRuntimeValidationFailure(candidate, expectedChecksum, maximumBytes)
    }

    val reuseFailure = cachedRuntimeValidationFailure(
      runtimePrebuilt.toPath(),
      expectedChecksum,
      maximumBytes,
    )
    if (reuseFailure == null) {
      logger.lifecycle("Using verified Nuxie runtime $release from runtime/prebuilt/.")
      return@doLast
    }
    logger.lifecycle("Cached Nuxie runtime cannot be reused ($reuseFailure); refetching $release.")

    val token = "${System.currentTimeMillis()}-${UUID.randomUUID()}"
    val downloadedZip = runtimeDirectory.resolve(".runtime-$token.zip").toPath()
    val extractedDirectory = runtimeDirectory.resolve(".prebuilt-$token.tmp").toPath()
    val backupDirectory = runtimeDirectory.resolve(".prebuilt-$token.backup").toPath()

    try {
      logger.lifecycle("Downloading pinned Nuxie runtime $release.")
      pinnedUrl.openConnection().apply {
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
      writeChecksumManifest(extractedDirectory, expectedChecksum)

      val prebuiltPath = runtimePrebuilt.toPath()
      if (Files.exists(prebuiltPath)) atomicMoveDirectory(prebuiltPath, backupDirectory)
      try {
        atomicMoveDirectory(extractedDirectory, prebuiltPath)
      } catch (error: Exception) {
        if (Files.exists(backupDirectory) && !Files.exists(prebuiltPath)) {
          atomicMoveDirectory(backupDirectory, prebuiltPath)
        }
        throw error
      }
      deleteRecursively(backupDirectory)
      logger.lifecycle(
        "Verified and extracted Nuxie runtime $release " +
          "(${runtimeArtifactSize(prebuiltPath)} / $maximumBytes bytes).",
      )
    } catch (error: Exception) {
      throw GradleException(
        "Cached Nuxie runtime failed integrity validation ($reuseFailure), and the verified refetch of " +
          "$release failed: ${error.message}. Refusing to use unverified runtime contents.",
        error,
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

fun stripSourceComments(source: String): String {
  val stripped = StringBuilder(source.length)
  var index = 0
  var lineComment = false
  var blockCommentDepth = 0
  var quote: Char? = null
  var tripleQuoted = false
  var escaped = false

  fun appendCommentCharacter(character: Char) {
    stripped.append(if (character == '\n' || character == '\r') character else ' ')
  }

  while (index < source.length) {
    val character = source[index]
    val next = source.getOrNull(index + 1)

    if (lineComment) {
      appendCommentCharacter(character)
      if (character == '\n') lineComment = false
      index += 1
      continue
    }

    if (blockCommentDepth > 0) {
      if (character == '/' && next == '*') {
        stripped.append("  ")
        blockCommentDepth += 1
        index += 2
      } else if (character == '*' && next == '/') {
        stripped.append("  ")
        blockCommentDepth -= 1
        index += 2
      } else {
        appendCommentCharacter(character)
        index += 1
      }
      continue
    }

    if (tripleQuoted) {
      if (source.startsWith("\"\"\"", index)) {
        stripped.append("\"\"\"")
        tripleQuoted = false
        index += 3
      } else {
        stripped.append(character)
        index += 1
      }
      continue
    }

    if (quote != null) {
      stripped.append(character)
      if (escaped) {
        escaped = false
      } else if (character == '\\') {
        escaped = true
      } else if (character == quote) {
        quote = null
      }
      index += 1
      continue
    }

    when {
      character == '/' && next == '/' -> {
        stripped.append("  ")
        lineComment = true
        index += 2
      }
      character == '/' && next == '*' -> {
        stripped.append("  ")
        blockCommentDepth = 1
        index += 2
      }
      source.startsWith("\"\"\"", index) -> {
        stripped.append("\"\"\"")
        tripleQuoted = true
        index += 3
      }
      character == '\"' || character == '\'' -> {
        stripped.append(character)
        quote = character
        index += 1
      }
      else -> {
        stripped.append(character)
        index += 1
      }
    }
  }
  return stripped.toString()
}

val runtimeBoundary by tasks.registering {
  group = "verification"
  description =
    "Checks source-level runtime boundary references; intentionally does not inspect reflection or compiled bytecode."
  val kotlinJavaSources = fileTree("nuxie-android") {
    include("**/*.kt", "**/*.java")
    exclude("build/**", ".gradle/**")
  }
  val generatedKotlinJavaSources = fileTree("nuxie-android/build/generated") {
    include("**/*.kt", "**/*.java")
  }
  val cppSources = fileTree("nuxie-android/src/main/cpp") {
    include("**/*.c", "**/*.cc", "**/*.cpp", "**/*.cxx", "**/*.h", "**/*.hh", "**/*.hpp", "**/*.hxx")
  }
  val sources = files(kotlinJavaSources, generatedKotlinJavaSources, cppSources)
  inputs.files(kotlinJavaSources, cppSources)
  // AGP shares build/generated/ between source and resource producers. Read
  // generated Kotlin/Java at execution time rather than claiming the shared
  // tree as an input; this task has no outputs and therefore always executes.

  doLast {
    val allowedPackages = setOf(
      "ai.nuxie.sdk.runtime",
    )
    val packagePattern = Regex("(?m)^\\s*package\\s+([A-Za-z0-9_.]+)")
    val simpleBridgePattern = Regex("(?<![A-Za-z0-9_.])NuxieRuntimeBridge\\b")
    val qualifiedBridgePattern = Regex("\\bai\\.nuxie\\.sdk\\.runtime\\.NuxieRuntimeBridge\\b")
    val bridgeImportPattern = Regex(
      "(?m)^\\s*import\\s+ai\\.nuxie\\.sdk\\.runtime\\.NuxieRuntimeBridge(?:\\s+as\\s+[A-Za-z0-9_]+)?\\s*$",
    )
    val bridgeTypeAliasPattern = Regex(
      "\\btypealias\\s+[A-Za-z_][A-Za-z0-9_]*(?:\\s*<[^>]+>)?\\s*=\\s*" +
        "(?:ai\\.nuxie\\.sdk\\.runtime\\.)?NuxieRuntimeBridge\\b",
    )
    val bridgeImportAliasPattern = Regex(
      "(?m)^\\s*import\\s+ai\\.nuxie\\.sdk\\.runtime\\.NuxieRuntimeBridge\\s+as\\s+" +
        "([A-Za-z_][A-Za-z0-9_]*)\\s*$",
    )
    val cSymbolPattern = Regex("\\bnux_[A-Za-z0-9_]+\\b")
    val runtimeShim = file("nuxie-android/src/main/cpp/nuxie_runtime_android.c").canonicalFile

    val violations = buildList {
      for (source in sources.files.sortedBy { it.relativeTo(rootDir).path }) {
        val text = stripSourceComments(source.readText())
        val relativePath = source.relativeTo(rootDir)
        val extension = source.extension.lowercase()

        if (extension in setOf("c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx")) {
          if (source.canonicalFile != runtimeShim) {
            val lines = text.lineSequence().mapIndexedNotNull { index, line ->
              (index + 1).takeIf { cSymbolPattern.containsMatchIn(line) }
            }.toList()
            if (lines.isNotEmpty()) add("$relativePath:${lines.joinToString()} (nux_* outside the JNI shim)")
          }
          continue
        }

        val importedBridgeAliases = bridgeImportAliasPattern.findAll(text)
          .map { it.groupValues[1] }
          .toSet()
        val aliasedBridgeTypeAliasPattern = importedBridgeAliases.takeIf { it.isNotEmpty() }?.let { aliases ->
          Regex(
            "\\btypealias\\s+[A-Za-z_][A-Za-z0-9_]*(?:\\s*<[^>]+>)?\\s*=\\s*(?:" +
              aliases.joinToString("|") { Regex.escape(it) } + ")\\b",
          )
        }
        val typeAliasMatches = buildList {
          addAll(bridgeTypeAliasPattern.findAll(text).toList())
          if (aliasedBridgeTypeAliasPattern != null) {
            addAll(aliasedBridgeTypeAliasPattern.findAll(text).toList())
          }
        }
        val typeAliasLines = typeAliasMatches
          .map { match -> text.take(match.range.first).count { it == '\n' } + 1 }
          .distinct()
          .sorted()
        if (typeAliasLines.isNotEmpty()) {
          add("$relativePath:${typeAliasLines.joinToString()} (typealias re-exports NuxieRuntimeBridge)")
        }

        val packageName = packagePattern.find(text)?.groupValues?.get(1).orEmpty()
        val allowed = allowedPackages.any { packageName == it || packageName.startsWith("$it.") }
        if (allowed) continue

        val bridgeLines = text.lineSequence().mapIndexedNotNull { index, line ->
          (index + 1).takeIf {
            simpleBridgePattern.containsMatchIn(line) ||
              qualifiedBridgePattern.containsMatchIn(line) ||
              bridgeImportPattern.containsMatchIn(line)
          }
        }.toList()
        if (bridgeLines.isNotEmpty()) {
          add("$relativePath:${bridgeLines.joinToString()} (package '$packageName')")
        }
      }
    }

    if (violations.isNotEmpty()) {
      throw GradleException(
        "Direct runtime references are outside the runtime boundary:\n" +
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
    description =
      "Checks source-level runtime boundary references; intentionally does not inspect reflection or compiled bytecode."
    dependsOn(runtimeBoundary)
  }
}

project(":nuxie-android") {
  tasks.matching {
    val taskName = name.lowercase()
    taskName.startsWith("generate") || taskName.startsWith("ksp") || taskName.startsWith("kapt") ||
      taskName.startsWith("databinding")
  }.configureEach {
    val sourceGenerator = this
    runtimeBoundary.configure { mustRunAfter(sourceGenerator) }
  }
  tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn(project(":runtime").tasks.named("fetch"))
  }
  // The documented PR gate always runs lint, and the boundary is a static
  // source rule, so lint is the narrowest lifecycle task that cannot omit it.
  tasks.matching { it.name == "lint" }.configureEach {
    dependsOn(project(":runtime").tasks.named("boundary"))
  }
}

val releaseVerifier = layout.projectDirectory.file("scripts/verify-android-release.py").asFile
val releaseVerifierTests = layout.projectDirectory.file("scripts/tests/test_verify_android_release.py").asFile
val expectedNativeInventoryArguments = listOf(
  "--expected-abi", "arm64-v8a",
  "--expected-abi", "x86_64",
  "--expected-library", "libc++_shared.so",
  "--expected-library", "libnux_capi.so",
  "--expected-library", "libnuxie_runtime_android.so",
)

fun configuredAndroidSdk(): File {
  val environmentPath = sequenceOf("ANDROID_SDK_ROOT", "ANDROID_HOME")
    .mapNotNull(System::getenv)
    .firstOrNull(String::isNotBlank)
  if (environmentPath != null) return file(environmentPath)

  val localPropertiesFile = file("local.properties")
  if (localPropertiesFile.isFile) {
    val localProperties = Properties().apply {
      localPropertiesFile.inputStream().use(::load)
    }
    localProperties.getProperty("sdk.dir")?.takeIf(String::isNotBlank)?.let(::file)?.let { return it }
  }
  throw GradleException("Set ANDROID_SDK_ROOT or ANDROID_HOME to the pinned Android SDK installation.")
}

val verifyAndroidReleaseVerifier by tasks.registering(Exec::class) {
  group = "verification"
  description = "Runs deterministic tests for the Android release artifact verifier."
  commandLine("python3", "-m", "unittest", releaseVerifierTests)
}

val releaseAar = project(":nuxie-android").layout.buildDirectory.file(
  "outputs/aar/nuxie-android-release.aar",
)
val verifyReleaseAar16KiB = project(":nuxie-android").tasks.register<Exec>("verifyReleaseAar16KiB") {
  group = "verification"
  description = "Verifies the complete release AAR native inventory and 16 KiB ELF contract."
  doFirst {
    commandLine(
      "python3",
      releaseVerifier,
      "elf-archive",
      releaseAar.get().asFile,
      *expectedNativeInventoryArguments.toTypedArray(),
    )
  }
}
project(":nuxie-android").tasks.matching { it.name == "assembleRelease" }.configureEach {
  finalizedBy(verifyReleaseAar16KiB)
}
verifyReleaseAar16KiB.configure { mustRunAfter(":nuxie-android:assembleRelease") }

val exampleDebugApk = project(":example-app").layout.buildDirectory.file(
  "outputs/apk/debug/example-app-debug.apk",
)
val verifyExampleApk16KiB = project(":example-app").tasks.register<Exec>("verifyDebugApk16KiB") {
  group = "verification"
  description = "Verifies the complete example APK native inventory, ELF contract, and ZIP alignment."
  doFirst {
    val buildTools = rootProject.extra["nuxieBuildToolsVersion"] as String
    val zipalign = configuredAndroidSdk().resolve("build-tools/$buildTools/zipalign")
    commandLine(
      "python3",
      releaseVerifier,
      "apk",
      exampleDebugApk.get().asFile,
      "--zipalign",
      zipalign,
      *expectedNativeInventoryArguments.toTypedArray(),
    )
  }
}
project(":example-app").tasks.matching { it.name == "assembleDebug" }.configureEach {
  finalizedBy(verifyExampleApk16KiB)
}
verifyExampleApk16KiB.configure { mustRunAfter(":example-app:assembleDebug") }

val exampleReleaseBundle = project(":example-app").layout.buildDirectory.file(
  "outputs/bundle/release/example-app-release.aab",
)
val bundleConfigDump = layout.buildDirectory.file(
  "reports/android-release/example-app-bundle-config.txt",
)
val bundleConfigCapture = ByteArrayOutputStream()
val dumpExampleBundleConfig = project(":example-app").tasks.register<JavaExec>("dumpReleaseBundleConfig") {
  group = "verification"
  description = "Dumps the release AAB configuration with the pinned bundletool."
  classpath = bundletoolCli
  mainClass.set("com.android.tools.build.bundletool.BundleToolMain")
  args("dump", "config", "--bundle=${exampleReleaseBundle.get().asFile.absolutePath}")
  standardOutput = bundleConfigCapture
  outputs.file(bundleConfigDump)
  outputs.upToDateWhen { false }
  doFirst {
    bundleConfigCapture.reset()
    val destination = bundleConfigDump.get().asFile.toPath()
    Files.createDirectories(destination.parent)
    Files.deleteIfExists(destination)
  }
  doLast {
    Files.write(bundleConfigDump.get().asFile.toPath(), bundleConfigCapture.toByteArray())
  }
}
val verifyExampleBundleElfs16KiB = project(":example-app").tasks.register<Exec>("verifyReleaseBundleElfs16KiB") {
  group = "verification"
  description = "Verifies the complete release AAB native inventory and 16 KiB ELF contract."
  doFirst {
    commandLine(
      "python3",
      releaseVerifier,
      "elf-archive",
      exampleReleaseBundle.get().asFile,
      *expectedNativeInventoryArguments.toTypedArray(),
    )
  }
}
val verifyExampleBundleConfig16KiB = project(":example-app").tasks.register<Exec>("verifyReleaseBundleConfig16KiB") {
  group = "verification"
  description = "Requires PAGE_ALIGNMENT_16K in the actual pinned-bundletool AAB config dump."
  doFirst {
    commandLine(
      "python3",
      releaseVerifier,
      "bundle-config",
      bundleConfigDump.get().asFile,
    )
  }
}
project(":example-app").tasks.matching { it.name == "bundleRelease" }.configureEach {
  finalizedBy(verifyExampleBundleElfs16KiB, dumpExampleBundleConfig)
}
verifyExampleBundleElfs16KiB.configure { mustRunAfter(":example-app:bundleRelease") }
dumpExampleBundleConfig.configure { mustRunAfter(":example-app:bundleRelease") }
dumpExampleBundleConfig.configure {
  finalizedBy(verifyExampleBundleConfig16KiB)
}
verifyExampleBundleConfig16KiB.configure { mustRunAfter(dumpExampleBundleConfig) }

tasks.register("verifyAndroidReleaseArtifacts") {
  group = "verification"
  description = "Builds and verifies the release AAR, example APK, and example AAB native contracts."
  dependsOn(
    verifyAndroidReleaseVerifier,
    ":nuxie-android:assembleRelease",
    verifyReleaseAar16KiB,
    ":example-app:assembleDebug",
    verifyExampleApk16KiB,
    ":example-app:bundleRelease",
    verifyExampleBundleElfs16KiB,
    dumpExampleBundleConfig,
    verifyExampleBundleConfig16KiB,
  )
}
