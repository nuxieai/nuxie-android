# SDK decisions

## 2026-10-04: in-app links use Android Custom Tabs

Levi approved this runtime dependency in L3 of the phone events and links brief: "Yes, add it so both phones match."

Use `androidx.browser:browser` for Custom Tabs. Web targets `_self`, `_parent`, `_top`, and an omitted target stay in-app. `_blank` opens externally. Non-web schemes go to the system. Journey `in_app` and `external` use the same target function. This records the explicit spec decision required by the SDK dependency rule.

Browser 1.8.0 provides the required Java Custom Tabs API and works with the existing Kotlin 2.0 toolchain. The dependency is implementation-scoped.

References: [AndroidX Browser releases](https://developer.android.com/jetpack/androidx/releases/browser), [CustomTabsIntent](https://developer.android.com/reference/androidx/browser/customtabs/CustomTabsIntent).

## 2026-10-06: modal layers use Material bottom sheets

Levi approved Material's bottom sheet in Q2 of brief 10, confirmed by `10-start.md`. The native sheet owns its slide, dimming, back handling and drag to close. Covered layers keep their mounted screen active and playing, and disable input until revealed. Authored dismiss closes the top layer after its exit completes; scope all closes the Experience. Android back uses the native window's close policy, including full-screen layers, and respects closedby none.

Use the implementation-scoped `com.google.android.material:material:1.12.0` dependency. This is the explicit dependency decision required by `AGENTS.md`. Appearance uses Material's theme defaults; no custom corner, shadow or scrim values are introduced here.

References: [Material bottom sheets](https://github.com/material-components/material-components-android/blob/master/docs/components/BottomSheet.md), [BottomSheetDialog](https://github.com/material-components/material-components-android/blob/master/lib/java/com/google/android/material/bottomsheet/BottomSheetDialog.java).
