plugins { `java-library` }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }
dependencies {
  compileOnly("com.android.tools.lint:lint-api:31.10.1")
  testImplementation("com.android.tools.lint:lint-tests:31.10.1")
  testImplementation("com.android.tools.lint:lint-checks:31.10.1")
  testImplementation(libs.junit)
}
