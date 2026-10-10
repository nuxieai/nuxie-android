"""Checksummed publication inputs; the Android SDK never compiles runtime sources."""

_FILES = [
    "include/nux_capi.generated.h",
    "jniLibs/arm64-v8a/libc++_shared.so",
    "jniLibs/arm64-v8a/libnux_capi.so",
    "jniLibs/x86_64/libc++_shared.so",
    "jniLibs/x86_64/libnux_capi.so",
]

def _runtime_impl(ctx):
    pin = json.decode(ctx.read(ctx.attr.pin))
    if pin["abis"] != ["arm64-v8a", "x86_64"]:
        fail("The SDK requires exactly arm64-v8a and x86_64 runtime artifacts")
    mode = ctx.getenv("NUXIE_RUNTIME_USE_LOCAL", "")
    if mode not in ["", "1"]:
        fail("NUXIE_RUNTIME_USE_LOCAL must be unset or exactly 1")
    python = ctx.which("python3")
    if python == None:
        fail("python3 is required to verify runtime artifact inputs")
    verifier = ctx.path(ctx.attr.verifier)
    budget = ctx.path(ctx.attr.budget)
    if mode == "1":
        directory = ctx.path(ctx.attr.pin).dirname
        tree = directory.get_child("prebuilt")
        provenance_path = directory.get_child("local-artifact.json")
        if not provenance_path.exists:
            fail("Local runtime provenance is missing; run scripts/stage-runtime.sh <runtime-checkout> first")
        provenance = json.decode(ctx.read(provenance_path, watch = "yes"))
        for path in _FILES:
            ctx.watch(tree.get_child(path))
        result = ctx.execute([python, verifier, "verify-local", "--tree", tree, "--provenance", provenance_path, "--budget", budget])
        for path in _FILES:
            ctx.symlink(tree.get_child(path), path)
    else:
        ctx.download(url = pin["url"], sha256 = pin["checksum"], output = "runtime.zip")
        result = ctx.execute([python, verifier, "extract", "--archive", ctx.path("runtime.zip"), "--output", ctx.path("."), "--budget", budget])
        ctx.delete("runtime.zip")
        provenance = dict(pin, mode = "published")
    if result.return_code:
        fail("Runtime artifact verification failed:\n" + result.stderr)
    ctx.file("provenance.json", json.encode(provenance))
    for path in _FILES:
        if not ctx.path(path).exists:
            fail("Pinned runtime archive is missing " + path)
    ctx.file("BUILD.bazel", """load("@rules_cc//cc:cc_import.bzl", "cc_import")
load("@rules_cc//cc:cc_library.bzl", "cc_library")
package(default_visibility = ["//visibility:public"])
config_setting(name = "arm64", constraint_values = ["@platforms//cpu:arm64"])
cc_import(name = "capi_shared", shared_library = select({
    ":arm64": "jniLibs/arm64-v8a/libnux_capi.so",
    "//conditions:default": "jniLibs/x86_64/libnux_capi.so",
}))
cc_library(name = "capi", hdrs = ["include/nux_capi.generated.h"], includes = ["include"], deps = [":capi_shared"])
filegroup(name = "jni_libraries", srcs = glob(["jniLibs/**/*.so"]))
exports_files(["provenance.json"])
""")

_runtime = repository_rule(
    implementation = _runtime_impl,
    attrs = {
        "pin": attr.label(default = Label("//runtime:artifact.json")),
        "budget": attr.label(default = Label("//runtime:size-budget.json")),
        "verifier": attr.label(default = Label("//scripts/bazel:runtime_artifacts.py")),
    },
    environ = ["NUXIE_RUNTIME_USE_LOCAL"],
)

def _extension_impl(ctx):
    _runtime(name = "nuxie_runtime_android")

runtime_artifacts = module_extension(implementation = _extension_impl)
