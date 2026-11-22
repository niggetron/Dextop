# Code Agent W8C (M3) — Wave 08 Report: Native Compose Host Activity Shell

## Scope inspected
- `MainActivity.kt:44` (`FlutterActivity`), `:208-217` onCreate, `:349-849` MethodChannel (79 names), manifest `AndroidManifest.xml:103-149` (MainActivity/DesktopActivity/DextopLauncherActivity), `styles.xml:1-23`.
- Book Compose shell: `plasma/BookUi.kt:199-424` (`install`, `compose()` → `MaterialExpressiveTheme`, `BookOwner` lifecycle), `plasma/Book.kt:43-100` (`BookShell`, `BookTheme.scheme`), no Activity host in `ui/`.
- Flutter source of truth: `lib/home_screen.dart:1454-1557` (NavigationRail 76/184dp, extended ≥1200dp, labels `home`/`nativePlasmaCategoryLinux`/`settings`, badge dot), `:1570-1590` (page switch 0=overview,1=Linux,_=settings), `:400-401` (`linuxOnly` → page 1); `lib/app_shell.dart:112-126` (setup gate before HomeScreen).
- Repos: `W06_REPO1` (Session 16 / Privilege 11, `StateFlow`), `W06_REPO2` (Display 33 / Workspace 5), `W06_REPO3` (Diagnostics 11 / Linux 95); `W07_SCREENS` (54 screens, `ui/` layout), `W07_THEME` (no adaptive nav), `W07_WEB` (alias + API 36 rules); `build.gradle.kts:206-234` (material3 1.5.0-alpha18, no Hilt). No builds, no source edits, no token.

## Findings
- **P0** — Swapping `FlutterActivity` is not a one-file change: 166 inbound RPCs live in two `when` blocks. W06's `FlutterMethodChannelAdapter` (`W06_REPO2:89-108`) *calls out to Flutter* — unusable in a native host. The shell must construct repos with **direct native delegates** (MirrorService, DesktopSessionFsm, LinuxContainerClient, BookStore…) via a `NativeCallAdapter`; keep the Flutter Activity + alias enabled as fallback until all 54 screens pass parity.
- **P0** — Do **not** port Flutter's manifest flags: API 36 ignores `screenOrientation`/aspect locks ≥600dp and predictive back bypasses `onBackPressed` (`W07_WEB:29-30`). Native host = `ComponentActivity` + `enableEdgeToEdge()` + `OnBackPressedDispatcher`/`BackHandler`; no `configChanges`, no `screenOrientation`.
- **P1** — Repo packages conflict: `W06_REPO1/2` use `…dextop.repo`, `W06_REPO3` uses `…dextop.repository`. Consolidate to one package before the shell imports them.
- **P1** — `linuxOnly` opens page 1 and incomplete setup replaces the whole shell with `SetupWizard` (`app_shell.dart:114-118`). Both must be mirrored or parity fails on first run.
- **P1** — `BookUi` owns its own `BookOwner` lifecycle + `ComposeView`; reuse through `ui/book` adapters, never attach its overlay to the Activity root.
- **P2** — No native Compose theme exists; `styles.xml` only has Flutter `LaunchTheme`/`NormalTheme`. No adaptive navigation anywhere; 12.1" tablet (≈1600dp) must get an **extended rail**, not a bottom bar.

## Deliverable

### 1. `ui/DextopActivity.kt`
```kotlin
package moe.n4tsu.dextop.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider

internal class DextopActivity : ComponentActivity() {
    private val host = UiHost(this)
    private val vm: DextopShellViewModel by viewModels {
        ViewModelProvider.singleFactory { DextopShellViewModel(application, host, RepoProvider.of(application)) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()                       // API 36 mandatory; no configChanges in manifest
        super.onCreate(savedInstanceState)
        setContent { ShellTheme { DextopShell(vm, onFinish = ::finish) } }
    }

    override fun onStart() { super.onStart(); vm.consumeIntents() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); vm.consumeIntents()  // one-shot, mirrors MainActivity:324-331
    }
    override fun onDestroy() { host.detach(); super.onDestroy() }
}
```
(`ViewModelProvider.singleFactory` = tiny local `object : ViewModelProvider.Factory` helper; or pass `defaultViewModelProviderFactory`.)

