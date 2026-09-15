#!/usr/bin/env python3
"""Build an independent app using only the SDK's Maven coordinate.

Publish to staging first. The optional repository URL also supports qualification
of a remotely published release; no project dependency or manual SDK dependency
list is used. Requires Python 3.11+ and the Android SDK.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tomllib
import xml.etree.ElementTree as ET
import zipfile


def main():
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    parser.add_argument('--repository', default=(root / 'build/maven-repository').as_uri())
    args = parser.parse_args()
    versions = tomllib.loads((root / 'gradle/libs.versions.toml').read_text())['versions']
    fixture = root / 'build/maven-consumer'
    fixture.mkdir(parents=True, exist_ok=True)
    # JSON strings are valid quoted Kotlin strings after escaping interpolation.
    def kotlin(value):
        return json.dumps(value).replace('$', '\\$')
    (fixture / 'settings.gradle.kts').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
    dependencyResolutionManagement {
      repositories {
        exclusiveContent {
          forRepository { maven { url = uri(%s) } }
          filter { includeModule("ai.nuxie", "nuxie-android") }
        }
        google(); mavenCentral()
      }
    }
    rootProject.name = "nuxie-maven-consumer"
    ''' % kotlin(args.repository))
    (fixture / 'build.gradle.kts').write_text('''plugins {
      id("com.android.application") version "%s"
      id("org.jetbrains.kotlin.android") version "%s"
    }
    android {
      namespace = "ai.nuxie.mavenconsumer"
      compileSdk = 36
      defaultConfig { applicationId = "ai.nuxie.mavenconsumer"; minSdk = 23; targetSdk = 36 }
      compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
      kotlinOptions { jvmTarget = "17" }
      buildFeatures { buildConfig = true }
      buildTypes { getByName("release") { isMinifyEnabled = true; proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) } }
      lint { checkOnly += "NuxieTestStoreRelease"; abortOnError = true }
    }
    dependencies { implementation(%s) }
    ''' % (versions['agp'], versions['kotlin'], kotlin('ai.nuxie:nuxie-android:' + args.version)))
    (fixture / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n')
    source = fixture / 'src/main/java/ai/nuxie/mavenconsumer/Consumer.kt'
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text('''package ai.nuxie.mavenconsumer
    import android.app.Application
    import ai.nuxie.sdk.Nuxie
    import ai.nuxie.sdk.NuxieConfiguration
    import ai.nuxie.sdk.billing.StoreProduct
    import ai.nuxie.sdk.features.FeatureInfo
    import com.android.billingclient.api.ProductDetails
    class Consumer : Application() {
      override fun onCreate() {
        super.onCreate()
        val config = NuxieConfiguration("pk_test_maven_consumer").apply {
          testStoreEnabled = BuildConfig.DEBUG
        }
        Nuxie.setup(this, config)
      }
      fun featureCount(info: FeatureInfo): Int = info.all.value.size
      fun playProduct(product: StoreProduct): ProductDetails? = product.rawProduct
    }
    ''')
    (fixture / 'src/main/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
      <uses-permission android:name="android.permission.INTERNET" />
      <application android:name=".Consumer" />
    </manifest>
    ''')
    log = fixture / 'qualification.log'
    with log.open('w') as output:
        result = subprocess.run([str(root / 'gradlew'), '--no-watch-fs', '-p', str(fixture),
                                 '--refresh-dependencies', 'assembleRelease', 'lintRelease'],
                                cwd=root, env=os.environ, stdout=output, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f'Maven consumer failed: {log}')
    apk = fixture / 'build/outputs/apk/release/nuxie-maven-consumer-release-unsigned.apk'
    with zipfile.ZipFile(apk) as archive:
        native = {name for name in archive.namelist() if name.startswith('lib/') and name.endswith('.so')}
    expected = {f'lib/{abi}/{library}' for abi in ('arm64-v8a', 'x86_64')
                for library in ('libnux_capi.so', 'libnuxie_runtime_android.so', 'libc++_shared.so')}
    if native != expected:
        raise RuntimeError(f'Consumer native inventory mismatch: expected {expected}, got {native}')
    safe_source = source.read_text()
    unsafe_log = fixture / 'unsafe-test-store.log'
    try:
        source.write_text(safe_source.replace('testStoreEnabled = BuildConfig.DEBUG', 'testStoreEnabled = true'))
        report = fixture / 'build/reports/lint-results-release.xml'
        report.unlink(missing_ok=True)
        with unsafe_log.open('w') as output:
            rejected = subprocess.run([str(root / 'gradlew'), '--no-watch-fs', '-p', str(fixture), 'lintRelease'],
                                      cwd=root, env=os.environ, stdout=output, stderr=subprocess.STDOUT)
        issues = ET.parse(report).getroot().findall("issue[@id='NuxieTestStoreRelease']") if report.exists() else []
        if rejected.returncode == 0 or len(issues) != 1:
            raise RuntimeError(f'Published lint did not reject unsafe Test Store: {unsafe_log}')
    finally:
        source.write_text(safe_source)
    settings = fixture / 'settings.gradle.kts'
    selected_settings = settings.read_text()
    empty = fixture / 'empty-repository'
    empty.mkdir(exist_ok=True)
    missing_log = fixture / 'missing-selected-repository.log'
    try:
        missing_settings = selected_settings.replace(kotlin(args.repository), kotlin(empty.as_uri()))
        missing_settings = missing_settings.replace('google(); mavenCentral()\n',
            'google(); mavenCentral(); maven { url = uri(' + kotlin(args.repository) + ') }\n')
        settings.write_text(missing_settings)
        with missing_log.open('w') as output:
            missing = subprocess.run([str(root / 'gradlew'), '--no-watch-fs', '-p', str(fixture),
                                      '--refresh-dependencies', 'checkReleaseAarMetadata'],
                                     cwd=root, env=os.environ, stdout=output, stderr=subprocess.STDOUT)
        reason = f'Could not find ai.nuxie:nuxie-android:{args.version}'
        if missing.returncode == 0 or reason not in missing_log.read_text():
            raise RuntimeError(f'Empty selected repository did not reject the SDK despite populated fallback: {missing_log}')
    finally:
        settings.write_text(selected_settings)
    print(f'Maven consumer compile, R8, native packaging, lint enforcement and exclusive repository selection passed: {log}; {unsafe_log}; {missing_log}')


if __name__ == '__main__':
    main()
