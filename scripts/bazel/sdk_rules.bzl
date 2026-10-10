"""Package SDK-owned language outputs with imported immutable runtime products."""

load("@rules_android//providers:providers.bzl", "AndroidLibraryAarInfo")
load("@rules_java//java/common:java_info.bzl", "JavaInfo")
load("@rules_android//rules:utils.bzl", "ANDROID_SDK_TOOLCHAIN_TYPE", "get_android_sdk")

def _abis_impl(_settings, attr):
    return {
        "arm64-v8a": {"//command_line_option:platforms": [str(attr.arm64_platform)]},
        "x86_64": {"//command_line_option:platforms": [str(attr.x86_64_platform)]},
    }

_abis = transition(implementation = _abis_impl, inputs = [], outputs = ["//command_line_option:platforms"])

def _aar_impl(ctx):
    base = ctx.attr.sdk[AndroidLibraryAarInfo].aar
    if base == None:
        fail("SDK Android library did not produce its resource AAR")
    jars = ctx.attr.lint[JavaInfo].runtime_output_jars
    if len(jars) != 1:
        fail("Expected one SDK lint JAR")
    output = ctx.actions.declare_file(ctx.label.name + ".aar")
    args = ctx.actions.args()
    args.add("aar")
    args.add("--base", base)
    args.add("--lint", jars[0])
    args.add("--consumer-rules", ctx.file.consumer_rules)
    args.add("--output", output)
    inputs = [base, jars[0], ctx.file.consumer_rules]
    for abi, targets in ctx.split_attr.shim.items():
        files = targets[DefaultInfo].files.to_list()
        libraries = [file for file in files if file.basename == "libnuxie_runtime_android.so"]
        if len(libraries) != 1:
            fail("Expected exactly one JNI adapter for " + abi)
        args.add("--shim", abi + "=" + libraries[0].path)
        inputs.append(libraries[0])
    for file in ctx.files.runtime:
        args.add("--runtime", file)
        inputs.append(file)
    ctx.actions.run(
        executable = ctx.executable._packager,
        arguments = [args],
        inputs = inputs,
        outputs = [output],
        mnemonic = "NuxieAndroidAar",
    )
    return [DefaultInfo(files = depset([output]))]

sdk_aar = rule(
    implementation = _aar_impl,
    attrs = {
        "sdk": attr.label(mandatory = True, providers = [AndroidLibraryAarInfo]),
        "lint": attr.label(mandatory = True, providers = [JavaInfo]),
        "shim": attr.label(mandatory = True, cfg = _abis),
        "runtime": attr.label(mandatory = True, allow_files = True),
        "consumer_rules": attr.label(mandatory = True, allow_single_file = True),
        "arm64_platform": attr.label(default = Label("//:android_arm64")),
        "x86_64_platform": attr.label(default = Label("//:android_x86_64")),
        "_packager": attr.label(default = Label("//scripts/bazel:package_aar"), executable = True, cfg = "exec"),
        "_allowlist_function_transition": attr.label(default = "@bazel_tools//tools/allowlists/function_transition_allowlist"),
    },
)

def _docs_impl(ctx):
    output = ctx.actions.declare_file(ctx.label.name + ".jar")
    directory = ctx.actions.declare_directory(ctx.label.name + "_html")
    config = ctx.actions.declare_file(ctx.label.name + ".json")
    jars = ctx.attr.sdk[JavaInfo].transitive_compile_time_jars.to_list()
    jars.append(get_android_sdk(ctx).android_jar)
    plugins = depset(transitive = [dep[JavaInfo].transitive_runtime_jars for dep in ctx.attr.plugins]).to_list()
    ctx.actions.write(config, json.encode({
        "moduleName": "Nuxie Android SDK",
        "outputDir": directory.path,
        "offlineMode": True,
        "sourceSets": [{
            "sourceSetID": {"scopeId": "nuxie-android", "sourceSetName": "main"},
            "sourceRoots": [file.path for file in ctx.files.srcs],
            "classpath": [file.path for file in jars],
            "documentedVisibilities": ["PUBLIC", "PROTECTED"],
            "analysisPlatform": "jvm",
            "languageVersion": "2.0",
            "apiVersion": "2.0",
            "jdkVersion": 17,
            "noStdlibLink": True,
            "noJdkLink": True,
        }],
        "pluginsClasspath": [file.path for file in plugins],
    }))
    ctx.actions.run(
        executable = ctx.attr._dokka[DefaultInfo].files_to_run,
        arguments = [config.path],
        inputs = ctx.files.srcs + jars + plugins + [config],
        outputs = [directory],
        mnemonic = "NuxieAndroidDocs",
    )
    ctx.actions.run(
        executable = ctx.attr._packager[DefaultInfo].files_to_run,
        arguments = ["directory", "--input", directory.path, "--output", output.path],
        inputs = [directory],
        outputs = [output],
        mnemonic = "NuxieAndroidDocsJar",
    )
    return [DefaultInfo(files = depset([output]))]

