#ifndef NUXIE_LOGGING_POLICY_H
#define NUXIE_LOGGING_POLICY_H

// Include after system headers. Native Android diagnostics must use the Kotlin
// policy callback, and must never redirect the embedding application's streams.
// The host-only harness deliberately retains its own stderr diagnostics.
#if defined(__ANDROID__)
#pragma GCC poison __android_log_print __android_log_write __android_log_vprint
#pragma GCC poison printf fprintf vprintf vfprintf puts fputs perror dprintf vdprintf
#pragma GCC poison dup2 dup3 freopen
#endif

#endif
