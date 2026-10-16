package ai.nuxie.example;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import ai.nuxie.sdk.runtime.NuxieRuntimeBridge;
import ai.nuxie.sdk.runtime.*;
import java.util.Collections;
import java.util.List;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/** Opt-in release/R8 qualification; absent from ordinary example variants. */
public final class FontFallbackQualificationActivity extends Activity {
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    if (!NuxieRuntimeBridge.INSTANCE.isAvailable()) throw new AssertionError("Runtime unavailable");
    SystemFontFallback.INSTANCE.setSelectionObserver$nuxie_android_release((scalar, face) -> {
      throw new AssertionError("Release observer must be inert");
    });
    if (SystemFontFallback.INSTANCE.getSelectionObserver$nuxie_android_release() != null)
      throw new AssertionError("Release observer was installed");
    if (android.os.Build.VERSION.SDK_INT >= 26) qualifyPublishedColorEmoji();
    // Registration resolves the kept static callback from JNI_OnLoad. A direct
    // call also verifies that all native acquisition names survive shrinking.
    for (int scalar : new int[] { 0x65e5, 0x645, 0x5e9, 0x928, 0x25b7, 0x1f600 }) {
      if (SystemFontFallback.request(scalar, 0, 400) == 0) throw new AssertionError("Missing " + scalar);
    }
    if (SystemFontFallback.INSTANCE.requestFromNativeThreadForTesting(0x65e5, 0, 400) != 0)
      throw new AssertionError("Release instrumentation must be inert");
    SystemFontFallback.INSTANCE.suppressForTesting(true);
    if (SystemFontFallback.request(0x65e5, 3, 700) == 0) throw new AssertionError("Release suppression must be inert");
    android.util.Log.i("NuxieFontR8", "PASS: covering fonts, kept JNI callback, inert release instrumentation");
    TextView text = new TextView(this);
    text.setText("R8 font fallback qualification: PASS");
    setContentView(text);
  }

  private byte[] asset(String name) {
    try (InputStream stream = getAssets().open(name);
         ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096];
      int count;
      while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
      return bytes.toByteArray();
    } catch (java.io.IOException error) { throw new AssertionError(error); }
  }

  private void qualifyPublishedColorEmoji() {
    NuxieRuntime runtime = NuxieRuntime.Companion.getShared();
    byte[] bytes = asset("ordinary-roboto.nux");
    List<ExpectedFileAsset> catalog = runtime.inspectFileAssets(bytes);
    ExpectedFileAsset font = null;
    for (ExpectedFileAsset candidate : catalog) if (candidate.getKind() == FileAssetKind.FONT) font = candidate;
    if (font == null) throw new AssertionError("Fixture font missing");
    NuxieAndroidVulkanRenderer renderer = runtime.newAndroidVulkanRenderer(390, 844);
    if (renderer == null) throw new AssertionError("Vulkan renderer unavailable");
    try {
      NuxieRuntimeFile file = runtime.importFile(renderer, bytes, catalog,
          Collections.singletonMap(font.getOrdinal(), asset("ordinary-latin.ttf")), AndroidImageDecoder.INSTANCE, false, null);
      if (file == null) throw new AssertionError("Published fixture import failed");
      try {
        NuxieRuntimeArtboard artboard = file.newArtboard("One");
        try {
          NuxieRuntimePlayer player = artboard.newPlayer(null);
          try {
            player.step(0.0);
            byte[] pixels = renderer.renderToCpuFrame(player, 0xff000000, 1f).getRgba();
            int color = 0;
            for (int y = 464; y < 568; y++) for (int x = 0; x < 390; x++) {
              int at = (y * 390 + x) * 4;
              int r = pixels[at] & 255, g = pixels[at + 1] & 255, b = pixels[at + 2] & 255;
              int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
              if (max > 100 && max - min > 40) color++;
            }
            if (color <= 100) throw new AssertionError("R8 published emoji must draw color ink: " + color);
            android.util.Log.i("NuxieFontR8", "Published runtime C callback: color pixels=" + color);
          } finally { player.close(); }
        } finally { artboard.close(); }
      } finally { file.close(); }
    } finally { renderer.close(); }
  }
}
