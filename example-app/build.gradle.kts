plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "ai.nuxie.example"
  compileSdk = 35
  buildToolsVersion = "34.0.0"

  defaultConfig {
    applicationId = "ai.nuxie.example"
    minSdk = 23
    targetSdk = 35
    versionCode = 1
    versionName = "0.1.0"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  implementation(project(":nuxie-android"))
}
