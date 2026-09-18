package ai.nuxie.sdk.runtime

import android.content.Context
import android.os.Build
import android.view.accessibility.CaptioningManager
import java.util.Locale

internal object ExperienceVideoCaptionSelection {
    private val isoLanguages by lazy {
        Locale.getISOLanguages().mapNotNull { language ->
            runCatching { Locale(language).isO3Language to language }.getOrNull()
        }.toMap() + mapOf(
            // ISO 639-2 bibliographic codes retained by MP4 language metadata.
            // https://www.loc.gov/standards/iso639-2/php/code_list.php
            "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh",
            "cze" to "cs", "dut" to "nl", "fre" to "fr", "geo" to "ka", "ger" to "de",
            "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi", "may" to "ms",
            "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
        )
    }

    @Suppress("DEPRECATION")
    fun preferredLanguages(context: Context): List<String> {
        val caption = (context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager)?.locale
        val configuration = context.resources.configuration
        val languages = if (Build.VERSION.SDK_INT >= 24) {
            (0 until configuration.locales.size()).map { configuration.locales[it].toLanguageTag() }
        } else listOf(configuration.locale.toLanguageTag())
        return listOfNotNull(caption?.toLanguageTag()) + languages
    }

    fun index(languages: List<String?>, preferred: List<String>): Int? {
        if (languages.isEmpty()) return null
        fun normalized(value: String): String {
            val parts = value.trim().replace('_', '-').lowercase(Locale.ROOT).split('-').toMutableList()
            parts[0] = isoLanguages[parts[0]] ?: parts[0]
            return Locale.forLanguageTag(parts.joinToString("-")).toLanguageTag().lowercase(Locale.ROOT)
        }
        fun parts(tag: String): Pair<String, String?> {
            val values = tag.split('-')
            return values.first() to values.drop(1).firstOrNull { it.length == 4 && it.all(Char::isLetter) }
        }
        val available = languages.map { normalized(it.orEmpty()) }
        for (preference in preferred) {
            val desired = normalized(preference)
            val (language, script) = parts(desired)
            if (language.isEmpty() || language == "und") continue
            available.indexOf(desired).takeIf { it >= 0 }?.let { return it }
            val compatible = available.indices.filter {
                val (candidateLanguage, candidateScript) = parts(available[it])
                candidateLanguage == language && (script == null || candidateScript == null || script == candidateScript)
            }
            if (script != null) compatible.firstOrNull { parts(available[it]).second == script }?.let { return it }
            compatible.firstOrNull { available[it] == language }?.let { return it }
            compatible.firstOrNull()?.let { return it }
        }
        return 0
    }
}