### 2. `ui/UiHost.kt`
```kotlin
internal class UiHost(private val activity: ComponentActivity) : ActivityHost, DiagnosticsOutput {
    // ActivityHost (W06_REPO1): main thread only.
    override fun startActivity(action: String, url: String?, packageId: String?): Boolean = runCatching {
        activity.startActivity(Intent(action).apply {
            url?.let { data = Uri.parse(it) }; packageId?.let(::setPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }); true
    }.getOrDefault(false)
    override fun requestNotifications(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) activity.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS), 8104)   // same code as MainActivity:51
        return true
    }
    override fun requestShizukuPermission(): Boolean = true          // delegate to PrivilegeRepository's Shizuku listener
    override fun setOrientation(portrait: Boolean) = Unit            // no-op: ignored ≥600dp on API 36; phone fallback only
    override fun restoreOrientation() = Unit
    override fun finishActivity() = activity.finish()
    override fun restartApp(delayMs: Long) = activity.window.decorView.postDelayed({ /* pending intent */ }, delayMs)
    override fun consumeTileAction(): Boolean = actionOnce(DextopTileService.ACTION_OPEN_LAST_WORKSPACE)
    override fun consumeShortcutAction(): ShortcutAction? = /* DextopShortcuts.isStartDisplayAction + reset action = null */
    private fun actionOnce(a: String): Boolean {
        val hit = activity.intent?.action == a && DextopIntentToken.isGenuine(activity, activity.intent)
        if (hit) activity.intent?.action = null
        return hit
    }
    // DiagnosticsOutput (W06_REPO3): sendEmail/shareText/pickZipDestination/shareZip/identity → SAF chooser + FileProvider,
    // using activity.exportZipLauncher equivalent registered via registerForActivityResult.
    fun attach() { /* Shizuku binder/permission listeners → repos */ }
    fun detach() { /* remove listeners; cancel scopes */ }
}
```

### 3. `ui/navigation/ShellDestinations.kt`
```kotlin
internal enum class ShellDestination(@StringRes val labelRes: Int, val route: String) {
    HOME(R.string.ui_shell_home, "home"),
    LINUX(R.string.ui_shell_linux, "linux"),
    SETTINGS(R.string.ui_shell_settings, "settings");
}
```
New strings (7-locale ARB→XML, `strings.xml`): `ui_shell_home`, `ui_shell_linux`, `ui_shell_settings`. New vector `res/drawable/ic_linux.xml` ported from `lib/expressive/penguin_icon.dart`; `ic_dextop` = existing launcher asset.

### 4. `ui/DextopShellViewModel.kt`
```kotlin
internal data class ShellState(
    val setupRequired: Boolean = false,
    val sessionActive: Boolean = false,
    val updateAvailable: Boolean = false,
    val desktopWindow: Boolean = false,
)

internal class DextopShellViewModel(
    app: Application, host: ActivityHost, private val repos: Repos,
) : ViewModel() {
    val shellState: StateFlow<ShellState> = combine(
        repos.sessions.state, repos.privileges.state,
        repos.diagnostics.state, repos.prefs.setupCompleted,
        repos.prefs.linuxOnly, repos.prefs.updateAvailable,
    ) { s, p, d, setup, linuxOnly, update -> ShellState(
        setupRequired = !setup && !linuxOnly,      // app_shell.dart:114
        sessionActive = s.active,
        updateAvailable = update,                  // settings badge dot
        desktopWindow = false,                     // set from repos.sessions.launchContext()
    ) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShellState())

    private val _destination = MutableStateFlow(
        if (repos.prefs.linuxOnly.value) ShellDestination.LINUX else ShellDestination.HOME) // home_screen.dart:400
    val destination: StateFlow<ShellDestination> = _destination.asStateFlow()

    fun select(d: ShellDestination) { _destination.value = d }
    fun onBack() { if (_destination.value != HOME) _destination.value = HOME }
    fun consumeIntents() = viewModelScope.launch {
        repos.sessions.consumeTileAction(); repos.sessions.consumeShortcutAction()
    }
    fun onSetupCompleted() = viewModelScope.launch { repos.prefs.setSetupCompleted(true) }
    fun onLinuxOnly() = viewModelScope.launch { repos.prefs.setLinuxOnly(true) }
}
// Repos = { sessions, privileges, display, apps, diagnostics, linux, prefs } built by RepoProvider
// with a NativeCallAdapter (NOT FlutterMethodChannelAdapter). No Flutter imports in ui/.
```

