plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
}

android {
  namespace = "ai.nuxie.example"
  compileSdk = 36
  buildToolsVersion = rootProject.extra["nuxieBuildToolsVersion"] as String
  ndkVersion = rootProject.extra["nuxieNdkVersion"] as String

  defaultConfig {
    applicationId = "ai.nuxie.example"
    minSdk = 23
    targetSdk = 36
    versionCode = 6
    versionName = "0.1.5"
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
