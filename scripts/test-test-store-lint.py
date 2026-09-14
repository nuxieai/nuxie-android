#!/usr/bin/env python3
"""Prove that a consumer of the release AAR receives the Test Store lint guard.

Run :nuxie-android:assembleRelease first. Fixtures and logs stay under build/.
"""
import io
import os
from pathlib import Path
import subprocess
import tomllib
import zipfile
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
aar = root / 'nuxie-android/build/outputs/aar/nuxie-android-release.aar'
with zipfile.ZipFile(aar) as archive:
    with zipfile.ZipFile(io.BytesIO(archive.read('lint.jar'))) as lint:
        registry = lint.read('META-INF/services/com.android.tools.lint.client.api.IssueRegistry').decode().strip()
        assert registry == 'ai.nuxie.lint.NuxieIssueRegistry', registry
        assert 'ai/nuxie/lint/TestStoreDetector.class' in lint.namelist()

versions = tomllib.loads((root / 'gradle/libs.versions.toml').read_text())['versions']
fixture = root / 'build/test-store-lint-consumer'
fixture.mkdir(parents=True, exist_ok=True)
(fixture / 'settings.gradle.kts').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "test-store-lint-consumer"
''')
# A file dependency deliberately exercises the shipped lint.jar without project wiring.
(fixture / 'build.gradle.kts').write_text('''plugins { id("com.android.application") version "%s"; id("org.jetbrains.kotlin.android") version "%s" }
android {
  namespace = "ai.nuxie.lintconsumer"
  compileSdk = 36
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  kotlinOptions { jvmTarget = "17" }
  defaultConfig { applicationId = "ai.nuxie.lintconsumer"; minSdk = 23; targetSdk = 36 }
  buildFeatures { buildConfig = true }
  lint { checkOnly += "NuxieTestStoreRelease"; abortOnError = true }
}
dependencies { implementation(files("%s")); implementation("org.jetbrains.kotlin:kotlin-stdlib:%s") }
''' % (versions['agp'], versions['kotlin'], aar.as_posix().replace('"', '\\"'), versions['kotlin']))
(fixture / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n')
source = fixture / 'src/main/java/ai/nuxie/lintconsumer/Configuration.java'
source.parent.mkdir(parents=True, exist_ok=True)
manifest = fixture / 'src/main/AndroidManifest.xml'
manifest.write_text('<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application /></manifest>\n')

for name, expression, should_pass, kotlin in (
    ('unsafe-literal', 'true', False, False),
    ('safe-debug-gate', 'BuildConfig.DEBUG', True, False),
    ('unsafe-kotlin-property', 'true', False, True),
    ('safe-kotlin-debug-gate', 'BuildConfig.DEBUG', True, True),
):
    source.with_suffix('.java').unlink(missing_ok=True)
    source.with_suffix('.kt').unlink(missing_ok=True)
    if kotlin:
        source.with_suffix('.kt').write_text('''package ai.nuxie.lintconsumer
import ai.nuxie.sdk.NuxieConfiguration
class Configuration {
  fun create() = NuxieConfiguration("pk_test_lint_fixture").apply { testStoreEnabled = %s }
}
''' % expression)
    else:
        source.write_text('''package ai.nuxie.lintconsumer;
import ai.nuxie.sdk.NuxieConfiguration;
public final class Configuration {
  public NuxieConfiguration create() {
    NuxieConfiguration c = new NuxieConfiguration("pk_test_lint_fixture");
    c.setTestStoreEnabled(%s);
    return c;
  }
}
''' % expression)
    log = fixture / (name + '.log')
    with log.open('w') as output:
        result = subprocess.run([str(root / 'gradlew'), '--no-watch-fs', '-p', str(fixture), 'lintRelease'],
                                cwd=root, env=os.environ, text=True, stdout=output, stderr=subprocess.STDOUT)
    report = fixture / 'build/reports/lint-results-release.xml'
    issues = ET.parse(report).getroot().findall("issue[@id='NuxieTestStoreRelease']") if report.exists() else []
    if should_pass:
        assert result.returncode == 0 and not issues, f'{name} failed: {log}'
    else:
        assert result.returncode != 0 and len(issues) == 1, f'{name} did not reject unsafe configuration: {log}'
    print(f'{name}: expected result verified ({log})', flush=True)
print('Release AAR lint publication and consumer enforcement passed.', flush=True)
