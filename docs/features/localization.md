# Localization

The app's interface can be shown in a language other than English. English is the source
language; translations are added as additional resource sets alongside it.

Key files: `core/common/util/LocaleContextWrapper.kt`, `core/common/constants/AppConstants.kt`
(`LocaleConstants`), `core/model/AppLanguage.kt`, `core/designsystem/component/LjLanguageRow.kt`,
`lint/checks/HardcodedComposeStringDetector.kt`

## Supported Locales

| Locale | Qualifier |
|---|---|
| English (default/source) | `values/` |
| Korean | `values-ko/` |
| Simplified Chinese | `values-zh-rCN/` |
| Traditional Chinese | `values-zh-rTW/` |

## Resource Layout

There is no single central `strings.xml`. Every module that has its own user-facing strings
(Compose UI or `Context.getString()` call sites) owns its own `res/values/strings.xml` (English,
source of truth) plus a sibling `res/values-<qualifier>/strings.xml` per supported locale, with
identical keys: `app`, `core/common`, `core/designsystem`, `core/location`, `core/map`, `core/routing`,
`feature/favorites/impl`, `feature/group/impl`, `feature/map/impl`, `feature/onboarding/impl`,
`feature/routes/impl`, `feature/settings/impl`, `feature/widget/impl`. A module may split its
strings across several files in the same `values*/` folders (e.g. `coordinate_workflow_strings.xml`
in `app`, `core/common` and `core/designsystem`), each with a sibling in every supported locale.
Cooldown advisory/badge text lives in `core/designsystem`'s `cooldown_strings.xml`, formatted by
`cooldownAdvisoryLabel()`/`cooldownBadgeText()` (`CooldownText.kt`); `CooldownState` itself holds no text.

## Selecting a Display Language

- **Android 13+ (API 33+)**: the OS's own per-app-language Settings screen lists every locale
  this app ships, sourced from `app/build.gradle.kts`'s `androidResources { generateLocaleConfig
  = true }` — AGP generates the locale-config XML and the manifest's `android:localeConfig`
  attribute automatically from whichever `values-*` folders exist at build time. No hand-maintained
  locale-config file.
- **Android 9-12 (API 28-32)**: the OS has no per-app-language UI at this API level, so
  `LocaleContextWrapper.wrap()` (`:core:common`) supports an app-chosen override on this range via
  `attachBaseContext()` in `MainActivity`, `OverlayService` (the joystick and widget overlay base
  class), and `MockLocationService` (the foreground service and its notification).
- **Settings language dialog**: Settings → **App language** opens a radio-choice dialog.
  It includes **한국어**, **English**, **简体中文**, **繁體中文**, and **System default**. Language
  names use their native spelling so a user can recover from an unfamiliar display language.
  The selection applies immediately and persists per device, not in `AppSettings`/`ExportData`
  (like `ThemeMode`). Choosing Korean uses the `ko` language tag and each module's `values-ko`
  resources; English continues to use the source resources.
- **Compact language picker**: the existing `LjLanguageDropdown` (`:core:designsystem`) remains
  in onboarding's optional header and the Settings → Menus top bar. Its menu uses the same native
  language names. The selected button remains compact: **EN**, **KO**, **CN**, **TW**, or the
  localized system-default label. The onboarding picker does not count toward "Step X of 3".
- **Applying a selection**: `LocaleContextWrapper.setLanguage()` uses the platform's own
  `LocaleManager.setApplicationLocales` on API 33+, which applies the configuration change and
  recreates the activity automatically. On API 28-32, it writes `LocaleConstants.KEY_LANGUAGE_TAG`
  to the SharedPreferences file read by `LocaleContextWrapper.wrap()`; the caller explicitly
  recreates the activity on this legacy path only. Neither picker requires saving other settings.
- **Android 9-12 (API 28-32) limitation**: the SharedPreferences override above only takes effect
  for a component the next time it's created (`attachBaseContext()` runs once per instance). A
  language change made while a spoofing session is actively running won't retroactively relabel
  that session's already-running foreground-service notification or overlays — only the next
  session (or `MainActivity`, which is force-recreated immediately) picks it up. Android 13+ has
  no such gap: the framework applies the per-app override process-wide the moment it's set.
- **Play Store installs**: `app/build.gradle.kts`'s `bundle { language { enableSplit = false } }`
  disables Play's per-device language-APK splitting, so every install carries every supported
  locale's resources regardless of the installing device's system language at install time.

## Lint Enforcement

`HardcodedComposeStringDetector` (`:lint:checks`) flags hardcoded user-facing strings in Compose
(direct calls, conditional/branch expressions, `semantics {}` blocks, and other generic literal
cases) so new UI text is externalized to `strings.xml` and stays translatable by construction.

## Translation Conventions

- English (`values/strings.xml` in each module) is the source of truth; a translation is a sibling
  `values-<qualifier>/strings.xml` with the exact same keys.
- Never translate: the app name/brand ("locationjoystick"), and units/symbols embedded in format
  strings (e.g. "m/s", "km/h").
- AI-assisted translations require a native-speaker review before merge — see CONTRIBUTING.md,
  "Translations".

## Adding a New Language

1. For every module's `res/values/strings.xml`, add a sibling `res/values-<qualifier>/strings.xml`
   translating every key (same key set, no additions/removals).
2. Leave the app name/brand and embedded units/symbols untranslated (see Translation Conventions).
3. Run `make format`, `make lint`, `make test`.
4. Get a native speaker of the target language to review the translated strings before merging.
