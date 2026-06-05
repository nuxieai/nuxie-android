plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

fun quote(value: String): String = "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""

val demoApiKey = (project.findProperty("NUXIE_EXAMPLE_API_KEY") as? String) ?: ""
val demoApiEndpoint = (project.findProperty("NUXIE_EXAMPLE_API_ENDPOINT") as? String) ?: "https://i.nuxie.io"

android {
  namespace = "io.nuxie.example"
  compileSdk = 35

  defaultConfig {
    applicationId = "io.nuxie.example"
    minSdk = 21
    targetSdk = 35
    versionCode = 1
    versionName = "1.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "DEFAULT_API_KEY", quote(demoApiKey))
    buildConfigField("String", "DEFAULT_API_ENDPOINT", quote(demoApiEndpoint))
    buildConfigField("String", "DEFAULT_FLOW_ID", quote(""))
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
    unitTests.isIncludeAndroidResources = true
  }

  buildFeatures {
    buildConfig = true
  }
}

dependencies {
  implementation(project(":nuxie-android"))

  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

  testImplementation("junit:junit:4.13.2")
  testImplementation("androidx.test:core:1.6.1")
  testImplementation("org.robolectric:robolectric:4.16.1")
}
