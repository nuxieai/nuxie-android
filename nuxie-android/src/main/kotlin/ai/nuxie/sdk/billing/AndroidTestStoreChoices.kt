package ai.nuxie.sdk.billing

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

/** Native development checkout. The caller supplies the current presentation Activity. */
internal class AndroidTestStoreChoices(
    private val activity: () -> Activity?,
) : TestStoreChoices {
    override suspend fun purchase(product: StoreProduct): TestStorePurchaseChoice = choose(
        title = "Nuxie Test Store",
        message = "TEST PURCHASE — no charge, no Google Play transaction.\n\n" +
            listOf("name", "price").mapNotNull { key ->
                (product.testStorePreview?.get(key) as? JsonPrimitive)
                    ?.takeIf(JsonPrimitive::isString)?.content
            }.joinToString("\n").ifEmpty { product.productId },
        choices = TestStorePurchaseChoice.entries.map {
            it.name.lowercase().replaceFirstChar(Char::uppercaseChar) to it
        },
        dismissed = TestStorePurchaseChoice.CANCELLED,
        unavailable = TestStorePurchaseChoice.FAILED,
    )

    override suspend fun restore(): TestStoreRestoreChoice = choose(
        title = "Nuxie Test Store — Restore",
        message = "TEST RESTORE — no Google Play account is contacted.",
        choices = listOf(
            "Restored" to TestStoreRestoreChoice.RESTORED,
            "No Purchases" to TestStoreRestoreChoice.NO_PURCHASES,
            "Failed" to TestStoreRestoreChoice.FAILED,
        ),
        dismissed = TestStoreRestoreChoice.FAILED,
        unavailable = TestStoreRestoreChoice.FAILED,
    )

    private suspend fun <Choice> choose(
        title: String,
        message: String,
        choices: List<Pair<String, Choice>>,
        dismissed: Choice,
        unavailable: Choice,
    ): Choice = withContext(Dispatchers.Main.immediate) {
        val owner = activity()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: return@withContext unavailable
        suspendCancellableCoroutine { continuation ->
            val content = LinearLayout(owner).apply {
                orientation = LinearLayout.VERTICAL
                val inset = (24 * resources.displayMetrics.density).toInt()
                setPadding(inset, inset, inset, inset)
                addView(TextView(owner).apply { text = message })
            }
            val scroll = ScrollView(owner).apply { addView(content) }
            val dialog = AlertDialog.Builder(owner).setTitle(title).setView(scroll).create()
            var settled = false
            lateinit var lifecycle: Application.ActivityLifecycleCallbacks
            fun dispose() {
                if (settled) return
                settled = true
                owner.application.unregisterActivityLifecycleCallbacks(lifecycle)
                dialog.dismiss()
            }
            fun finish(choice: Choice) {
                if (settled) return
                dispose()
                if (continuation.isActive) continuation.resume(choice)
            }
            lifecycle = object : Application.ActivityLifecycleCallbacks {
                override fun onActivityDestroyed(activity: Activity) {
                    if (activity === owner) finish(dismissed)
                }
                override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            }
            for ((label, choice) in choices) {
                content.addView(Button(owner).apply {
                    text = label
                    tag = label
                    setOnClickListener { finish(choice) }
                })
            }
            dialog.setOnDismissListener { finish(dismissed) }
            owner.application.registerActivityLifecycleCallbacks(lifecycle)
            continuation.invokeOnCancellation {
                Handler(Looper.getMainLooper()).post { dispose() }
            }
            if (!continuation.isActive) {
                dispose()
            } else {
                try {
                    dialog.show()
                } catch (_: WindowManager.BadTokenException) {
                    finish(unavailable)
                }
            }
        }
    }
}
