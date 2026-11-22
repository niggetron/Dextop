# W9C (M4) — Wave 9 Rule-Isolation Test Design Report

## Scope inspected
- `DeviceProfiles.kt:8-96`: narrow rule `:69-84` = `DeviceMatch(manufacturers={xiaomi,redmi}, devices={duchamp}, fingerprintPrefixes={redmi/duchamp,xiaomi/duchamp}, minSdk=36)`; broad `xiaomi_hyperos` `:85-87` = manufacturer-only `{xiaomi,redmi,poco}` (default minSdk 29). Rule order: narrow before broad.
- `DesktopEnvironment.kt:22-46` `DeviceMatch.matches()` ANDs every non-empty field; sdk range `:34`; manufacturer `:35`; model/prefix `:36-39`; device `:40`; fingerprint prefix `:41-43`. `:175-178` resolve = `firstOrNull`. `:191-207` `xiaomiHyperos` baseline; narrow adds only `maxRateApplicationDisplays`, `pinPhysicalMouseToDesktop`, `directDeckKeyboardToApp` (`DeviceProfiles.kt:80-82`).
- `XiaomiFeatureFlags.kt:11-41`: prefs flags; **never** use as match criteria (escape hatches, not capability proof).
- Existing `src/test/.../DeviceMatchTest.kt:1-127` (JVM-only, no Robolectric; avoid `DeviceIdentity.current()`). No builds, no source edits.

## Findings
- **P2 — POCO X6 Pro shares codename `duchamp`.** The *only* field excluding POCO from the narrow rule is `manufacturers`. If it is ever widened to include `poco` (copy/paste from the broad rule), a phone receives `maxRate/pin/directDeck` — the exact W03_BACKEND P0 leak. Mitigation: `pocoDuchampSpoof` case below pins this invariant.
- **P2 — asymmetric normalization.** `DesktopEnvironment.kt:41-43` lowercases the identity fingerprint but compares against the raw rule prefix. A future mixed-case prefix silently disables the guard. Fix: `startsWith(it.lowercase().trim())`; also prefer a delimited token check over bare `startsWith` (`redmi/duchampX` would otherwise pass).
- **P3 — `brand` is unused by `DeviceMatch`.** Tests vary `brand` deliberately to document that `manufacturer` is authoritative.

## Deliverable — exact Kotlin to append to `android/app/src/test/kotlin/moe/n4tsu/dextop/DeviceMatchTest.kt`
(Imports already present: `kotlin.test.{Test,assertEquals,assertFalse,assertTrue}`. Names avoid the existing `xiaomiAurora`/`pixel`.)

