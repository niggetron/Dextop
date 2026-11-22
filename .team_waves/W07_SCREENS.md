# Code Agent W7A (M3) — Wave 07 Report: 54-screen Flutter→Compose migration map

## Scope inspected
- `lib/` 65 Dart files (10 in `l10n/`, 55 hand-written). 54 screen units grouped from those files; `main.dart` is entry only. Primary route graph: `lib/main.dart` (13 `part`s), `lib/app_shell.dart:3-130`, `lib/home_screen.dart:3`, `lib/settings_screen.dart`, `lib/linux_page.dart:30`, `lib/setup_page.dart:15`.
- Existing Compose M3E: only `android/app/src/main/kotlin/moe/n4tsu/dextop/plasma/{BookUi,BookSettings,BookPanels,BookWidgets,BookKit,Book}.kt` (80 `@Composable`s). No `@Composable` anywhere else; `moe/n4tsu/splitui/SplitPlacementDialog.kt` is an `AlertDialog`, `SamsungDesktopSettings.kt` is data-only. `material3:1.5.0-alpha18` at `android/app/build.gradle.kts:212`.
- Parity contract: `.agents/skills/dextop-flutter-to-compose-parity/SKILL.md` (54 screens / 166 RPCs / 7 locales); W06 repo slices (Session 16, Privilege 11, DisplayAndShell 33, WorkspaceAndApps 5, Diagnostics 11, Linux 90); W07_WEB cutover order; W07B theme gaps.
- No builds, no source edits, no token.

## Findings (prioritized migration order)

