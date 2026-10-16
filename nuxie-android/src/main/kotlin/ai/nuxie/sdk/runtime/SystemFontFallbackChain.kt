package ai.nuxie.sdk.runtime

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.Locale
import kotlin.math.abs

/** Android's ordered unnamed families, as in rive-android FontHelper. No font bytes are copied. */
internal object SystemFontFallbackChain {
    data class Face(val path: String, val index: Int)
    data class Font(val name: String, val index: Int, val weight: Int, val style: String, val fallbackFor: String?)
    data class Family(val name: String?, val lang: String?, val variant: String?, val fonts: List<Font>)

    private val directories = listOf("/system/fonts", "/product/fonts", "/system/product/fonts",
        "/vendor/fonts", "/system/vendor/fonts", "/odm/fonts")
    private var cachedFamilies: List<Family>? = null

    private fun families(): List<Family> = cachedFamilies ?: run {
        val file = listOf("/system/etc/fonts.xml", "/system/etc/system_fonts.xml", "/system/etc/system_fallback.xml")
            .asSequence().map(::File).firstOrNull { it.isFile }
            ?: throw java.io.IOException("System font configuration unavailable")
        val parsed = file.inputStream().use(::parse)
        if (parsed.isEmpty()) throw java.io.IOException("Empty system font configuration")
        parsed.also { cachedFamilies = it }
    }

    @Synchronized
    fun load(locale: Locale): List<Face> = order(families(), locale) { resolvePath(it, directories.map(::File)) }

    fun resolvePath(name: String, roots: List<File>): String? {
        // Resolve both the candidate and trusted roots before checking containment.
        // Reject traversal outright, including paths which happen to end inside a root.
        if (name.split('/').any { it == ".." }) return null
        return try {
            val allowed = roots.map { it.canonicalFile }
            val configured = File(name)
            val candidates = if (configured.isAbsolute) sequenceOf(configured) else roots.asSequence().map { File(it, name) }
            candidates.map { it.canonicalFile }.firstOrNull { file ->
                file.isFile && allowed.any { file.path.startsWith("${it.path}/") }
            }?.path
        } catch (_: java.io.IOException) { null }
    }

    fun order(families: List<Family>, locale: Locale, path: (String) -> String?): List<Face> {
        fun regular(family: Family): Face? = family.fonts.asSequence()
            .filter { it.style == "normal" && it.fallbackFor == null }
            .mapNotNull { font -> path(font.name)?.let { font to it } }
            .minByOrNull { abs(it.first.weight - 400) }?.let { Face(it.second, it.first.index) }
        val defaults = families.firstOrNull { it.name == "sans-serif" }?.let(::regular)
        val (preferred, others) = families.filter { it.name.isNullOrBlank() && it.variant != "elegant" }
            .partition { matches(it.lang, locale) }
        return (listOfNotNull(defaults) + (preferred + others).mapNotNull(::regular)).distinct()
    }

    fun matches(languages: String?, locale: Locale): Boolean {
        val script = locale.script.ifEmpty {
            if (locale.language == "zh") {
                if (locale.country in setOf("TW", "HK", "MO")) "Hant" else "Hans"
            } else ""
        }
        return languages?.split(',')?.any {
            val tag = Locale.forLanguageTag(it.trim())
            tag.language == locale.language && (tag.script.isEmpty() || tag.script.equals(script, true))
        } == true
    }

    fun parse(input: InputStream): List<Family> {
        data class Alias(val name: String, val target: String, val weight: Int?)
        val aliases = mutableListOf<Alias>()
        val parser = Xml.newPullParser().apply { setInput(input, null) }
        val result = mutableListOf<Family>()
        var name: String? = null
        var lang: String? = null
        var variant: String? = null
        var fonts = mutableListOf<Font>()
        var inFamily = false
        var ignored = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) when (parser.name) {
                "family" -> {
                    inFamily = true
                    ignored = parser.getAttributeValue(null, "ignore") in listOf("true", "1")
                    name = parser.getAttributeValue(null, "name")
                    lang = parser.getAttributeValue(null, "lang")
                    variant = parser.getAttributeValue(null, "variant")
                    fonts = mutableListOf()
                }
                "alias" -> {
                    val alias = parser.getAttributeValue(null, "name")
                    val target = parser.getAttributeValue(null, "to")
                    if (alias != null && target != null) aliases += Alias(alias, target,
                        parser.getAttributeValue(null, "weight")?.toIntOrNull())
                }
                "font" -> if (inFamily) {
                    val index = parser.getAttributeValue(null, "index")?.toIntOrNull() ?: 0
                    val weight = parser.getAttributeValue(null, "weight")?.toIntOrNull() ?: 400
                    val style = parser.getAttributeValue(null, "style") ?: "normal"
                    val fallbackFor = parser.getAttributeValue(null, "fallbackFor")
                    // <axis> children may precede or follow the filename. nextText() cannot read these.
                    val text = StringBuilder()
                    val depth = parser.depth
                    while (!(parser.next() == XmlPullParser.END_TAG && parser.depth == depth)) {
                        if (parser.eventType == XmlPullParser.END_DOCUMENT) error("Unterminated system font")
                        if (parser.eventType == XmlPullParser.TEXT && parser.depth == depth) text.append(parser.text)
                    }
                    if (text.isNotBlank() && index >= 0) fonts += Font(text.toString().trim(), index, weight, style, fallbackFor)
                }
            }
            if (parser.eventType == XmlPullParser.END_TAG && parser.name == "family") {
                if (!ignored && fonts.isNotEmpty()) result += Family(name, lang, variant, fonts)
                inFamily = false
            }
            parser.next()
        }
        // OEM configurations may alias sans-serif, including through another alias.
        // Resolve only existing targets, preserve real families, and terminate cycles.
        var progress = true
        while (progress && aliases.isNotEmpty()) {
            progress = false
            val remaining = aliases.iterator()
            while (remaining.hasNext()) {
                val alias = remaining.next()
                if (result.any { it.name == alias.name }) {
                    remaining.remove()
                    continue
                }
                val target = result.firstOrNull { it.name == alias.target } ?: continue
                val selected = target.fonts.filter { alias.weight == null || it.weight == alias.weight }
                if (selected.isNotEmpty()) result += target.copy(name = alias.name, fonts = selected)
                remaining.remove()
                progress = true
            }
        }
        return result
    }
}
