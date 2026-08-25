package ai.nuxie.example

import ai.nuxie.sdk.presentation.debug.ExperienceRenderProbe
import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Emulator-only smoke for the configured external-image import path. */
class AssetSmokeActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    runCatching {
      val rivFile = copyFixtureToCache(RIV_ASSET)
      val imageFile = copyFixtureToCache(IMAGE_ASSET)
      val rivBytes = rivFile.readBytes()
      val imageBytes = imageFile.readBytes()
      val imageSha256 = imageBytes.sha256()
      val imageKey = "assets/sha256/$imageSha256.png"
      val inspectedImage = ExperienceRenderProbe.inspectImageAsset(rivBytes)
      val descriptor = syntheticDescriptor(
        imageKey = imageKey,
        imageSha256 = imageSha256,
        imageSizeBytes = imageBytes.size.toLong(),
        imageName = inspectedImage.name,
        authoredId = inspectedImage.authoredId,
      )

      ExperienceRenderProbe.createView(
        context = this,
        rivBytes = rivBytes,
        descriptorJson = descriptor.toString(),
        artifactsByKey = mapOf(imageKey to imageFile),
        onFirstFrame = { Log.i(LOG_TAG, "first frame") },
        onFailure = ::showFailure,
      )
    }.fold(
      onSuccess = ::setContentView,
      onFailure = { error ->
        showFailure(error.message ?: "Asset smoke setup failed", error)
      },
    )
  }

  private fun copyFixtureToCache(assetPath: String): File {
    val fixtureDirectory = File(cacheDir, "asset-smoke").apply { mkdirs() }
    val destination = File(fixtureDirectory, File(assetPath).name)
    assets.open(assetPath).use { input ->
      destination.outputStream().use(input::copyTo)
    }
    return destination
  }

  private fun syntheticDescriptor(
    imageKey: String,
    imageSha256: String,
    imageSizeBytes: Long,
    imageName: String,
    authoredId: Long,
  ): JSONObject {
    val image = JSONObject()
      .put("kind", "image")
      .put("key", imageKey)
      .put("sha256", imageSha256)
      .put("sizeBytes", imageSizeBytes)
      .put("contentType", "image/png")
      .put("riveAssetId", authoredId)
      .put("riveUniqueName", "$imageName-$authoredId")
      .put("required", true)
    return JSONObject().put(
      "render",
      JSONObject().put("assets", JSONArray().put(image)),
    )
  }

  private fun showFailure(message: String, error: Throwable? = null) {
    Log.e(LOG_TAG, message, error)
    runOnUiThread {
      setContentView(
        TextView(this).apply {
          layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
          )
          gravity = Gravity.CENTER
          text = "Asset smoke failed:\n$message"
        },
      )
    }
  }

  private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(this)
    .joinToString("") { byte -> "%02x".format(byte) }

  private companion object {
    const val LOG_TAG = "AssetSmoke"
    const val RIV_ASSET = "asset-smoke/external-image.riv"
    const val IMAGE_ASSET = "asset-smoke/external-image.png"
  }
}