sdk_docs = rule(
    implementation = _docs_impl,
    attrs = {
        "sdk": attr.label(mandatory = True, providers = [JavaInfo]),
        "srcs": attr.label_list(allow_files = [".kt", ".java"]),
        "plugins": attr.label_list(providers = [JavaInfo]),
        "_dokka": attr.label(default = Label("//scripts/bazel:dokka_cli"), executable = True, cfg = "exec"),
        "_packager": attr.label(default = Label("//scripts/bazel:package_aar"), executable = True, cfg = "exec"),
    },
    toolchains = [ANDROID_SDK_TOOLCHAIN_TYPE],
)

def _jvm_test_impl(ctx):
    launcher = ctx.actions.declare_file(ctx.label.name + ".sh")
    binary = ctx.executable.binary
    ctx.actions.write(launcher, """#!/usr/bin/env bash
set -euo pipefail
root="$TEST_SRCDIR/$TEST_WORKSPACE"
# Gradle's fixture tests run from the SDK module. Robolectric's generated
# manifest/resource paths run from the Bazel package. Give both their existing
# relative paths using links to declared runfiles in an isolated test directory.
work="$TEST_TMPDIR/sdk-cwd"
cwd="$work/nuxie-android"
mkdir -p "$cwd"
for entry in "$TEST_SRCDIR"/*; do
  ln -s "$entry" "$work/$(basename "$entry")"
done
for entry in "$root"/*; do
  name="$(basename "$entry")"
  if [[ "$name" != nuxie-android ]]; then
    ln -s "$entry" "$cwd/$name"
    [[ -e "$work/$name" ]] || ln -s "$entry" "$work/$name"
  fi
done
for entry in "$root/nuxie-android"/*; do
  name="$(basename "$entry")"
  [[ -e "$cwd/$name" ]] || ln -s "$entry" "$cwd/$name"
done
cd "$cwd"
exec "$root/%s" "$@"
""" % binary.short_path, is_executable = True)
    runfiles = ctx.runfiles(files = [binary]).merge(ctx.attr.binary[DefaultInfo].default_runfiles)
    return [DefaultInfo(executable = launcher, runfiles = runfiles)]

sdk_jvm_test = rule(
    implementation = _jvm_test_impl,
    attrs = {"binary": attr.label(executable = True, cfg = "target", mandatory = True)},
    test = True,
)

def _assets_impl(ctx):
    files = {}
    for source in ctx.files.srcs:
        path = source.short_path
        prefix = "nuxie-android/src/androidTest/assets/" if path.startswith("nuxie-android/") else "fixtures/"
        if not path.startswith(prefix):
            fail("Unsupported instrumentation asset: " + path)
        relative = path[len(prefix):]
        if relative in files:
            fail("Duplicate instrumentation asset: " + relative)
        output = ctx.actions.declare_file(ctx.label.name + "/" + relative)
        ctx.actions.symlink(output = output, target_file = source)
        files[relative] = output
    return [DefaultInfo(files = depset(files.values()))]

sdk_assets = rule(implementation = _assets_impl, attrs = {"srcs": attr.label_list(allow_files = True)})
