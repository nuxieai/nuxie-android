package ai.nuxie.sdk.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build

/** Runtime-native kind values from `NuxFileAssetDescriptorView`. */
internal enum class FileAssetKind(val nativeValue: Int) {
    IMAGE(0),
    FONT(1),
    AUDIO(2),
    BLOB(3),
    SCRIPT(4),
    SHADER(5),
    ;

    companion object {
        fun fromNativeValue(value: Int): FileAssetKind? = entries.firstOrNull {
            it.nativeValue == value
        }
    }
}

/** Complete file-order identity required by `NuxFileImportConfig.expected_assets`. */
internal data class ExpectedFileAsset(
    val ordinal: Int,
    val kind: FileAssetKind,
    val authoredId: Long?,
    val name: String,
    val fileExtension: String,
    val isEmbedded: Boolean,
    val hasContentsRecord: Boolean,
    val requiredProviderFlags: Int,
)

/** JNI construction shape; converted immediately to the enum-backed model. */
internal data class NativeExpectedFileAsset(
    val ordinal: Int,
    val kind: Int,
    val hasAuthoredId: Boolean,
    val authoredId: Long,
    val name: String,
    val fileExtension: String,
    val isEmbedded: Boolean,
    val hasContentsRecord: Boolean,
    val requiredProviderFlags: Int,
)

/** Tightly packed RGBA8 premultiplied-sRGB pixels returned to native import. */
internal data class DecodedImage(
    val width: Int,
    val height: Int,
    val rowBytes: Int,
    val pixels: ByteArray,
)

internal fun interface NuxImageDecoder {
    fun decode(
        encoded: ByteArray,
        maximumDimension: Int,
        maximumDecodedBytes: Long,
    ): DecodedImage?
}

/** Android host decoder used synchronously by `NuxAssetHooks.decode_image`. */
internal object AndroidImageDecoder : NuxImageDecoder {
    override fun decode(
        encoded: ByteArray,
        maximumDimension: Int,
        maximumDecodedBytes: Long,
    ): DecodedImage? {
        if (maximumDimension <= 0 || maximumDecodedBytes < 0) return null
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
            inScaled = false
        }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        // The bounds pass enforces the ceiling before pixel allocation from
        // the same header used by the decoder.
        if (width <= 0 || height <= 0 ||
            width > maximumDimension || height > maximumDimension
        ) {
            return null
        }
        val tightRowBytes = width.toLong() * RGBA_BYTES_PER_PIXEL
        val tightByteCount = tightRowBytes * height.toLong()
        if (tightRowBytes > Int.MAX_VALUE || tightByteCount > Int.MAX_VALUE ||
            tightByteCount > maximumDecodedBytes
        ) {
            return null
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = true
            inScaled = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
            }
        }
        val bitmap = BitmapFactory.decodeByteArray(
            encoded,
            0,
            encoded.size,
            decodeOptions,
        ) ?: return null
        return try {
            // Revalidate after decode as defense against decoder divergence.
            if (bitmap.width != width || bitmap.height != height ||
                bitmap.config != Bitmap.Config.ARGB_8888
            ) {
                return null
            }
            if (bitmap.rowBytes < tightRowBytes ||
                bitmap.rowBytes.toLong() * height.toLong() > maximumDecodedBytes
            ) {
                return null
            }
            val row = IntArray(width)
            val tightlyPacked = ByteArray(tightByteCount.toInt())
            repeat(height) { y ->
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                row.forEachIndexed { x, color ->
                    val alpha = color ushr 24
                    val destination = (y * width + x) * RGBA_BYTES_PER_PIXEL.toInt()
                    tightlyPacked[destination] = premultiply(color ushr 16, alpha).toByte()
                    tightlyPacked[destination + 1] = premultiply(color ushr 8, alpha).toByte()
                    tightlyPacked[destination + 2] = premultiply(color, alpha).toByte()
                    tightlyPacked[destination + 3] = alpha.toByte()
                }
            }
            DecodedImage(
                width = width,
                height = height,
                rowBytes = tightRowBytes.toInt(),
                pixels = tightlyPacked,
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun premultiply(channel: Int, alpha: Int): Int =
        ((channel and 0xff) * alpha + 127) / 255

    private const val RGBA_BYTES_PER_PIXEL = 4L
}
