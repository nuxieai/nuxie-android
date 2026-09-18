package ai.nuxie.sdk.runtime;

/** Owner-thread cleanup: attempt every release and retry only unsuccessful steps. */
final class VideoResourceCleanup {
  private final Runnable[] pending;

  VideoResourceCleanup(Runnable... releases) { pending = releases.clone(); }

  RuntimeException release() {
    RuntimeException failure = null;
    for (int i = 0; i < pending.length; i++) {
      if (pending[i] == null) continue;
      try {
        pending[i].run();
        pending[i] = null;
      } catch (RuntimeException error) {
        if (failure == null) failure = new IllegalStateException("video resources remain owned");
        failure.addSuppressed(error);
      }
    }
    return failure;
  }
}
