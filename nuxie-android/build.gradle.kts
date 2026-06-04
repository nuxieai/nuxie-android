plugins {
  id("com.android.library")
  id("org.jetbrains.kotlin.android")
  id("com.google.devtools.ksp")
}

android {
  namespace = "io.nuxie.sdk"
  compileSdk = 35

  defaultConfig {
    minSdk = 21
    consumerProguardFiles("consumer-rules.pro")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
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
}

dependencies {
  api(project(":nuxie-core"))

  implementation("androidx.activity:activity-ktx:1.9.3")
  implementation("androidx.annotation:annotation:1.8.2")
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
  implementation("androidx.room:room-runtime:2.6.1")
  implementation("androidx.room:room-ktx:2.6.1")
  implementation("com.android.billingclient:billing:8.0.0")
  ksp("androidx.room:room-compiler:2.6.1")

  testImplementation("junit:junit:4.13.2")
  testImplementation("androidx.test:core:1.6.1")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
  testImplementation("org.robolectric:robolectric:4.13")
  testImplementation("androidx.room:room-testing:2.6.1")
}
