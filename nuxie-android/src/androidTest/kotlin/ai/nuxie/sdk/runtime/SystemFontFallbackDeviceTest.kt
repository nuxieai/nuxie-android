package ai.nuxie.sdk.runtime

import android.graphics.Bitmap
import android.os.Build
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class SystemFontFallbackDeviceTest {
    /** Diagnostic screenshot only; this does not claim published rendering on API 23. */
    @Test
    @SdkSuppress(maxSdkVersion = 23)
    fun api23RecordsUnavailablePublishedRenderer() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val baseline = InstrumentationRegistry.getArguments().getString("fontFallbackBaseline") == "true"
        assertTrue(NuxieRuntime.shared.isAvailable)
        assertFalse(ai.nuxie.sdk.presentation.AndroidRenderCapability.isAvailable())
        val report = if (baseline) "Fallback hook: absent on main" else {
            val selected = java.util.concurrent.ConcurrentHashMap<Int, SystemFontFallbackChain.Face>()
            SystemFontFallback.selectionObserver = { character, face -> selected[character] = face }
            try {
                for (character in listOf(0x65e5, 0x645, 0x5e9, 0x928, 0x25b7, 0x1f600)) {
                    assertTrue(SystemFontFallback.requestFromNativeThreadForTesting(character, 0, 400) != 0L)
                }
                "Fallback hook: covering faces selected\n\n" + selected.toSortedMap().entries.joinToString("\n") {
                    "U+${it.key.toString(16)}: ${File(it.value.path).name} #${it.value.index}"
                }
            } finally { SystemFontFallback.selectionObserver = null }
        }
        val activity = instrumentation.startActivitySync(android.content.Intent(instrumentation.targetContext,
            ai.nuxie.sdk.presentation.SurfaceCompatibilityHostActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        val firstDraw = java.util.concurrent.CountDownLatch(1)
        try {
            instrumentation.runOnMainSync {
                activity.setContentView(android.widget.TextView(activity).apply {
                    setPadding(32, 64, 32, 24)
                    textSize = 14f
                    setTextColor(android.graphics.Color.BLACK)
                    setBackgroundColor(android.graphics.Color.WHITE)
                    text = "API 23 qualification diagnostics\n\nPublished renderer: unavailable\nVulkan is required by the pinned runtime.\n\n" +
                        report + "\n\nPhone emoji: NotoColorEmoji.ttf (CBDT)\n\nThis is a diagnostic view, not a published Experience."
                })
                // Observe the attached window rather than an unattached TextView tree.
                activity.window.decorView.viewTreeObserver.addOnDrawListener { firstDraw.countDown() }
                activity.window.decorView.invalidate()
            }
            assertTrue("Diagnostic view must draw before capture", firstDraw.await(10, java.util.concurrent.TimeUnit.SECONDS))
            instrumentation.waitForIdleSync()
            android.os.SystemClock.sleep(300)
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                File(instrumentation.targetContext.cacheDir,
                    "fallback-api23-${if (baseline) "before" else "after"}-renderer-unavailable.png")
                    .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    @Test
    fun systemFallbackFromUnattachedNativeThreadsSelectsEveryScriptAndCachesFaces() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(NuxieRuntime.shared.isAvailable)
        val selections = java.util.concurrent.ConcurrentHashMap<Int, SystemFontFallbackChain.Face>()
        SystemFontFallback.selectionObserver = { character, face -> selections[character] = face }
        try {
            // Each call starts a fresh unattached pthread. Different characters in one
            // face also exercise consumed-probe handling and cross-lane cache reuse.
            val handles = mutableMapOf<Int, Long>()
            for (text in listOf("日本語", "مرحبا", "שלום", "नमस्ते", "▷✶●", "😀")) {
                var offset = 0
                while (offset < text.length) {
                    val character = Character.codePointAt(text, offset)
                    offset += Character.charCount(character)
                    val handle = SystemFontFallback.requestFromNativeThreadForTesting(character, 0, 400)
                    assertTrue("U+${character.toString(16)} must select a covering face", handle != 0L)
                    handles[character] = handle
                    assertTrue("Selection must name an immutable OS font", checkNotNull(selections[character]).path.startsWith("/system/") ||
                        checkNotNull(selections[character]).path.startsWith("/product/"))
                }
            }
            for ((character, handle) in handles) {
                assertEquals("Warm requests reuse borrowed font handles", handle,
                    SystemFontFallback.requestFromNativeThreadForTesting(character, 1, 400))
            }
            assertTrue(SystemFontFallback.requestFromNativeThreadForTesting(0x65e5, 7, 400) != 0L)
            assertEquals(0L, SystemFontFallback.requestFromNativeThreadForTesting(0x10ffff, 0, 400))
            val emoji = checkNotNull(selections[0x1f600])
            val format = fontTables(emoji)
            assertTrue("Phone emoji must use supported color tables", "COLR" in format || "CBDT" in format)
            val report = selections.toSortedMap().entries.joinToString("\n") {
                "U+${it.key.toString(16)}=${it.value.path}#${it.value.index}"
            } + "\nemojiTables=$format\n"
            File(instrumentation.targetContext.cacheDir, "fallback-api${Build.VERSION.SDK_INT}-selections.txt").writeText(report)
            android.util.Log.i("FontFallbackProof", report)
        } finally { SystemFontFallback.selectionObserver = null }
    }

    @Test
    fun laterFallbacksPreserveOriginalWeightAndAttachOnlyOncePerNativeThread() {
        assertTrue(NuxieRuntime.shared.isAvailable)
        val callers = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val requests = (0 until 12).map { callers.submit<LongArray> { SystemFontFallback.sequenceFromNativeThreadForTesting() } }
            for (request in requests) {
                val result = request.get(30, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(7, result.size)
                for (i in 0..2) { assertTrue(result[i * 2] != 0L); assertEquals(700L, result[i * 2 + 1]) }
                assertEquals("One attachment for three callbacks on the same pthread", 1L, result[6])
                assertEquals(SystemFontFallback.request(0x928, 0, 700), result[2])
                assertEquals(result[2], result[4])
            }
        } finally { callers.shutdownNow() }
    }

    @Test
    fun localeChangesReselectHanWithoutRecreatingPreviouslySelectedFaces() {
        assertTrue(NuxieRuntime.shared.isAvailable)
        // API 23's Japanese subset lacks 日. 一 is covered by both regional faces,
        // so this tests ordering rather than assuming identical font coverage.
        val original = java.util.Locale.getDefault()
        val selected = java.util.concurrent.atomic.AtomicReference<SystemFontFallbackChain.Face>()
        SystemFontFallback.selectionObserver = { _, face -> selected.set(face) }
        try {
            java.util.Locale.setDefault(java.util.Locale.JAPAN)
            val japaneseHandle = SystemFontFallback.requestFromNativeThreadForTesting(0x4e00, 0, 400)
            assertTrue(japaneseHandle != 0L)
            val japanese = selected.get()
            java.util.Locale.setDefault(java.util.Locale.CHINA)
            assertTrue(SystemFontFallback.requestFromNativeThreadForTesting(0x4e00, 0, 400) != 0L)
            val chinese = selected.get()
            assertNotEquals("Each emulator supplies distinct Japanese and simplified Chinese faces", japanese, chinese)
            java.util.Locale.setDefault(java.util.Locale.JAPAN)
            assertEquals(japaneseHandle, SystemFontFallback.requestFromNativeThreadForTesting(0x4e00, 0, 400))
            assertEquals(japanese, selected.get())
            android.util.Log.i("FontFallbackProof", "locale ja=$japanese zh=$chinese")
        } finally {
            java.util.Locale.setDefault(original)
            SystemFontFallback.selectionObserver = null
        }
    }

    private fun fontTables(face: SystemFontFallbackChain.Face): Set<String> = java.io.RandomAccessFile(face.path, "r").use { file ->
        val tag = file.readInt()
        val start = if (tag == 0x74746366) {
            file.seek(12L + face.index * 4L)
            file.readInt().toLong() and 0xffffffffL
        } else 0L
        file.seek(start + 4)
        val count = file.readUnsignedShort()
        (0 until count).map { index ->
            file.seek(start + 12 + index * 16L)
            val bytes = ByteArray(4).also(file::readFully)
            String(bytes, Charsets.US_ASCII)
        }.toSet()
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun ordinaryBoundTextDrawsEveryRequestedStringIncludingSymbols() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val baseline = InstrumentationRegistry.getArguments().getString("fontFallbackBaseline") == "true"
        fun asset(name: String) = instrumentation.context.assets.open("authored-font-fallback/$name").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        val bytes = asset("inline_text_data_binding.riv")
        val catalog = checkNotNull(runtime.inspectFileAssets(bytes))
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(320, 160))
        val selections = java.util.concurrent.ConcurrentHashMap<Int, SystemFontFallbackChain.Face>()
        SystemFontFallback.selectionObserver = { character, face -> selections[character] = face }
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, catalog,
                mapOf(catalog.single { it.kind == FileAssetKind.FONT }.ordinal to asset("ordinary-latin.ttf"))))
            try {
                val artboard = checkNotNull(file.newArtboard("Inline text"))
                try {
                    artboard.bindDefaultViewModel("Runtime")
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        fun render(text: String): ByteArray {
                            assertTrue(artboard.setDefaultViewModelValue("paywall/selectedProductId", NuxieViewModelScalarValue.StringValue(text)))
                            player.step(0.0)
                            return renderer.renderToCpuFrame(player, 0xff000000.toInt(), 1f).rgba
                        }
                        val empty = render("")
                        for ((name, text) in listOf("japanese" to "日本語", "arabic" to "مرحبا", "hebrew" to "שלום",
                            "hindi" to "नमस्ते", "symbols" to "▷ ✶ ●", "emoji" to "😀")) {
                            render("")
                            SystemFontFallback.suppressForTesting(true)
                            val missing = try { render(text) } finally { SystemFontFallback.suppressForTesting(false) }
                            render("")
                            selections.clear()
                            val drawn = render(text)
                            val changed = (drawn.indices step 4).count { at -> (0..2).any { drawn[at + it] != empty[at + it] } }
                            android.util.Log.i("FontFallbackProof", "ordinary bound $name changed=$changed baseline=$baseline")
                            if (!baseline) {
                                assertTrue("Ordinary $name adds native ink", changed > 10)
                                assertFalse("Ordinary $name must differ from primary-font missing glyphs", drawn.contentEquals(missing))
                                assertTrue("Actual C hook must choose a face for ordinary $name",
                                    selections.containsKey(text.codePointAt(0)))
                            }
                            val bitmap = Bitmap.createBitmap(320, 160, Bitmap.Config.ARGB_8888)
                            try {
                                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(drawn))
                                File(instrumentation.targetContext.cacheDir,
                                    "fallback-api${Build.VERSION.SDK_INT}-${if (baseline) "before" else "after"}-ordinary-$name.png")
                                    .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                            } finally { bitmap.recycle() }
                        }
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally {
            SystemFontFallback.suppressForTesting(false)
            SystemFontFallback.selectionObserver = null
            renderer.close()
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 26)
    fun ordinaryPublishedTextDrawsFallbackScriptsAndColorEmoji() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val baseline = InstrumentationRegistry.getArguments().getString("fontFallbackBaseline") == "true"
        fun asset(name: String) = instrumentation.context.assets.open("authored-font-fallback/$name").use { it.readBytes() }
        val runtime = NuxieRuntime.shared
        assertTrue(runtime.isAvailable)
        val bytes = asset("ordinary-roboto.nux")
        val catalog = checkNotNull(runtime.inspectFileAssets(bytes))
        val renderer = checkNotNull(runtime.newAndroidVulkanRenderer(390, 844))
        val missing: ByteArray
        SystemFontFallback.suppressForTesting(true)
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, catalog,
                mapOf(catalog.single { it.kind == FileAssetKind.FONT }.ordinal to asset("ordinary-latin.ttf"))))
            try {
                val artboard = checkNotNull(file.newArtboard("One"))
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        player.step(0.0)
                        missing = renderer.renderToCpuFrame(player, 0xff000000.toInt(), 1f).rgba
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { SystemFontFallback.suppressForTesting(false) }
        try {
            val file = checkNotNull(runtime.importFile(renderer, bytes, catalog,
                mapOf(catalog.single { it.kind == FileAssetKind.FONT }.ordinal to asset("ordinary-latin.ttf"))))
            try {
                val artboard = checkNotNull(file.newArtboard("One"))
                try {
                    val player = checkNotNull(artboard.newPlayer())
                    try {
                        player.step(0.0)
                        val frame = renderer.renderToCpuFrame(player, 0xff000000.toInt(), 1f)
                        val bitmap = Bitmap.createBitmap(390, 844, Bitmap.Config.ARGB_8888)
                        try {
                            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(frame.rgba))
                            File(instrumentation.targetContext.cacheDir,
                                "fallback-api${Build.VERSION.SDK_INT}-${if (baseline) "before" else "after"}-ordinary.png")
                                .outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                        } finally { bitmap.recycle() }
                        fun ink(top: Int): Int = (top until top + 104).sumOf { y ->
                            (0 until 390).count { x ->
                                val at = (y * 390 + x) * 4
                                (0..2).any { (frame.rgba[at + it].toInt() and 255) > 100 }
                            }
                        }
                        for ((name, top) in listOf("japanese" to 32, "arabic" to 140, "hebrew" to 248,
                            "hindi" to 356, "emoji" to 464, "latin" to 680)) {
                            android.util.Log.i("FontFallbackProof", "$name ink=${ink(top)} baseline=$baseline")
                            if (!baseline) {
                                assertTrue("$name must draw native ink", ink(top) > 100)
                                if (name != "latin") assertTrue("$name must differ from primary-font missing glyphs",
                                    (top until top + 104).any { y -> (0 until 390 * 4).any { x ->
                                        val at = y * 390 * 4 + x
                                        frame.rgba[at] != missing[at]
                                    } })
                            }
                        }
                        if (!baseline) {
                            val color = (464 until 568).sumOf { y -> (0 until 390).count { x ->
                                val at = (y * 390 + x) * 4
                                val channels = (0..2).map { frame.rgba[at + it].toInt() and 255 }
                                channels.max() > 100 && channels.max() - channels.min() > 40
                            } }
                            assertTrue("Ordinary emoji must contain color pixels", color > 100)
                        }
                    } finally { player.close() }
                } finally { artboard.close() }
            } finally { file.close() }
        } finally { renderer.close() }
    }
}
