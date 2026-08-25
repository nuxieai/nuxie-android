pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "nuxie-android-sdk"

include(":nuxie-android")
include(":example-app")

// Task-only lifecycle project for the operator-facing runtime:fetch and
// runtime:boundary paths. It applies no plugin and produces no artifact; the
// SDK remains a single Gradle artifact.
include(":runtime")