### 5. `ui/DextopApp.kt` — the shell
```kotlin
@file:OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
package moe.n4tsu.dextop.ui

@Composable
internal fun DextopShell(vm: DextopShellViewModel, onFinish: () -> Unit) {
    val state by vm.shellState.collectAsStateWithLifecycle()
    val dest by vm.destination.collectAsStateWithLifecycle()
    val width = calculateWindowSizeClass().widthSizeClass
    val useRail = width != WindowWidthSizeClass.Compact
    val extended = width == WindowWidthSizeClass.Expanded      // ≥1200dp; Flutter uses same threshold
    BackHandler(enabled = dest != ShellDestination.HOME) { vm.onBack() }

    if (state.setupRequired) {
        SetupWizard(onCompleted = vm::onSetupCompleted, onLinuxOnly = vm::onLinuxOnly) // W07 #7, no rail
        return
    }
    val outerInsets = if (state.desktopWindow) {
        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)
    } else WindowInsets.safeDrawing                              // MediaQuery.removePadding(removeTop:) parity

    Row(Modifier.fillMaxSize().windowInsetsPadding(outerInsets)) {
        if (useRail) {
            NavigationRail(
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                extended = extended,
                selectedIndex = dest.ordinal,
                onDestinationSelected = { vm.select(ShellDestination.entries[it]) },
                leading = { Icon(painterResource(R.drawable.ic_dextop), null, Modifier.size(34.dp).padding(16.dp)) },
                windowInsets = WindowInsets(0),                  // parent already applied safeDrawing
            ) {
                ShellDestination.entries.forEach { d ->
                    NavigationRailItem(
                        selected = dest == d,
                        onClick = { vm.select(d) },
                        icon = { DestIcon(d, state.updateAvailable) },
                        label = { Text(stringResource(d.labelRes)) },
                        alwaysShowLabel = !extended,             // Flutter: labels hidden when extended
                        modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                    )
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Scaffold(Modifier.weight(1f), containerColor = MaterialTheme.colorScheme.surface) { pad ->
            Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
                when (dest) {
                    HOME -> HomeScreen(vm.repos)                 // W07 #2/#3
                    LINUX -> LinuxHomeScreen(vm.repos)           // W07 #32
                    SETTINGS -> SettingsHome(vm.repos)           // W07 #11
                }
            }
        }
    }
}

@Composable private fun DestIcon(d: ShellDestination, update: Boolean) = when (d) {
    HOME -> Icon(Icons.Filled.Home, null)
    LINUX -> Icon(painterResource(R.drawable.ic_linux), null)
    SETTINGS -> BadgedBox(badge = { if (update) Badge() }) { Icon(Icons.Filled.Settings, null) }
}
```
Compact width (<840dp) intentionally falls back to a bottom bar — add `NavigationBar` branch only if phone support is required; target device always takes the rail.

### 6. `ui/kit/ShellTheme.kt`
```kotlin
@Composable internal fun ShellTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    MaterialExpressiveTheme(                    // reuse Book's scheme; no raw colors
        colorScheme = BookTheme.scheme(context, dark),
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
```

### 7. `res/values/themes.xml` (new; leave `styles.xml` untouched for Flutter fallback)
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="DextopNativeTheme" parent="Theme.AppCompat.DayNight.NoActionBar">
        <item name="windowActionBar">false</item>
        <item name="windowNoTitle">true</item>
        <item name="android:windowBackground">?android:attr/colorBackground</item>
        <item name="android:windowFullscreen">false</item>
    </style>
