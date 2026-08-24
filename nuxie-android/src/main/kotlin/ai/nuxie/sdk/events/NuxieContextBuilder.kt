package ai.nuxie.sdk.events

import ai.nuxie.sdk.LogLevel
import ai.nuxie.sdk.NuxieEnvironment
import ai.nuxie.sdk.SdkVersion
import ai.nuxie.sdk.identity.IdentityProvider
import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.util.Locale
import java.util.TimeZone

/**
 * Layered property enrichment, mirroring the iOS `NuxieContextBuilder`:
 * static device context, dynamic context, SDK context, user context, then
 * custom properties with highest precedence. Property keys match iOS exactly;
 * Android-specific values fill them (`$device_manufacturer` from Build,
 * `$os_name` = "Android", `$lib` = "nuxie-android").
 */
internal class NuxieContextBuilder(
    context: Context,
    private val environment: NuxieEnvironment,
    private val logLevel: LogLevel,
    private val identity: IdentityProvider,
) {
    private val appContext = context.applicationContext ?: context

    /** Static device context: computed once, never changes during app lifetime. */
    private val staticContext: Map<String, Any?> by lazy { buildStaticDeviceContext() }

    fun buildEnrichedProperties(customProperties: Map<String, Any?>): Map<String, Any?> {
        val enriched = linkedMapOf<String, Any?>()
        enriched.putAll(staticContext)
        enriched.putAll(buildDynamicContext())
        enriched.putAll(buildSdkContext())
        enriched.putAll(buildUserContext())
        enriched.putAll(customProperties)
        return enriched
    }

    private fun buildStaticDeviceContext(): Map<String, Any?> {
        val context = linkedMapOf<String, Any?>()
        val packageManager = appContext.packageManager
        val packageName = appContext.packageName
        runCatching {
            val applicationInfo = appContext.applicationInfo
            context["\$app_name"] = packageManager.getApplicationLabel(applicationInfo).toString()
            @Suppress("DEPRECATION")
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName?.let { context["\$app_version"] = it }
            context["\$app_build"] = versionCode(packageInfo).toString()
        }
        context["\$app_bundle_id"] = packageName

        context["\$device_manufacturer"] = Build.MANUFACTURER
        context["\$device_model"] = Build.MODEL
        context["\$device_type"] = deviceType()

        context["\$os_name"] = "Android"
        context["\$os_version"] = Build.VERSION.RELEASE

        context["\$is_emulator"] = isProbablyEmulator()
        context["\$is_debug"] =
            (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        return context
    }

    private fun buildDynamicContext(): Map<String, Any?> {
        val context = linkedMapOf<String, Any?>()
        val metrics = appContext.resources.displayMetrics
        context["\$screen_width"] = metrics.widthPixels.toFloat() / metrics.density
        context["\$screen_height"] = metrics.heightPixels.toFloat() / metrics.density
        context["\$screen_scale"] = metrics.density

        val locale = Locale.getDefault()
        context["\$locale"] = locale.toString()
        context["\$language"] = locale.language
        context["\$country"] = locale.country

        val timezone = TimeZone.getDefault()
        context["\$timezone"] = timezone.id
        context["\$timezone_offset"] = timezone.getOffset(System.currentTimeMillis()) / 1000

        context["\$network_type"] = "unknown"

        runCatching {
            val activityManager =
                appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (activityManager != null) {
                val memoryInfo = ActivityManager.MemoryInfo()
                activityManager.getMemoryInfo(memoryInfo)
                context["\$memory_total"] = memoryInfo.totalMem
                context["\$memory_available"] = memoryInfo.availMem
            }
        }
        return context
    }

    private fun buildSdkContext(): Map<String, Any?> = linkedMapOf(
        "\$lib" to "nuxie-android",
        "\$lib_version" to SdkVersion.VALUE,
        "\$environment" to environment.name.lowercase(Locale.US),
        "\$log_level" to logLevel.name.lowercase(Locale.US),
    )

    private fun buildUserContext(): Map<String, Any?> {
        val context = linkedMapOf<String, Any?>(
            "\$distinct_id" to identity.distinctId(),
            "\$is_identified" to identity.isIdentified,
            "\$anonymous_id" to identity.anonymousId(),
        )
        identity.rawDistinctId()?.let { context["\$user_id"] = it }
        return context
    }

    private fun deviceType(): String {
        val smallestWidthDp = appContext.resources.configuration.smallestScreenWidthDp
        return if (smallestWidthDp >= 600) "Tablet" else "Mobile"
    }

    private fun isProbablyEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.MODEL.contains("sdk_gphone") ||
            Build.PRODUCT.contains("sdk")

    private fun versionCode(packageInfo: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode.toLong()
        }
}
