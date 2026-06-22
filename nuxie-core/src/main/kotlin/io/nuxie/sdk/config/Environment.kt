package io.nuxie.sdk.config

/**
 * Environment settings.
 *
 * Mirrors iOS `Environment` behavior:
 * - Each environment has a default ingest endpoint.
 * - `CUSTOM` keeps the default endpoint unless the caller overrides `apiEndpoint`.
 */
enum class Environment(val defaultEndpoint: String) {
  PRODUCTION("https://i.nuxie.ai"),
  STAGING("https://staging-i.nuxie.ai"),
  DEVELOPMENT("https://dev-i.nuxie.ai"),
  CUSTOM("https://i.nuxie.ai"),
}