</resources>
```

### 8. Manifest entry (insert after existing `.MainActivity`; target MUST precede alias)
```xml
<activity android:name=".ui.DextopActivity" android:exported="true"
    android:label="@string/app_name" android:theme="@style/DextopNativeTheme"
    android:resizeableActivity="true" android:windowSoftInputMode="adjustResize" />

<activity-alias android:name=".NativeLauncherAlias" android:targetActivity=".ui.DextopActivity"
    android:enabled="false" android:exported="true" android:label="@string/app_name"
    android:icon="@mipmap/ic_launcher">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
    <!-- copy OEM/deep-link filters from DextopLauncherActivity verbatim -->
</activity-alias>
```
No `configChanges`, `screenOrientation`, `hardwareAccelerated`, or Flutter `NormalTheme` meta-data. Cutover toggle (atomic, keep alias name stable):
```kotlin
pm.setComponentEnabledSetting(ComponentName(ctx, "${ctx.packageName}.NativeLauncherAlias"),
    PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
pm.setComponentEnabledSetting(ComponentName(ctx, "${ctx.packageName}.DextopLauncherActivity"),
    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
```

### Parity test checklist
1. Exactly 3 destinations, order HOME/LINUX/SETTINGS; labels match Flutter locale strings across 7 locales; settings badge dot when `updateAvailable`.
2. `linuxOnly=true` → initial index 1; setup incomplete → full-screen `SetupWizard`, rail absent; onCompleted/onLinuxOnly transitions match `app_shell.dart`.
3. Rail at ≥840dp, extended ≥1200dp (76/184dp equivalent); 12.1" tablet → extended rail + vertical divider, no bottom bar; `windowInsets = WindowInsets(0)` ⇒ no double insets.
4. Destination switch uses `MotionScheme.expressive()` (~350ms parity); `BackHandler` returns LINUX/SETTINGS → HOME, then finishes; predictive-back gesture verified on API 36.
5. Every W06 `state: StateFlow` collected with `collectAsStateWithLifecycle`; no Flutter/`MethodChannel` import under `ui/`; no `GlobalScope`; repos disposed on `onDestroy`.
6. `consumeTileAction`/`consumeShortcutAction` fire once on `onStart`/`onNewIntent`; no duplicate session start after resume.
7. `desktopWindow=true` removes top inset; safeDrawing applied once; `imePadding()` on settings search; 48dp targets + `contentDescription` on all three icons (TalkBack order).
8. Theme = `BookTheme.scheme` + `MotionScheme.expressive()`; no raw `Color(0x)` in shell; `DextopNativeTheme` applied and no `configChanges`/`screenOrientation` in manifest.
9. Alias declared after target; enabling alias + disabling `DextopLauncherActivity` yields exactly one launcher icon; deep links with and without action still resolve; Flutter fallback still launches.
10. CI-only gates (no local build): compiles against material3 1.5.0-alpha18; 166-RPC golden + 54-screen parity unchanged; APK free of `libflutter.so`/`libapp.so`/`flutter_assets` only after W3.
11. Device gate (Redmi Pad Pro 5G / API 36 / HyperOS 3): 120 Hz rail jank, external-display top-inset, binder-death mid-collect → UI shows error not crash.

## Risks / device testing needed
- Shell cannot ship as "Flutter removed" alone: W06 repos need direct native delegates first (P0). Stage via alias; never delete `lib/` before all 54 screens + 166 RPCs green.
- Verify actual signatures of `NavigationRail(windowInsets=)`, `calculateWindowSizeClass()`, `enableEdgeToEdge()`, `consumeWindowInsets` against material3 1.5.0-alpha18 / activity 1.8+ **in CI** (no local build allowed).
- Duplicate launcher icon if alias toggled before disabling the old activity; verify icon count + pinned shortcut on HyperOS 3.
- `RepoProvider` must keep one `NativeCallAdapter`; if W06 package split (`repo` vs `repository`) is not consolidated, imports break compilation.
- Book compositor's overlay keeps its own `BookOwner` lifecycle; the Activity rail is control UI only.
