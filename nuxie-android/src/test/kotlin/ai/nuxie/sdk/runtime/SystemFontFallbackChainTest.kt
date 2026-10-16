package ai.nuxie.sdk.runtime

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SystemFontFallbackChainTest {
    @Test fun resolvesOemDefaultAliasesWithoutOverwritingFamiliesOrLoopingOnCycles() {
        val xml = """<familyset>
          <family name="oem"><font weight="400">oem.ttf</font><font weight="700">bold.ttf</font></family>
          <alias name="sans-serif" to="ui-default" />
          <alias name="ui-default" to="oem" weight="400" />
          <alias name="cycle-a" to="cycle-b" /><alias name="cycle-b" to="cycle-a" />
          <alias name="oem" to="ui-default" />
          <family><font>emoji.ttf</font></family>
        </familyset>"""
        val families = SystemFontFallbackChain.parse(xml.byteInputStream())
        assertEquals(4, families.size)
        assertEquals(2, families.single { it.name == "oem" }.fonts.size)
        assertEquals(1, families.single { it.name == "sans-serif" }.fonts.size)
        assertEquals(listOf("oem.ttf", "emoji.ttf"),
            SystemFontFallbackChain.order(families, Locale.US) { it }.map { it.path })
    }

    @Test fun preservesUnnamedFamiliesIndicesAndLocaleOrderWhileSkippingStyleVariants() {
        val xml = """<familyset>
          <family name="sans-serif"><font weight="700">bold.ttf</font><font weight="400">regular.ttf</font></family>
          <family lang="zh-Hans"><font index="2">cjk.ttc</font></family>
          <family lang="ja"><font index="0">cjk.ttc</font></family>
          <family lang="zh-Hant,zh-Bopo"><font index="1">cjk.ttc</font></family>
          <family lang="ja" variant="elegant"><font>elegant.ttf</font></family>
          <family><font style="italic">italic.ttf</font><font fallbackFor="serif">serif.ttf</font>
            <font weight="400">emoji.ttf<axis tag="wght" stylevalue="400" /></font></family>
          <family><font>emoji.ttf</font></family>
          <family><font>missing.ttf</font></family>
        </familyset>"""
        val families = SystemFontFallbackChain.parse(xml.byteInputStream())
        val ordered = SystemFontFallbackChain.order(families, Locale.JAPAN) {
            if (it == "missing.ttf") null else "/system/fonts/$it"
        }
        assertEquals(listOf("regular.ttf" to 0, "cjk.ttc" to 0, "cjk.ttc" to 2, "cjk.ttc" to 1, "emoji.ttf" to 0),
            ordered.map { it.path.substringAfterLast('/') to it.index })
        assertEquals(8, families.size)
        assertTrue(SystemFontFallbackChain.matches("zh-Hant,zh-Bopo", Locale.TAIWAN))
        assertFalse(SystemFontFallbackChain.matches("zh-Hans", Locale.TAIWAN))
        assertTrue(SystemFontFallbackChain.matches("zh-Hans", Locale.CHINA))
        assertTrue(SystemFontFallbackChain.matches("ar", Locale.forLanguageTag("ar-EG")))
    }
    @Test fun ignoresLegacyEmojiFamiliesUsingBothAndroidBooleanSpellings() {
        // API 36 fonts.xml puts the ignored CBDT face before the active emoji face.
        val xml = """<familyset>
            <family lang="und-Zsye" ignore="true"><font weight="400" style="normal">NotoColorEmojiLegacy.ttf</font></family>
            <family lang="und-Zsye" ignore="1"><font>NotoColorEmojiLegacy2.ttf</font></family>
            <family lang="und-Zsye"><font weight="400" style="normal">NotoColorEmoji.ttf</font></family>
        </familyset>"""
        val families = SystemFontFallbackChain.parse(xml.byteInputStream())
        assertEquals(listOf("NotoColorEmoji.ttf"), SystemFontFallbackChain.order(families, Locale.US) { it }.map { it.path })
    }

    @Test fun selectsTheNearestRegularFaceAmongExistingFiles() {
        val families = SystemFontFallbackChain.parse("""<familyset><family>
            <font weight="400">missing.ttf</font><font weight="500" index="2">existing.ttc</font>
            <font weight="700">bold.ttf</font><font style="italic">italic.ttf</font>
        </family></familyset>""".byteInputStream())
        assertEquals(listOf(SystemFontFallbackChain.Face("existing.ttc", 2)),
            SystemFontFallbackChain.order(families, Locale.US) { if (it == "missing.ttf") null else it })
    }

    @Test fun pathsAreCanonicalAndCannotTraverseOrFollowSymlinksOutsideTrustedRoots() {
        val directory = java.nio.file.Files.createTempDirectory("nuxie-font-paths").toFile()
        try {
            val root = java.io.File(directory, "fonts").apply { mkdir() }
            val font = java.io.File(root, "face.ttf").apply { writeText("font") }
            val outside = java.io.File(directory, "outside.ttf").apply { writeText("outside") }
            java.nio.file.Files.createSymbolicLink(java.io.File(root, "escape.ttf").toPath(), outside.toPath())
            java.nio.file.Files.createSymbolicLink(java.io.File(root, "alias.ttf").toPath(), font.toPath())
            fun resolve(name: String) = SystemFontFallbackChain.resolvePath(name, listOf(root))
            assertEquals(font.canonicalPath, resolve("./face.ttf"))
            assertEquals(font.canonicalPath, resolve("alias.ttf"))
            assertEquals(font.canonicalPath, resolve(font.absolutePath))
            assertNull(resolve("../outside.ttf"))
            assertNull(resolve(java.io.File(root, "../outside.ttf").path))
            assertNull(resolve("escape.ttf"))
            assertNull(resolve(outside.absolutePath))
        } finally { directory.deleteRecursively() }
    }

    @Test fun callbackExceptionsAreLoggedOncePerUnderlyingKind() {
        class FontIoFailure : RuntimeException("private path")
        class FontParserFailure : RuntimeException("private document")
        org.robolectric.shadows.ShadowLog.clear()
        ai.nuxie.sdk.logging.NuxieLog.configure(ai.nuxie.sdk.LogLevel.WARN)
        repeat(3) {
            ai.nuxie.sdk.logging.NuxieLog.nativeFontException(java.util.concurrent.ExecutionException(FontIoFailure()))
            ai.nuxie.sdk.logging.NuxieLog.nativeFontException(FontParserFailure())
        }
        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("Nuxie").filter { it.msg.contains("System font fallback failed") }
        assertEquals(2, logs.size)
        assertTrue(logs.all { "redacted" in it.msg && "private path" !in it.msg && "private document" !in it.msg })
    }

}