**P0 — do before porting any screen.** Native shell + prefs, else every port runs on a broken substrate.
1. `ui/DextopApp.kt` host + launcher `<activity-alias>` (P0-2/P1-3 in W07_WEB); `FlutterPrefsMigrator` first (`FlutterSharedPreferences.xml`, `flutter.` prefix, 3 encoded types) — W07_WEB P0-1. Until signed off, keep `MainActivity:FlutterActivity` as fallback.
2. `ui/kit/` M3E primitives (#54) — current Dart kit (`expressive/dextop_widgets.dart`, `linux/linux_widgets.dart`) has no native counterpart except `plasma/BookKit.kt`. Build once; every screen depends on it.
3. Lock the 166-RPC golden list from W06 into `ui/parity/RpcGolden.kt`; no screen merged with fewer RPCs than its Dart source.

**P1 — leaf screens, no live streams, fast parity proof (wave 1).** #11-23, #28, #30-31, #44-46, #51, #5-6, #8. Mostly `Map`-in/`Map`-out reads; BookSettings already covers About/Experimental/Style pages (`BookSettings.kt:65-160,388-457`) — reuse via `ui/book` adapters rather than re-porting.

**P2 — wizards, panels, streaming state (wave 2).** #3-4, #9, #20, #25-26, #29, #32-43, #48-50, #52-53. Need `StateFlow` + lifecycle + DataStore; 12.1" two-pane and predictive back.

**P3 — hardest/last (wave 3).** #2, #24, #27, #10, #32, #47, #38, #7 (deduped in §Hardest). Must pass on-device before Flutter removal.

Legend for per-screen parity codes: `RPC(x)` = typed repo methods wired (counts from W06); `L` = 7-locale ARB→XML key+placeholder parity; `T` = widget test of the Dart flow; `S` = prefs key migration; `A` = adaptive list-detail + predictive back + insets; `M` = M3E dynamic color/48dp/semantics. `∃` = reuse existing Compose surface; `+` = new.

## Deliverable

### Inventory: 54 screens → Compose target, priority, parity checklist

| # | Dart screen (file) | Compose M3E target | P | Parity |
|---|---|---|---|---|
| 1 | `DextopApp` `app_shell.dart:3` | `+ui/DextopApp.kt` ComponentActivity host (∃ `BookShell` host `BookUi.kt:421`) | P0 | RPC(sess+priv) L S A M T |
| 2 | `HomeScreen` `home_screen.dart:3` | `+ui/home/HomeScreen.kt` (∃ Dock/Launcher/Desktop/StatusBar `BookUi.kt:595-1370`) | P3 | RPC(sess17/priv) L S A M T |
| 3 | home overview/hero panels `home_content.dart` | `+ui/home/HomeOverview.kt` (∃ `BookKit` groups) | P2 | RPC(sess) L A M T |
| 4 | resolution sheets `resolution_ui.dart:44,228` | `+ui/display/ResolutionSheets.kt` | P2 | RPC(display) L A M T |
| 5 | `_MultiTouchUpgradeFlow` `home_screen.dart:27` | `+ui/onboarding/UpgradeFlow.kt` | P1 | L S M T |
| 6 | `_ThreeFingerGestureDemo` `home_screen.dart:195` | `+ui/onboarding/ThreeFingerDemo.kt` | P1 | L M T |
| 7 | `DextopSetupPage` `setup_page.dart:15` | `+ui/setup/SetupWizard.kt` | P3 | RPC(display+priv+linux) L S A M T |
| 8 | `GestureDemoFlow` `setup_page.dart:1189` | `+ui/onboarding/GestureDemoFlow.kt` | P1 | L M T |
| 9 | `EmbeddedBinderSetupPage` `embedded_binder_setup.dart:11` | `+ui/setup/EmbeddedBinderSetupScreen.kt` (∃ native `privilege/EmbeddedPairing*`) | P2 | RPC(priv11) L M T |
| 10 | `LinuxSetupPage` `linux_setup_page.dart:59` | `+ui/linux/setup/LinuxSetupScreen.kt` | P3 | RPC(linux90) L S A M T |
| 11 | Settings root `settings_screen.dart` | `+ui/settings/SettingsHome.kt` (list-detail; ∃ `BookSettings.kt:88` nav cards) | P1 | RPC(display) L S A M T |
| 12 | `MouseSettingsPage` `settings_screen.dart:967` | `+ui/settings/MouseSettingsScreen.kt` | P1 | RPC(display) L S M T |
| 13 | `AutoSettingsPage` `settings_screen.dart:1208` | `+ui/settings/AutoSettingsScreen.kt` | P1 | RPC(display) L S A M T |
| 14 | `AppInfoPage` `app_info.dart:3` | ∃ `BookSettings.kt:410-457` about page → `+ui/info/AppInfoScreen.kt` wrapper | P1 | RPC(diag) L S M T |
| 15 | `_ExperimentalFeaturesPage` `app_info.dart:269` | ∃ `BookSettings.kt:388-410` experimental → `+ui/info/ExperimentalFeatures.kt` | P1 | RPC(display) L S M T |
| 16 | `_DiagnosticLogPage` `app_info.dart:510` | `+ui/info/DiagnosticLogScreen.kt` (∃ `plasma/LaunchLogTail.kt` logic) | P1 | RPC(diag11) L T |
| 17 | `DeviceReportPage` `device_report.dart:5` | `+ui/info/DeviceReportScreen.kt` | P1 | RPC(diag11) L T |
| 18 | `KeyboardSettingsPage` `keyboard_settings.dart:3` | `+ui/settings/KeyboardSettingsScreen.kt` | P1 | RPC(display+linux) L S M T |
| 19 | `KeyboardThemesPage` `keyboard_themes.dart:3` | `+ui/settings/KeyboardThemesScreen.kt` (∃ `LaptopKeyboardThemeRepository.kt`) | P1 | RPC(display) L S M T |
| 20 | `KeyboardThemeEditorPage` `keyboard_themes.dart:659` | `+ui/settings/KeyboardThemeEditor.kt` (live demo overlay) | P2 | RPC(display) L M T |
| 21 | `SamsungDesktopSettingsPage` `samsung_desktop_settings.dart:87` | `+ui/settings/SamsungSettingsScreen.kt` (∃ `SamsungDesktopSettings.kt` data) | P1 | RPC(display) L M T |
| 22 | `BookSettingsPage` `plasma_settings.dart:4` | ∃ `plasma/BookSettings.kt` 8 pages — verify + `ui/book` adapter only | P1 | RPC(display) L S M T |
| 23 | `DisplaySettingsPage`+env card `display_topology.dart:122,3` | `+ui/display/DisplaySettingsScreen.kt` | P1 | RPC(display33) L S M T |
| 24 | `DisplayTopologyPage` `display_topology.dart:382` | `+ui/display/TopologyScreen.kt` (∃ `DisplayTopologyController.kt`) | P3 | RPC(display) L A M T |
| 25 | `DisplayResolutionListPage` `display_topology.dart:398` | `+ui/display/ResolutionListScreen.kt` (∃ `ResolutionRepository.kt`) | P2 | RPC(display) L A M T |
| 26 | `DisplayResolutionDetailPage` `display_topology.dart:513` | `+ui/display/ResolutionDetailScreen.kt` | P2 | RPC(display) L M T |
| 27 | `DisplayTopologyEditor`+canvas `display_topology.dart:690,1428` | `+ui/display/TopologyEditor.kt` | P3 | RPC(display) L A M T |
| 28 | `ExternalDisplayModeCard` `display_topology.dart:145` | `+ui/display/ExternalDisplayModeCard.kt` | P1 | RPC(display) L M T |
| 29 | `DextopFeaturesPage` `features_page.dart:15` | `+ui/features/FeaturesScreen.kt` | P2 | RPC(display33+diag) L S A M T |
| 30 | `OverlayApp` `overlay_entry.dart:3` | `+ui/overlay/OverlayActivity.kt` (channel `app.freedextop/overlay`) | P2 | RPC(overlay) L M T |
| 31 | `OverlayMenu` `overlay_entry.dart:31` | `+ui/overlay/OverlayMenuScreen.kt` | P2 | RPC(overlay) L M T |
| 32 | `LinuxPage` `linux_page.dart:30` | `+ui/linux/LinuxHomeScreen.kt` | P3 | RPC(linux90) L S A M T |
| 33 | `LinuxSettingsPage` `linux_settings_page.dart:29` | `+ui/linux/settings/LinuxSettingsScreen.kt` | P2 | RPC(linux) L S A M T |
| 34 | `LinuxAdvancedSettingsPage` `linux/linux_advanced_settings.dart:11` | `+ui/linux/settings/AdvancedSettings.kt` | P2 | RPC(linux) L S M T |
| 35 | `LinuxAppModePage` `linux/linux_app_mode.dart:13` | `+ui/linux/apps/AppModeScreen.kt` | P2 | RPC(linux) L M T |
| 36 | `LinuxAppsGrid` `linux/linux_apps.dart:479` | `+ui/linux/apps/AppsGrid.kt` | P2 | RPC(linux) L A M T |
| 37 | `LinuxDesktopSessionSettings` `linux/linux_desktop_session.dart:14` | `+ui/linux/session/DesktopSessionSettings.kt` (∃ `DesktopSessionFsm.kt`) | P2 | RPC(linux) L M T |
| 38 | `LinuxResourcesPanel` `linux/linux_resources.dart:15` | `+ui/linux/monitor/ResourcesPanel.kt` | P3 | RPC(linux) L M T |
| 39 | `LinuxStorageUi`/`_LinuxStorageDetails` `linux/linux_storage_ui.dart:10,235` | `+ui/linux/storage/StorageScreen.kt` | P2 | RPC(linux) L A M T |
| 40 | `LinuxLogTail` `linux/linux_error_log.dart:138` | `+ui/linux/monitor/LogTailPanel.kt` | P2 | RPC(linux) L T |
| 41 | `LinuxCompatPanel` `linux/linux_compat.dart:11` | `+ui/linux/settings/CompatPanel.kt` | P2 | RPC(linux+usb) L M T |
| 42 | `LinuxIntegrationsPanel` `linux/linux_integrations.dart:13` | `+ui/linux/settings/IntegrationsPanel.kt` | P2 | RPC(linux+display) L A M T |
| 43 | `LinuxMacosPanel` `linux/linux_macos.dart:10` | `+ui/linux/settings/MacosPanel.kt` (∃ `LinuxMacosSettings.kt`) | P2 | RPC(linux) L S M T |
| 44 | `LinuxScaleSetting` `linux/linux_scale_setting.dart:7` | `+ui/linux/settings/ScaleSetting.kt` | P1 | RPC(linux) L S M T |
| 45 | `LinuxFastModeExperimentalTile` `linux/landlock_feature.dart:17` | `+ui/linux/settings/FastModeTile.kt` | P1 | RPC(linux) L S M T |
| 46 | `LinuxPrivilegeWarning` `linux/linux_privilege_warning.dart` | `+ui/privilege/PrivilegeWarningDialog.kt` | P1 | RPC(priv) L M T |
| 47 | `LinuxStorePage` `linux/linux_store_page.dart:690` | `+ui/linux/store/StoreScreen.kt` | P3 | RPC(linux90) L S A M T |
| 48 | `StoreAppPage` `linux/linux_store_page.dart:1821` | `+ui/linux/store/StoreAppScreen.kt` | P2 | RPC(linux) L M T |
| 49 | `LinuxStoreSourcesPanel` `linux/linux_store_sources.dart:13` | `+ui/linux/store/SourcesPanel.kt` | P2 | RPC(linux) L S M T |
| 50 | `LinuxBackupsUi` `linux/linux_backups.dart:16` | `+ui/linux/store/BackupsPanel.kt` | P2 | RPC(linux) L M T |
| 51 | `LinuxProgressDialog` `linux/linux_progress_dialog.dart:7` | `+ui/kit/ProgressDialog.kt` | P1 | L M T |
| 52 | `WindowsStoreView` `linux/windows_store.dart:86` | `+ui/linux/store/WindowsStoreScreen.kt` (∃ `WindowsCatalog.kt`) | P2 | RPC(linux) L A M T |
| 53 | `WindowsAppPage` `linux/windows_store.dart:289` | `+ui/linux/store/WindowsAppScreen.kt` | P2 | RPC(linux) L M T |
| 54 | Expressive widget kit `expressive/*`, `linux/linux_widgets.dart`, `penguin_icon.dart`, `linux/linux_installation_icon.dart` | ∃ `plasma/BookKit.kt` + `+ui/kit/*` (Section/Group/Toggle/Slider/KeyValue/StatusPill/MenuRow/Icons) | P0 | M L T |

Wave order: **W0** = 1, 54 (+prefs/RPC golden). **W1** = 5,6,8,11-23,28,44-46,51. **W2** = 3,4,9,20,24-26,29-43,48-50,52-53. **W3** = 2,7,10,27,32,38,47. Flutter deletion only after all 54 `T` green + 166-RPC count + 7-locale + no `libflutter.so` (W07_WEB steps 5-6).

### The 8 hardest screens and their risks

| Screen | Why hard | Top risks / device test |
|---|---|---|
| #47 `LinuxStorePage` (2246 LOC, 95 Linux RPCs) | Catalog/install/job queue/container switch, `ChangeNotifier` fan-out to 4 sub-pages | Silent RPC loss; stale StateFlow on Binder callback thread; 1GB rootfs download across process death. Test install/resume/cancel on HyperOS. |
| #24/#27 `display_topology.dart` (1713 LOC, 800ms poll) | Drag canvas of virtual displays, position offsets, live topology signature polling during apply | HyperOS virtual-display position mapping; writes racing `wm` IPC (BUG-02); jank at 120 Hz. Test DeX/extend/mirror transitions. |
| #2 `HomeScreen` (1594 LOC) | Session FSM + upgrade flow + gestures + orientation + theme + `WidgetsBindingObserver` | Regressing `DesktopSessionFsm` single-source-of-truth; predictive back (API 36) not firing `onBackPressed`; 40% cold-start claim. |
| #7 `DextopSetupPage` (1406 LOC) | Multi-step wizard with privilege/permission gates and prefs writes | First-run data loss via Flutter-prefix keys; alias icon/deep-link breakage; half-written state on rotation. Test fresh install + upgrade. |
| #29 `DextopFeaturesPage` (1410 LOC) | 15+ toggles, workspaces, cover display, metrics timer, many M3E dialogs/sheets | Toggle/policy drift across ~20 display RPCs; metrics timer recomposition storms; privilege-gated toggles silently off. |
| #32 `LinuxPage` (1495 LOC) | Container lifecycle hub launching setup/store/settings/app-mode and privilege recovery | Navigation state loss on config change; product-flavor (Play vs full privilege) branches; slow container listing. |
| #38 `LinuxResourcesPanel` (1360 LOC, `CustomPainter` graphs) | 1s CPU/mem/GPU/core sampling + live graphs | Recomposition/jank at 120 Hz; canvas graph parity; background sampling waking device. |
| #10 `LinuxSetupPage` (1093 LOC) | Long-running installs, phase labels, password/keyring, backup restore, log tail | StateFlow vs streamed progress mismatch; UI stuck after service death; leaked secret keys in prefs. |

Also watch #9 `EmbeddedBinderSetupPage` (notification RemoteInput pairing), #19-20 `KeyboardThemes` (live overlay demo), #30-31 `OverlayMenu` (system-overlay window + direct-touch), #52 `WindowsStoreView` (winget YAML slivers) — each has a non-Compose native seam but smaller surface.

### Recommended `ui/` package structure (upstream cherry-pick hygiene)

```
android/app/src/main/kotlin/moe/n4tsu/dextop/ui/
  DextopApp.kt            # native ComponentActivity + setContent; launcher-alias target
  UiHost.kt               # ActivityHost impl (W06), back dispatcher, SAF/share outputs
  navigation/{Routes,DextopNavGraph}.kt
  kit/{Theme,Tokens,Section,Group,Toggle,SliderTile,KeyValueRow,StatusPill,MenuRow,Icons,ProgressDialog}.kt
  home/{HomeScreen,HomeOverview,ResolutionSheets}.kt
  setup/{SetupWizard,EmbeddedBinderSetup}.kt
  onboarding/{GestureDemoFlow,ThreeFingerDemo,UpgradeFlow}.kt
  settings/{SettingsHome,MouseSettings,AutoSettings,KeyboardSettings,KeyboardThemes,
            KeyboardThemeEditor,SamsungSettings}.kt
  display/{DisplaySettings,TopologyScreen,TopologyEditor,ResolutionList,
           ResolutionDetail,ExternalDisplayModeCard}.kt
  features/FeaturesScreen.kt
  info/{AppInfo,ExperimentalFeatures,DiagnosticLog,DeviceReport}.kt
  overlay/{OverlayActivity,OverlayMenuScreen}.kt
  privilege/PrivilegeWarningDialog.kt
  linux/{LinuxHomeScreen,apps/*,settings/*,setup/*,session/*,monitor/*,storage/*,store/*}.kt
  book/{BookHost,BookSettingsHost}.kt   # thin adapters over existing plasma/* Compose
  prefs/FlutterPrefsMigrator.kt, Prefs.kt
  parity/{RpcGolden,ScreenParity}.kt
android/app/src/test/kotlin/moe/n4tsu/dextop/ui/parity/   # 54 screen tests + 166-RPC golden
```

Rules that keep upstream cherry-picks clean:
1. **Never edit `lib/`** during migration; freeze it. Upstream (github.com/niggetron/Dextop) commits touch `lib/`, `pubspec.yaml`, `MainActivity.kt`, `plasma/*`, `linux/*`. All new work lands in `ui/` only.
2. **No Flutter types in `ui/`** — depend on W06 `repo/*` interfaces and StateFlow; inject `ActivityHost`/`TextProvider` for side effects. Adapters to existing `MainActivity` channels stay in one `ui/parity/ChannelAdapter.kt`.
3. **Reuse, don't move, `plasma/*` Compose** — `ui/book` wraps `BookUi`/`BookSettings`/`BookPanels`/`BookWidgets`; W07B token/motion/a11y fixes land in `plasma/*` during M0–M3 while layouts converge, then get absorbed after W3.
4. **Manifest is the one shared file.** Put the alias/Activity registration in a separate `src/native/AndroidManifest.xml` (or narrow, well-commented diff) so upstream manifest cherry-picks do not collide; keep alias name unchanged (W07_WEB P0-2).
5. One `ui/` screen per Dart screen with the **same class name** (e.g. `DeviceReportScreen` ↔ `DeviceReportPage`) so parity review is mechanical; `parity/ScreenParity.kt` enumerates all 54 as the exit-gate table.

## Risks / device testing needed
- Verify `material3:1.5.0-alpha18` APIs (`MaterialExpressiveTheme`, adaptive `NavigableListDetailPaneScaffold`) against the actual BOM — no local build allowed.
- On Xiaomi Redmi Pad Pro 5G / API 36 / HyperOS 3: all 166 RPCs with byte-identical error codes; 7-locale key+placeholder parity; predictive-back gesture; launcher alias icon count + OEM deep links; 12.1" size-class transitions; 120 Hz graph/topology jank; TalkBack order over Compose overlay/menu layers; install/prefs migration rollback.
- Exit gate only on CI artifact: zero `libflutter.so`/`libapp.so`/`flutter_assets`, APK shrink ≥15–25 MB, cold start ≥40% faster.
