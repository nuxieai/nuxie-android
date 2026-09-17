package ai.nuxie.sdk.experiences

import android.annotation.TargetApi
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.TextRunShaper
import android.os.Build
import java.security.MessageDigest

internal class SystemFontException(val reason: Reason, cause: Throwable? = null) :
    IllegalStateException(reason.code, cause) {
    enum class Reason(val code: String) {
        UNSUPPORTED_REQUEST("system_font.unsupported_request"),
        FACE_UNAVAILABLE("system_font.face_unavailable"),
        DATA_UNUSABLE("system_font.data_unusable"),
    }
}

/** Device data only. A candidate enters the cache only after successful native import. */
internal data class SystemFontCandidate(
    val bytes: ByteArray,
    val digest: String,
    val sourceIdentity: String,
    val weight: Int,
)

internal object SystemFontProvider {
    internal data class Selection(val identity: String, val extract: () -> SystemFontCandidate)

    fun prepare(requirement: SystemFontRequirement): SystemFontCandidate = select(requirement).extract()

    private fun validate(requirement: SystemFontRequirement) {
        if (requirement.weight !in 100..900 || requirement.weight % 100 != 0 || requirement.style != "normal") {
            throw SystemFontException(SystemFontException.Reason.UNSUPPORTED_REQUEST)
        }
    }

    fun typeface(requirement: SystemFontRequirement): Typeface {
        validate(requirement)
        if (Build.VERSION.SDK_INT < 28) {
            throw SystemFontException(SystemFontException.Reason.FACE_UNAVAILABLE)
        }
        return Typeface.create(Typeface.DEFAULT, requirement.weight, false)
    }

    fun select(requirement: SystemFontRequirement): Selection {
        validate(requirement)
        if (Build.VERSION.SDK_INT < 31) {
            throw SystemFontException(SystemFontException.Reason.FACE_UNAVAILABLE)
        }
        return fontOperation { selectFace(requirement.weight) }
    }

    private inline fun <T> fontOperation(operation: () -> T): T = try {
            operation()
        } catch (error: SystemFontException) {
            throw error
        } catch (error: SystemFontDataException) {
            throw SystemFontException(SystemFontException.Reason.DATA_UNUSABLE, error)
        } catch (error: RuntimeException) {
            throw SystemFontException(SystemFontException.Reason.FACE_UNAVAILABLE, error)
        }

    @TargetApi(31)
    private fun selectFace(weight: Int): Selection {
        val paint = Paint().apply {
            typeface = Typeface.create(Typeface.DEFAULT, weight, false)
            textSize = 17f
        }
        // Ask Android which face its default family actually selects, including
        // OEM replacements and static weight faces. No hardcoded Roboto path.
        val glyphs = TextRunShaper.shapeTextRun("a", 0, 1, 0, 1, 0f, 0f, false, paint)
        if (glyphs.glyphCount() != 1 || glyphs.getGlyphId(0) == 0) {
            throw SystemFontException(SystemFontException.Reason.FACE_UNAVAILABLE)
        }
        val font = glyphs.getFont(0)
        val sourceIdentity = "${Build.FINGERPRINT}:${font.sourceIdentifier}:${font.ttcIndex}"
        return Selection("$sourceIdentity:$weight:normal") {
            fontOperation {
                val buffer = font.buffer.duplicate().apply { position(0) }
                if (buffer.remaining() !in 1..(256 * 1024 * 1024)) {
                    throw SystemFontException(SystemFontException.Reason.DATA_UNUSABLE)
                }
                val source = ByteArray(buffer.remaining()).also(buffer::get)
                val bytes = SystemFontData.standalone(source, font.ttcIndex)
                val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                SystemFontCandidate(bytes, digest, sourceIdentity, weight)
            }
        }
    }
}
