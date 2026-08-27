plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "ai.nuxie.sdk"
  compileSdk = 35
  buildToolsVersion = "34.0.0"

  defaultConfig {
    minSdk = 23
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  testOptions {
    targetSdk = 35
    unitTests.isReturnDefaultValues = true
    unitTests.isIncludeAndroidResources = true
  }

  // The engine (.so) is fetched and verified from runtime/artifact.json by
  // the root runtimeFetch task. scripts/stage-runtime.sh remains the explicit
  // NUXIE_RUNTIME_USE_LOCAL=1 escape for local runtime development.
  val runtimePrebuilt = rootProject.file("runtime/prebuilt")
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }
  sourceSets.getByName("main") {
    jniLibs.srcDir(runtimePrebuilt.resolve("jniLibs"))
  }
  sourceSets.getByName("test") {
    // Give the dedicated host-only source tree friend access to SDK internals
    // by compiling it with JVM tests; it is never part of an Android variant/AAR.
    java.srcDir("src/hostRenderHarness/kotlin")
  }
  defaultConfig {
    ndk {
      abiFilters += listOf("arm64-v8a", "x86_64")
    }
  }
}

dependencies {
  implementation(libs.androidx.sqlite.framework)
  // StoreProduct exposes ProductDetails, so Billing is part of consumers'
  // compile classpath. Use the plain artifact: billing-ktx ships Kotlin 2.2
  // metadata this repo's pinned compiler cannot read.
  api(libs.google.play.billing)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)

  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.junit)
}

val hostCapiLibrary = providers.environmentVariable("NUXIE_HOST_CAPI_LIB")
val hostVulkanIcd = providers.environmentVariable("VK_ICD_FILENAMES")
val hostDyldLibraryPath = providers.environmentVariable("DYLD_LIBRARY_PATH")
val hostOperatingSystem = System.getProperty("os.name")
val hostIsMac = hostOperatingSystem.startsWith("Mac")
val hostIsLinux = hostOperatingSystem.startsWith("Linux")
val hostLibrarySuffix = if (hostIsMac) "dylib" else "so"
val hostBridgeDirectory = layout.buildDirectory.dir("host-render/native")
val hostBridgeLibrary = hostBridgeDirectory.map {
  it.file("libnuxie_runtime_android_host.$hostLibrarySuffix")
}

val compileHostRenderBridge by tasks.registering(Exec::class) {
  group = "host render"
  description = "Compiles the host JVM JNI adapter against NUXIE_HOST_CAPI_LIB."
  inputs.file("src/main/cpp/nuxie_runtime_android.c")
  inputs.file(rootProject.file("runtime/prebuilt/include/nux_capi.generated.h"))
  hostCapiLibrary.orNull?.let { inputs.file(it) }
  outputs.file(hostBridgeLibrary)

  doFirst {
    check(hostIsMac || hostIsLinux) {
      "The host render JNI adapter supports macOS and Linux (found $hostOperatingSystem)."
    }
    val capi = hostCapiLibrary.orNull?.let(::file)?.canonicalFile
      ?: throw GradleException("Set NUXIE_HOST_CAPI_LIB to the host-built nux_capi library.")
    if (!capi.isFile) {
      throw GradleException("NUXIE_HOST_CAPI_LIB is not a file: $capi")
    }
    val output = hostBridgeLibrary.get().asFile
    output.parentFile.mkdirs()
    val javaHome = file(System.getProperty("java.home"))
    val platformInclude = if (hostIsMac) "darwin" else "linux"
    val compiler = if (hostIsMac) "clang" else "cc"
    val linkMode = if (hostIsMac) "-dynamiclib" else "-shared"
    commandLine(
      compiler,
      linkMode,
      "-fPIC",
      "-O2",
      "-DNUX_CAPI_ANDROID_VULKAN",
      "-I${javaHome.resolve("include")}",
      "-I${javaHome.resolve("include/$platformInclude")}",
      "-I${rootProject.file("runtime/prebuilt/include")}",
      file("src/main/cpp/nuxie_runtime_android.c"),
      capi,
      "-Wl,-rpath,${capi.parentFile}",
      "-o",
      output,
    )
  }
}

tasks.register<JavaExec>("hostRenderHarness") {
  group = "host render"
  description = "Renders a published Experience to deterministic headless Vulkan CPU frames."
  dependsOn(
    compileHostRenderBridge,
    "compileDebugUnitTestKotlin",
    "compileDebugUnitTestJavaWithJavac",
    "processDebugUnitTestJavaRes",
  )
  mainClass.set("ai.nuxie.sdk.hostrender.HostRenderHarnessKt")
  systemProperty("nuxie.host.jni.lib", hostBridgeLibrary.get().asFile.absolutePath)
  if (hostVulkanIcd.isPresent) environment("VK_ICD_FILENAMES", hostVulkanIcd.get())
  if (hostDyldLibraryPath.isPresent) {
    environment("DYLD_LIBRARY_PATH", hostDyldLibraryPath.get())
  }
  doFirst {
    classpath = tasks.named<Test>("testDebugUnitTest").get().classpath
  }
}

if (hostCapiLibrary.isPresent) {
  tasks.withType<Test>().configureEach {
    dependsOn(compileHostRenderBridge)
    systemProperty("nuxie.host.jni.lib", hostBridgeLibrary.get().asFile.absolutePath)
    if (hostVulkanIcd.isPresent) environment("VK_ICD_FILENAMES", hostVulkanIcd.get())
    if (hostDyldLibraryPath.isPresent) {
      environment("DYLD_LIBRARY_PATH", hostDyldLibraryPath.get())
    }
  }
}

// The cross-SDK conformance fixtures live at the repository root; register
// them as test inputs so fixture-only changes re-run the tests instead of
// hitting Gradle up-to-date or build-cache reuse.
tasks.withType<Test>().configureEach {
  systemProperty("nuxie.repo.root", rootProject.projectDir.absolutePath)
  inputs.dir(rootProject.layout.projectDirectory.dir("fixtures"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("crossSdkFixtures")
}
