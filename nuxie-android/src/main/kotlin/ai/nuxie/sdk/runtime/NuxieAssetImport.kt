package ai.nuxie.sdk.runtime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.os.Build
import java.nio.ByteBuffer

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
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
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
            if (bitmap.width != width || bitmap.height != height ||
                bitmap.config != Bitmap.Config.ARGB_8888
            ) {
                return null
            }
            val sourceRowBytes = bitmap.rowBytes
            val sourceByteCount = sourceRowBytes.toLong() * height.toLong()
            if (sourceRowBytes < tightRowBytes || sourceByteCount > Int.MAX_VALUE ||
                sourceByteCount > maximumDecodedBytes
            ) {
                return null
            }
            val source = ByteBuffer.allocate(sourceByteCount.toInt())
            bitmap.copyPixelsToBuffer(source)
            val tightlyPacked = if (sourceRowBytes == tightRowBytes.toInt()) {
                source.array()
            } else {
                ByteArray(tightByteCount.toInt()).also { destination ->
                    val sourceBytes = source.array()
                    repeat(height) { row ->
                        sourceBytes.copyInto(
                            destination = destination,
                            destinationOffset = row * tightRowBytes.toInt(),
                            startIndex = row * sourceRowBytes,
                            endIndex = row * sourceRowBytes + tightRowBytes.toInt(),
                        )
                    }
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

    private const val RGBA_BYTES_PER_PIXEL = 4L
}
