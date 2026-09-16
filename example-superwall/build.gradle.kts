plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "ai.nuxie.example.superwall"
  compileSdk = 36
  defaultConfig { minSdk = 23 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
  implementation(project(":nuxie-android"))
  api("com.superwall.sdk:superwall-android:2.8.3")
  testImplementation("org.mockito:mockito-core:5.18.0")
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
}