```kotlin
    // ---- W9C isolation fixtures -------------------------------------------------
    private val redmiPadPro5g = DeviceIdentity(
        "Redmi", "redmi", "Redmi Pad Pro 5G", "duchamp", "duchamp_global",
        "redmi/duchamp_global/duchamp:16/BP2A.250605.015/release-keys", 36,
    )
    private val xiaomi14Ultra = DeviceIdentity( // 24030PN60G, Android 16
        "Xiaomi", "Xiaomi", "24030PN60G", "aurora", "aurora_global",
        "Xiaomi/aurora_global/aurora:16/BP2A.250605.015/release-keys", 36,
    )
    private val pocoRodin = DeviceIdentity(
        "POCO", "POCO", "25053RP47C", "rodin", "rodin_global",
        "POCO/rodin_global/rodin:16/BP2A.250605.015/release-keys", 36,
    )
    private val galaxyM1q = DeviceIdentity(
        "samsung", "samsung", "SM-S942Z", "m1q", "m1q",
        "samsung/m1q/m1q:16/BP2A.250605.015/release-keys", 36,
    )
    private val googlePixel = DeviceIdentity(
        "Google", "google", "Pixel 9", "tokay", "tokay",
        "google/tokay/tokay:16/BP2A.250605.015/release-keys", 36,
    )
    private val narrowRedmiRule = DeviceProfiles.rules.single { it.id == "xiaomi_redmi_pad_pro_5g" }
    private val broadXiaomiRule = DeviceProfiles.rules.single { it.id == "xiaomi_hyperos" }

    private fun matchesNarrow(id: DeviceIdentity) = narrowRedmiRule.match.matches(id)
    private fun matchedRuleId(id: DeviceIdentity) =
        DeviceProfiles.rules.firstOrNull { it.match.matches(id) }?.id

    /** Single source of truth: narrow matches iff the expected rule is the narrow one. */
    @Test fun isolationMatrixResolvesEveryIdentityToExactlyOneExpectedRule() {
        val spoofedRedmiFp = "redmi/duchamp_global/duchamp:16/build"
        val cases = listOf(
            redmiPadPro5g to "xiaomi_redmi_pad_pro_5g",
            redmiPadPro5g.copy(manufacturer = "Xiaomi", brand = "Xiaomi",
                fingerprint = "Xiaomi/duchamp_global/duchamp:16/build") to "xiaomi_redmi_pad_pro_5g",
            redmiPadPro5g.copy(sdk = 37) to "xiaomi_redmi_pad_pro_5g",
            redmiPadPro5g.copy(sdk = 99) to "xiaomi_redmi_pad_pro_5g", // maxSdk unbounded
            redmiPadPro5g.copy(device = "aurora") to "xiaomi_hyperos",
            redmiPadPro5g.copy(fingerprint = "redmi/aurora_global/aurora:16/build") to "xiaomi_hyperos",
            redmiPadPro5g.copy(sdk = 35) to "xiaomi_hyperos",
            xiaomi14Ultra to "xiaomi_hyperos",
            pocoRodin to "xiaomi_hyperos",
            pocoRodin.copy(device = "duchamp", product = "duchamp_global",
                fingerprint = spoofedRedmiFp) to "xiaomi_hyperos", // POCO X6 Pro codename collision
            galaxyM1q to "samsung_dex",
            galaxyM1q.copy(device = "duchamp", fingerprint = spoofedRedmiFp) to "samsung_dex",
            googlePixel to "pixel_android",
            googlePixel.copy(device = "duchamp", fingerprint = spoofedRedmiFp) to "pixel_android",
        )
        cases.forEach { (id, expected) ->
            assertEquals(expected, matchedRuleId(id), "${id.manufacturer}/${id.device}/sdk${id.sdk}")
            assertEquals(expected == "xiaomi_redmi_pad_pro_5g", matchesNarrow(id),
                "narrow rule match must be exclusive: ${id.manufacturer}/${id.device}")
        }
    }

    @Test fun isolationNarrowRuleExposesExactGuardFieldsAndScopedFlagsOnly() {
        val m = narrowRedmiRule.match
        assertEquals(setOf("xiaomi", "redmi"), m.manufacturers)
        assertEquals(setOf("duchamp"), m.devices)
        assertEquals(setOf("redmi/duchamp", "xiaomi/duchamp"), m.fingerprintPrefixes)
        assertTrue(m.models.isEmpty())        // model is NOT a guard
        assertTrue(m.modelPrefixes.isEmpty()) // modelPrefix is NOT a guard
        assertEquals(36, m.minSdk)
        assertEquals(Int.MAX_VALUE, m.maxSdk)

        assertTrue(matchesNarrow(redmiPadPro5g))
        assertTrue(matchesNarrow(redmiPadPro5g.copy(model = "anything", product = "anything")))
        val env = DesktopEnvironmentRegistry.resolve(redmiPadPro5g)
        assertEquals("xiaomi_redmi_pad_pro_5g", env.id)
        // Three flags that exist ONLY on the narrow rule.
        assertTrue(env.maxRateApplicationDisplays)
        assertTrue(env.pinPhysicalMouseToDesktop)
        assertTrue(env.directDeckKeyboardToApp)
        // Inherited HyperOS baseline.
        assertTrue(env.directPlasmaDisplay)
        assertTrue(env.untrustedMirrorDisplay)
        assertTrue(env.fullscreenApplicationDisplays)
        assertTrue(env.passivePlasmaShellFocus)
        assertEquals("dextop_book", env.defaultWindowManager)
        assertFalse(env.privateApplicationDisplays)
        assertFalse(env.plasmaApplicationOverlay)
        assertEquals(listOf("cmd_window", "wm", "activity_task_manager"), env.windowingStrategies)
    }

    @Test fun isolationCaseAndWhitespaceNormalizationStillMatchesNarrow() {
        val messy = redmiPadPro5g.copy(
            manufacturer = "  REDMI  ",
            device = "DUCHAMP",
            fingerprint = "  REDMI/duchamp_global/duchamp:16/build  ",
        )
        assertTrue(matchesNarrow(messy))
        assertEquals("xiaomi_redmi_pad_pro_5g", DesktopEnvironmentRegistry.resolve(messy).id)
    }

    @Test fun isolationDuchampMustBeAFingerprintPrefixNotAMiddleToken() {
        for (fp in listOf(
            "redmi/aurora/duchamp:16/build",
            "notredmi/duchamp_global/duchamp:16/build",
            "poco/duchamp_global/duchamp:16/build",
        )) {
            val id = redmiPadPro5g.copy(fingerprint = fp)
            assertFalse(matchesNarrow(id), fp)
            assertEquals("xiaomi_hyperos", matchedRuleId(id), fp)
        }
    }

    @Test fun isolationXiaomi14UltraNeverGetsTabletOnlyFlags() {
        assertFalse(matchesNarrow(xiaomi14Ultra))
        assertTrue(broadXiaomiRule.match.matches(xiaomi14Ultra))
        assertEquals("xiaomi_hyperos", matchedRuleId(xiaomi14Ultra))
        val env = DesktopEnvironmentRegistry.resolve(xiaomi14Ultra)
        assertEquals("xiaomi_hyperos", env.id)
        assertFalse(env.maxRateApplicationDisplays)
        assertFalse(env.pinPhysicalMouseToDesktop)
        assertFalse(env.directDeckKeyboardToApp)
        assertTrue(env.directPlasmaDisplay)
    }

    @Test fun isolationPocoNeverMatchesNarrowEvenWithDuchampIdentity() {
        val spoof = pocoRodin.copy(
            device = "duchamp", product = "duchamp_global",
            fingerprint = "redmi/duchamp_global/duchamp:16/build", model = "Redmi Pad Pro 5G",
        )
        for (id in listOf(pocoRodin, spoof, pocoRodin.copy(sdk = 35), pocoRodin.copy(sdk = 37))) {
            assertFalse(matchesNarrow(id), "POCO ${id.device}/sdk${id.sdk}")
            assertEquals("xiaomi_hyperos", matchedRuleId(id))
            val env = DesktopEnvironmentRegistry.resolve(id)
            assertEquals("xiaomi_hyperos", env.id)
            assertFalse(env.pinPhysicalMouseToDesktop)
            assertFalse(env.maxRateApplicationDisplays)
        }
    }

    @Test fun isolationSamsungAndPixelNeverMatchNarrowEvenWithSpoofedFields() {
        val spoofedRedmiFp = "redmi/duchamp_global/duchamp:16/build"
        val samsungs = listOf(
            DeviceIdentity("samsung", "samsung", "SM-F968N", "q7mq", "q7mqksx",
                "samsung/q7mqksx/q7mq:16/build", 36),
            galaxyM1q,
            galaxyM1q.copy(device = "duchamp", fingerprint = spoofedRedmiFp),
        )
        samsungs.forEach { id ->
            assertFalse(matchesNarrow(id), "Samsung ${id.device}")
            assertEquals("samsung_dex", DesktopEnvironmentRegistry.resolve(id).id)
            assertEquals("system", DesktopEnvironmentRegistry.resolve(id).defaultWindowManager)
        }
        for (sdk in listOf(33, 35, 36, 37)) {
            val id = googlePixel.copy(sdk = sdk)
            assertFalse(matchesNarrow(id), "Pixel sdk $sdk")
            assertEquals("pixel_android", matchedRuleId(id))
            val env = DesktopEnvironmentRegistry.resolve(id)
            assertEquals("pixel_android", env.id)
            assertFalse(env.pinPhysicalMouseToDesktop)
            assertFalse(env.directDeckKeyboardToApp)
            assertFalse(env.maxRateApplicationDisplays)
        }
        val spoofedPixel = googlePixel.copy(device = "duchamp", fingerprint = spoofedRedmiFp)
        assertFalse(matchesNarrow(spoofedPixel))
        assertEquals("pixel_android", matchedRuleId(spoofedPixel))
    }

    @Test fun isolationBroadHyperosStillResolvesForEveryPhoneVariant() {
        val phones = listOf(
            xiaomi14Ultra,
            xiaomi14Ultra.copy(sdk = 33),
            xiaomi14Ultra.copy(sdk = 35),
            xiaomi14Ultra.copy(sdk = 37),
            xiaomi14Ultra.copy(model = "24031PN0DC", device = "houji",
                fingerprint = "Xiaomi/houji_global/houji:16/build"),
            pocoRodin,
            pocoRodin.copy(manufacturer = "Redmi", brand = "Redmi"), // generic Redmi phone
            redmiPadPro5g.copy(device = "aurora",
                fingerprint = "redmi/aurora_global/aurora:16/build"),
        )
        phones.forEach { id ->
            assertFalse(matchesNarrow(id), "phone ${id.manufacturer}/${id.device}")
            assertTrue(broadXiaomiRule.match.matches(id))
            assertEquals("xiaomi_hyperos", matchedRuleId(id))
            val env = DesktopEnvironmentRegistry.resolve(id)
            assertEquals("xiaomi_hyperos", env.id)
            assertTrue(env.directPlasmaDisplay)
            assertTrue(env.untrustedMirrorDisplay)
            assertTrue(env.fullscreenApplicationDisplays)
            assertTrue(env.passivePlasmaShellFocus)
            assertFalse(env.privateApplicationDisplays)
            assertEquals("dextop_book", env.defaultWindowManager)
            assertFalse(env.maxRateApplicationDisplays)
            assertFalse(env.pinPhysicalMouseToDesktop)
            assertFalse(env.directDeckKeyboardToApp)
        }
    }
```

## Risks / device testing needed
- M1 ground-truth ADB capture must confirm `Build.DEVICE=duchamp` and the exact `Build.FINGERPRINT` starts with `redmi/duchamp` or `xiaomi/duchamp` on the target HyperOS 3 / API 36 tablet; a different prefix silently falls through to broad HyperOS (loses 120 Hz + mouse pinning).
- If the fingerprint guard is tightened per P2 above, re-run this matrix; do not loosen `manufacturers` to `poco` to "fix" a POCO collision (P2 finding).
- BackendId/selector-level isolation (E→D→F, broad Xiaomi → C) remains covered by `W03_BACKEND.md`; do not duplicate `BackendId` declarations in this JVM test file.
