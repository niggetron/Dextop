# Compile Audit — Wave 21 Report

## Scope inspected
All compilable files changed in `git diff HEAD~20..HEAD` (no builds run, per BRIEF). 16 Kotlin files (13 main + 4 test), 2 C `.inc` files, `build.gradle.kts`, `proguard-rules.pro`, `pubspec.yaml`, 3 GitHub workflow YAMLs. Docs/skill MD and `.team_waves/*.md` ignored (non-compilable).

## Findings
**Zero compile-blocking errors found.** No type mismatch, unresolved reference, visibility violation, missing import, or deprecated-API-as-error in any changed source file. GitHub Actions Kotlin/C compilation should pass.

Verified high-risk spots (file:line → why it is safe):
- `CapabilitySnapshot.kt:98-99` — `getSystemService(DisplayManager::class.java)` is a platform type `DisplayManager!` (same direct-call pattern already compiles at `CardexRelayService.kt:525`, `DisplayTopologyController.kt:359`, `MirrorService.kt:1724`); `displayManager?.displays.orEmpty()` fine.
- `CapabilitySnapshot.kt:145,171` — `device.getSources() and SOURCE_TOUCHSCREEN != 0` parses as `(a and b) != 0` (infix `and` binds tighter than `!=`); `PackageManager.FEATURE_VULKAN_HARDWARE_VERSION` exists.
- `CapabilitySnapshot.kt:180-181`, `SessionJournal.kt:38-39,179`, `MirrorService.kt:1112` — `PrivilegedAccess(String)`, `DeviceDiagnostics.report(): Map<String,Any>`, `PhoneRotationState`, `DisplayMirrorBackend.DISPLAY_SPECIFICATION`, `PublisherKeys.issue(Context,String)`, `XIAOMI_TABLET_PROFILE` all exist with matching signatures/visibility (same-module `internal`).
- `DesktopModeConfigurator.kt:39-40` — spread `Array<out String>` into `vararg String` is legal; `:89-100` `invokeOnCompletion` lambda Boolean result is discarded (Unit coercion fine).
- `DesktopModeConfigurator.kt:69` — `localText("uiSettingRejected")` key exists in all 7 `lib/l10n/inline/*.json` (no localization-lint failure).
- `LaptopSwipeDecoder.kt:43-45` — `removeEldestEntry` override signature matches `java.util.LinkedHashMap`; `:52` `Language.entries` and FSM `data object` need Kotlin 1.9+, project uses 2.2.20 (`android/settings.gradle.kts:23`).
- `MirrorService.kt:1112` — `private val sessionJournal get() = journal(this)` correctly resolves the new `internal fun journal(Context)` in the companion object.
- `MainActivity.kt:777` — added `.toMap()` produces `Map<String,Any?>`, exactly `DextopLog.log`'s `fields` parameter (`DextopLog.kt:62-64`); without it this line would not compile.
- `WatchdogProtocol.kt:20-21` — regex escapes (`\\\$`, `\\-`) are valid Kotlin string escapes; `:38,82` `!!` on platform JSONArray fine.
- `WatchdogDaemon.kt:32-33` — `@JvmStatic main` on a public `object` generates the static `main(String[])` that `app_process` needs; `proguard-rules.pro` keeps the class.
- Tests: `org.junit.Ignore` is available (JUnit4 already used via `@Test(timeout=...)`); `assertFailsWith<IllegalStateException>` matches `check()`; internal FSM types are visible to same-module unit tests.
- C: `WL_EVENT_HANGUP/ERROR` are defined in vendored `third_party/wayland/src/wayland-server-core.h:40-43`; all required libc headers come from `compositor.c:19-47`; `deck_ahb_broker.inc:85` `CMSG_SPACE(8*sizeof(int))` is a constant expression so the array initializer is legal; every non-void C function has an unconditional return (`-Werror=return-type` safe at `build.gradle.kts:87`).

## Deliverable
Static audit only; no source edits, no builds. Verdict: CI compile stage is clean for this diff.

## Risks / device testing needed
P3 (non-compile, verify at runtime/CI, no code change required):
- `android/app/build.gradle.kts:217-221` — downgrade to `hiddenapibypass:6.1` is compile-safe; confirm the empty-prefix exemption still bypasses API-36 hidden APIs on HyperOS 3 at runtime (comment claims it does).
- `.github/workflows/release.yml:1-11`, `beta-release.yml:17`, `release-apk.yml:57` — concurrency-group reshuffle is YAML-valid; confirm no two Gradle builds overlap on the shared `android-gradle` group.
- `pubspec.yaml:4` — version `2.0.0-beta.4+45` is well-formed semver; monotonic build number only matters for store upload (not compile).
