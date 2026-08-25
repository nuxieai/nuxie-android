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

// The cross-SDK conformance fixtures live at the repository root; register
// them as test inputs so fixture-only changes re-run the tests instead of
// hitting Gradle up-to-date or build-cache reuse.
tasks.withType<Test>().configureEach {
  inputs.dir(rootProject.layout.projectDirectory.dir("fixtures"))
    .withPathSensitivity(PathSensitivity.RELATIVE)
    .withPropertyName("crossSdkFixtures")
}
