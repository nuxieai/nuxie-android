plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.android) apply false
  alias(libs.plugins.binary.compatibility.validator)
}

group = "ai.nuxie"
version = "0.1.0-SNAPSHOT"

apiValidation {
  ignoredProjects.add("example-app")
}

subprojects {
  group = rootProject.group
  version = rootProject.version
}
