# SDK decisions

## 2026-10-04: in-app links use Android Custom Tabs

Levi approved this runtime dependency in L3 of the phone events and links brief: "Yes, add it so both phones match."

Use `androidx.browser:browser` for Custom Tabs. Web targets `_self`, `_parent`, `_top`, and an omitted target stay in-app. `_blank` opens externally. Non-web schemes go to the system. Journey `in_app` and `external` use the same target function. This records the explicit spec decision required by the SDK dependency rule.

Browser 1.8.0 provides the required Java Custom Tabs API and works with the existing Kotlin 2.0 toolchain. The dependency is implementation-scoped.

References: [AndroidX Browser releases](https://developer.android.com/jetpack/androidx/releases/browser), [CustomTabsIntent](https://developer.android.com/reference/androidx/browser/customtabs/CustomTabsIntent).
