plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
}

val purchaseProvider = providers.gradleProperty("nuxieExamplePurchaseProvider").orElse("nuxie").get()
require(purchaseProvider in setOf("nuxie", "revenuecat", "superwall")) {
  "nuxieExamplePurchaseProvider must be nuxie, revenuecat, or superwall."
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
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    versionCode = 11
    versionName = "0.1.10"
  }

  buildFeatures { buildConfig = true }
  testOptions { unitTests.isIncludeAndroidResources = true }
  sourceSets.getByName("main").java.srcDir("src/$purchaseProvider/kotlin")

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  kotlinOptions {
    jvmTarget = "17"
  }
}

dependencies {
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.junit)
  testImplementation(libs.junit)
  testImplementation(libs.robolectric)
  implementation(project(":nuxie-android"))
  when (purchaseProvider) {
    "revenuecat" -> implementation(project(":example-revenuecat"))
    "superwall" -> implementation(project(":example-superwall"))
  }
}

val verifyPurchaseProviderSelection = tasks.register("verifyPurchaseProviderSelection") {
  group = "verification"
  doLast {
    for (variant in listOf("debug", "release")) {
      val actual = configurations.getByName("${variant}RuntimeClasspath")
        .incoming.resolutionResult.allComponents.mapNotNull { component ->
          val group = component.moduleVersion?.group.orEmpty()
          when {
            group.startsWith("com.revenuecat") -> "revenuecat"
            group.startsWith("com.superwall") -> "superwall"
            else -> null
          }
        }.toSet()
      val expected = if (purchaseProvider == "nuxie") emptySet() else setOf(purchaseProvider)
      check(actual == expected) { "Expected provider dependencies $expected, found $actual in $variant." }
    }
  }
}
tasks.named("preBuild") { dependsOn(verifyPurchaseProviderSelection) }
