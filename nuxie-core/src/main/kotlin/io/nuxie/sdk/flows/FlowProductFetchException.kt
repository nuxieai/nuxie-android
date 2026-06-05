package io.nuxie.sdk.flows

class FlowProductFetchException(
  message: String,
  cause: Throwable? = null,
) : RuntimeException(message, cause)
