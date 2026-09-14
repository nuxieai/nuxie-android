#!/usr/bin/env python3
"""Build an R8 consumer of the release AAR and exercise JNI logging on a device.

Usage: python3 scripts/test-minified-native-logging.py emulator-5558
The fixture intentionally uses internal bridge calls to provoke native failures;
this is qualification machinery, not an application integration example.
"""
import os
from pathlib import Path
import subprocess
import sys
import time
import tomllib
import uuid
import zipfile

root = Path(__file__).resolve().parents[1]
serial = sys.argv[1]
versions = tomllib.loads((root / 'gradle/libs.versions.toml').read_text())['versions']
aar = root / 'nuxie-android/build/outputs/aar/nuxie-android-release.aar'
with zipfile.ZipFile(aar) as archive:
    assert 'nativeWarning(java.lang.String, int, byte[], byte[])' in archive.read('proguard.txt').decode()
fixture = root / 'build/minified-native-logging-consumer'
fixture.mkdir(parents=True, exist_ok=True)
(fixture / 'settings.gradle.kts').write_text('''pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "minified-native-logging-consumer"
''')
(fixture / 'build.gradle.kts').write_text('''plugins { id("com.android.application") version "%s" }
android {
  namespace = "ai.nuxie.loggingconsumer"
  compileSdk = 36
  compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
  defaultConfig { applicationId = "ai.nuxie.loggingconsumer"; minSdk = 23; targetSdk = 36 }
  buildTypes { getByName("release") {
    isMinifyEnabled = true
    signingConfig = signingConfigs.getByName("debug")
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
  } }
}
dependencies {
  implementation(files("%s"))
  implementation("org.jetbrains.kotlin:kotlin-stdlib:%s")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:%s")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:%s")
  implementation("androidx.sqlite:sqlite-framework:%s")
  implementation("com.android.billingclient:billing:%s")
}
''' % (versions['agp'], aar.as_posix(), versions['kotlin'], versions['coroutines'],
       versions['serialization-json'], versions['sqlite'], versions['billing']))
(fixture / 'gradle.properties').write_text('android.useAndroidX=true\norg.gradle.jvmargs=-Xmx2g\n')
source = fixture / 'src/main/java/ai/nuxie/loggingconsumer/ProbeActivity.java'
source.parent.mkdir(parents=True, exist_ok=True)
source.write_text('''package ai.nuxie.loggingconsumer;
import android.app.Activity;
import android.os.Bundle;
import android.system.Os;
import android.util.Log;
import ai.nuxie.sdk.Nuxie;
import ai.nuxie.sdk.NuxieConfiguration;
import ai.nuxie.sdk.LogLevel;
import ai.nuxie.sdk.runtime.NuxieRuntimeBridge;
import java.net.URL;

public final class ProbeActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    String marker = getIntent().getStringExtra("marker");
    String mode = getIntent().getStringExtra("mode");
    new Thread(() -> {
      try {
        NuxieConfiguration configuration = new NuxieConfiguration("pk_test_logging_consumer");
        configuration.getTestingOverrides().setApiEndpoint(new URL("http://127.0.0.1:9"));
        configuration.setLogLevel(mode.equals("disabled") ? LogLevel.NONE : LogLevel.WARN);
        configuration.setRedactSensitiveData(!mode.equals("raw"));
        Nuxie.INSTANCE.setup(getApplicationContext(), configuration);
        String stderr = Os.readlink("/proc/self/fd/2");
        if (!NuxieRuntimeBridge.INSTANCE.isAvailable()) throw new AssertionError("Native library unavailable");
        if (!stderr.equals(Os.readlink("/proc/self/fd/2"))) throw new AssertionError("stderr redirected");
        long renderer = NuxieRuntimeBridge.INSTANCE.nativeRendererNewAndroidVulkan(16, 16);
        if (renderer == 0) throw new AssertionError("Renderer unavailable");
        try {
          Log.i("NuxieMinifiedProbe", marker + " begin");
          if (NuxieRuntimeBridge.INSTANCE.nativeFileNew(renderer, new byte[] {1, 2, 3}) != 0)
            throw new AssertionError("Invalid input accepted");
          Log.i("NuxieMinifiedProbe", marker + " end");
        } finally { NuxieRuntimeBridge.INSTANCE.nativeRendererFree(renderer); }
        Log.i("NuxieMinifiedProbe", marker + " passed");
      } catch (Throwable error) { Log.e("NuxieMinifiedProbe", marker + " failed", error); }
    }, "native-logging-probe").start();
  }
}
''')
(fixture / 'src/main/AndroidManifest.xml').write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android">
<uses-permission android:name="android.permission.INTERNET" />
<application android:theme="@android:style/Theme.Material.Light.NoActionBar" android:usesCleartextTraffic="true">
<activity android:name=".ProbeActivity" android:exported="true" />
</application></manifest>
''')
with (fixture / 'build.log').open('w') as output:
    subprocess.run([str(root / 'gradlew'), '--no-watch-fs', '-p', str(fixture), 'assembleRelease'],
                   cwd=root, check=True, stdout=output, stderr=subprocess.STDOUT)
mapping = (fixture / 'build/outputs/mapping/release/mapping.txt').read_text()
assert 'ai.nuxie.sdk.logging.NuxieLog -> ai.nuxie.sdk.logging.NuxieLog:' in mapping
assert 'nativeWarning(java.lang.String,int,byte[],byte[])' in mapping
assert any(line.startswith('ai.nuxie.sdk.') and ' -> ' in line and
           line.split(' -> ')[0] != line.split(' -> ')[1].rstrip(':') for line in mapping.splitlines()), 'SDK was not obfuscated'
adb = str(Path(os.environ['ANDROID_HOME']) / 'platform-tools/adb')
def run(*args):
    return subprocess.run([adb, '-s', serial, *args], check=True, capture_output=True, text=True).stdout
package = 'ai.nuxie.loggingconsumer'
apk = fixture / 'build/outputs/apk/release/minified-native-logging-consumer-release.apk'
run('install', '-r', str(apk))
try:
    for mode in ('disabled', 'redacted', 'raw'):
        run('shell', 'am', 'force-stop', package)
        marker = str(uuid.uuid4())
        run('shell', 'am', 'start', '-n', package + '/.ProbeActivity', '--es', 'mode', mode, '--es', 'marker', marker)
        deadline = time.monotonic() + 60
        log = ''
        while time.monotonic() < deadline:
            log = run('logcat', '-d', '-v', 'brief', 'Nuxie:V', 'NuxieMinifiedProbe:V', '*:S')
            if marker + ' passed' in log or marker + ' failed' in log:
                break
            time.sleep(0.25)
        (fixture / (mode + '.log')).write_text(log)
        assert marker + ' passed' in log, f'{mode} failed or timed out; see {fixture / (mode + ".log")}'
        section = log.split(marker + ' begin', 1)[1].split(marker + ' end', 1)[0]
        warnings = [line for line in section.splitlines() if 'Native runtime call failed operation=file_import_android_vulkan' in line]
        if mode == 'disabled':
            assert not warnings, section
        else:
            assert len(warnings) == 1, section
            assert ('code=<redacted hmac-sha256:' in warnings[0]) == (mode == 'redacted'), warnings
            assert ('details=<redacted hmac-sha256:' in warnings[0]) == (mode == 'redacted'), warnings
        print(f'Minified consumer: {mode} native policy and unchanged stderr passed.', flush=True)
finally:
    run('shell', 'am', 'force-stop', package)
print(f'R8 release consumer qualification passed; evidence: {fixture}', flush=True)
