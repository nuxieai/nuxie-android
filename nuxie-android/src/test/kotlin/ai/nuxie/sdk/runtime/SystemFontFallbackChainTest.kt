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
}
