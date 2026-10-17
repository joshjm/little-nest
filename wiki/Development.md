# Development, tools and verification

## Stack

- Native Android/Java 17, min SDK 28 (Android 9), target/compile SDK 35.
- Android Gradle Plugin 8.9.2; checksum-pinned Gradle wrapper 8.13.
- AppAuth 0.11.1 for Microsoft authorization-code/PKCE sign-in; ZXing Android
  Embedded 4.3.0 for local pairing QR generation/scanning. No Google Play services.
- Core-library desugaring 2.1.5 for Java collection/string APIs on older Android.
- Android SQLite, JobScheduler, AlarmManager, document picker and share intents.
- No WebView, JavaScript app runtime, backend or proprietary tracker dependency.

Source layout:

| File | Responsibility |
|---|---|
| `app/src/main/java/app/littlenest/MainActivity.java` | Native screens, local pairing, Microsoft consent and import/export |
| `app/src/main/java/app/littlenest/EventEditor.java` | Typed activity form, manual times, photos and timer creation |
| `app/src/main/java/app/littlenest/Catalog.java` | Activity categories and fields |
| `app/src/main/java/app/littlenest/Records.java` | Versioned operations, validation, ordering and timer semantics |
| `app/src/main/java/app/littlenest/Store.java` | Transactional SQLite operation log and materialized records |
| `app/src/main/java/app/littlenest/OneDriveApi.java` | Bounded Graph HTTP transport, immutable files and visible permission validation |
| `app/src/main/java/app/littlenest/OneDriveAuth.java` | Native browser OAuth, token exchange and refresh |
| `app/src/main/java/app/littlenest/LocalSync.java` | Pinned mutual TLS, expiring invitations, NSD discovery and foreground reconnect |
| `app/src/main/java/app/littlenest/LocalProtocol.java` | Bounded inventory and missing-operation exchange |
| `app/src/main/java/app/littlenest/SyncSecrets.java` | Keystore-encrypted credentials and local pairing secret |
| `app/src/main/java/app/littlenest/SyncEngine.java` | Pull/upload/pull, per-folder checkpoints and retries |
| `app/src/main/java/app/littlenest/SyncJob.java` | Best-effort Android background sync |
| `app/src/main/java/app/littlenest/Reminders.java` | Local notification and alarm scheduling |
| `app/src/main/java/app/littlenest/CarePredictor.java` | Deterministic recent-history feed/nap windows |
| `app/src/main/java/app/littlenest/CareComparison.java` | Moving-average clock schedules and comparable-history adjustments |
| `app/src/main/java/app/littlenest/CareReminders.java` | Per-phone options, revalidated alarms, snooze/dismiss |
| `app/src/main/java/app/littlenest/CarePlanUi.java` | Coming up, settings and upcoming explicit care |
| `app/src/main/java/app/littlenest/Insights.java` | Unit normalization, daily boundaries and sleep intervals |
| `app/src/main/java/app/littlenest/NaraImport.java` | Activity CSV mapping, stable source identities, preview and ongoing sleep recovery |
| `app/src/main/java/app/littlenest/CsvReader.java` | Bounded quoted/multiline UTF-8 CSV parsing |

## Tools installed during development

User-local installation, no system packages or desktop configuration modified:

- JDK: `mise install java@temurin-17`
- Android SDK: `~/.local/share/baby-app-tools/android-sdk`
- SDK packages: `platforms;android-35`, `build-tools;35.0.0`, `platform-tools`,
  `emulator`, `system-images;android-35;google_apis;x86_64`
- AVDs: `LittleNestTest` and clean `LittleNestImportTest`, Pixel 6 profile, KVM
- REA: project-local `tooling/node_modules/.bin/rea`, npm `rea-agents@6.5.0`
- JADX bridge: `~/.local/share/rea/android/jadx-headless-mcp-0.7.1-all.jar`
  with SHA-256
  `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75`

REA's repository skill and Android reference were read directly because the
current agent's skill loader does not contain that externally hosted skill.
The actual CLI was used as its documented fallback. No global agent/MCP
configuration was overwritten; MCP registration is not necessary for CLI use.
REA's Android provider doctor was verified healthy. Ghidra/Hopper are not needed
for this Android workflow and were not installed.

## Reproduce a build

Linux prerequisites: Node 22.19+/24.11+/26+, npm, curl, unzip, OpenSSL, a full
JDK 17+ (or `mise`), and a procps-compatible `ps` for REA.

```bash
bash scripts/bootstrap.sh
bash scripts/build-apk.sh
```

Bootstrap installs only user-local, task-related tools. Running it accepts the
Android SDK licenses. It uses `npm ci` with the checked-in tooling lockfile.
The Gradle wrapper downloads its pinned distribution and checks its SHA-256.

The APK and signing certificate information are placed in `artifacts/`.
Build outputs, dependency directories, keystores and artifact analysis are
ignored by version control.

### Signing and long-term updates

The build helper creates a strong random-password, RSA-3072 signing key at:

- `~/.local/share/little-nest/signing/release.jks`
- `~/.local/share/little-nest/signing/password`

Both have private file permissions and are outside the project. Back them up
securely together. Never commit either file. The password is not printed or
embedded in the APK.

For a different location, set `NEST_KEYSTORE` and
`NEST_KEYSTORE_PASSWORD_FILE` before running the signing/build scripts.
The key alias is `little-nest`. Existing material is preserved; partial signing
material fails explicitly rather than silently replacing the key.

Future release APKs must use the same key and application ID. Increment
`versionCode`/`versionName` in `app/build.gradle`.

## Run checks

```bash
source scripts/env.sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease

# Optional emulator setup, requiring additional disk/download:
sdkmanager 'emulator' 'system-images;android-35;google_apis;x86_64'
echo no | avdmanager create avd --name LittleNestTest \
  --package 'system-images;android-35;google_apis;x86_64' --device pixel_6
emulator -avd LittleNestTest -no-window -no-audio -gpu off -no-snapshot

# In another terminal, after adb reports a booted device:
source scripts/env.sh
./gradlew :app:connectedDebugAndroidTest
```

To exercise the exact signed release and save screenshots:

```bash
# Only on the disposable emulator. --reset clears this test app's sample data.
python3 scripts/smoke-apk.py emulator-5554 --reset --care-plan
python3 scripts/smoke-nara-import.py emulator-5554
```

Use an owned test emulator, not a phone with personal production data.
Instrumentation tests create test profiles. Depending on Gradle's test runner
cleanup, the app may be uninstalled after the run.

The import smoke uses synthetic CSV data and the real Android document picker.
It checks the recent ongoing-sleep option, finishing the sleep and reimporting
without duplication or restarting it. The general smoke also accepts
`--apk /path/to/previous.apk` to create old-version sample data before using
`--existing` with the new release to verify update retention.
`--care-plan` additionally saves manual intervals through native settings, waits
for a real suggested-care alarm notification, checks its displayed basis and
dismisses it. It requires notification-permission support on the test emulator.

This machine's current emulator host/software GPU backends stalled in graphics
fences, including System UI. `-gpu off -no-snapshot` (guest CPU rendering) worked.
This is a test-emulator setting, not disabled hardware acceleration in the app.
Wait for `adb shell getprop sys.boot_completed` to return `1` before testing.

### Optional private export acceptance

Private CSVs are deliberately not test assets or APK contents. To run the
additional local JVM test against your own export:

```bash
source scripts/env.sh
./gradlew :app:testDebugUnitTest -PnaraCsv=/absolute/path/to/export.csv
```

Importer class names and `nara*` storage/identity keys remain internal compatibility
details, not visible branding. Keep these stable so repeat imports and existing
backups remain compatible.

The test compares every nonempty original activity cell against retained
`naraSource`, exact timestamps and notes, in addition to synthetic field tests.
The Android acceptance test imports the full dataset twice, pages through all
records, merges into a second SQLite store and opens Today/History/Trends with
the full dataset. To enable it on a **disposable emulator**:

```bash
./gradlew :app:installDebug
adb -s emulator-5554 shell run-as app.littlenest mkdir -p files
adb -s emulator-5554 exec-in run-as app.littlenest sh -c \
  'cat > files/import-acceptance.csv' < /absolute/path/to/export.csv
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.naraCsv=/data/user/0/app.littlenest/files/import-acceptance.csv
```

This opt-in test creates real imported profiles in the emulator's app database.
Clear/uninstall only the test app afterward to remove private test data.
The normal suite skips private-file acceptance when no path is supplied.

### REA verification

```bash
source scripts/env.sh
tooling/node_modules/.bin/rea doctor --provider jadx --format json
bash scripts/inspect-apk.sh
```

This runs real `inspect-android-package` and `inspect-android-class` operations
through REA/JADX. Artifact-bound evidence includes APK SHA-256, provider
identity/digest, manifest, package/SDK declarations, permissions, class inventory,
locations, diagnostics and limitations.

Evidence goes to `artifacts/rea-package.json`, `artifacts/rea-drive-class.json`,
`artifacts/rea-import-class.json`, `artifacts/rea-predictor-class.json` and
`artifacts/rea-care-reminders-class.json`.
Static manifest evidence cannot prove runtime network behavior or security.
Use the executable tests and manual acceptance checks as separate evidence.

## Verification scope

### Version 1.6 sync replacement

The new setup and remaining real-account/physical-device acceptance steps are
documented in [Sync setup](Sync-Setup.md). The 1.6 tests exercise the production
OneDrive transport through simulated Graph responses and two SQLite stores,
including lost upload replies, cancellation, offline retry and visible extra
members. Local tests exercise real mutual-TLS sockets on Android, pinned
certificate/secret rejection, reconnect, keystore credential storage, bounded
frames and delta exchange with photos, running timers and tombstones.

No Microsoft app registration or credentials were supplied. Live Microsoft
consent, invitation representations, OneDrive permissions and two-account
transfer have **not** been verified. Physical-phone discovery, camera scanning,
hotspots and OEM background behavior require the two-phone acceptance checklist.
The emulator uses a disposable, separately named AVD and explicit device serial
to avoid interfering with another checkout or agent.

Validation in this worktree: 58 JVM tests, with 57 passed and the optional
private-export test skipped; debug app/test APKs built successfully; Android
lint reported zero errors and three warnings (the deliberate peer-pinning trust
manager plus the existing backup/drawing warnings). The full Android suite
passed 32 tests before the final family-setup regression was added; the final
focused sync/identity run passed all 12 tests, including that new case.
Real local TLS pairing exposed and verified the need to permit raw ECDSA
signing for Android's Conscrypt provider.

### Earlier 1.4 baseline

The following historical checks were completed before the sync replacement on
17 October 2026. They are not evidence of the new OneDrive or local transports:

- **77 JVM unit tests:** passed, including local full-export acceptance,
  source-field retention, parser edge cases, unit mapping, live-sleep recovery
  and 17 predictor/reminder-timing tests. Import errors and warnings are also
  checked for generic activity-export wording. 26 comparison tests check exact
  moving averages, earlier/later shifts, short sleep, small/large bottles,
  unmeasured breastfeeding, grouping/units, sparse data, guards and local/DST
  boundaries. They do not validate clinical prediction accuracy.
- **29 Android 15 emulator tests:** passed, including SQLite, native UI/timers,
  category add actions, minimum navigation touch targets and simulated
  two-phone Drive synchronization; also full-data double import, paged history,
  partner database merge, manual per-side duration editing and ongoing-sleep
  preview/finish. Seven care-plan tests cover silent defaults, profile isolation,
  alarm replacement/rearming, parent exclusion, real notification receiver and
  snooze/dismiss actions, stale/deleted-profile guards, native settings,
  appointments and notification-profile lifecycle restoration. Two identity
  tests check sans-serif headings, text contrast of at least 4.5:1 across the
  palette, activity tiles, generic import labels, the version footer and absence
  of competitor text on the native Today/History/Trends/Family screens.
  Two comparison tests exercise the native dual-prediction/evidence UI and
  unmeasured milk-volume handling on Android.
- **Android lint:** passed with 0 errors and 2 non-blocking warnings (legacy
  backup configuration guidance and a small drawing-time allocation).
- **Signed release smoke:** installed the exact deliverable APK and exercised
  profile creation, bottle logging, pause/resume/side-switch/finish, history,
  trends and family navigation. A separate exact-release CSV smoke passed through
  the real document picker, including ongoing sleep and safe repeat import.
  The care-plan smoke saved native settings, received a real alarm notification,
  checked the manual-window basis and dismissed the cycle.
- **APK signature/integrity:** `apksigner verify` and SHA-256 verification passed.
- **REA/JADX:** actual package/decoded-manifest, Drive, CSV importer, predictor
  and care-reminder class inspections executed; evidence retained with the APK.
- **Wiki links and shell helper syntax:** checked.
- **Version 1.0 → 1.1 upgrade:** existing profile and bottle logs retained when
  installing the same-key styling update over the prior release.
- **Version 1.1 → 1.2 upgrade:** recreated sample history on the old signed APK,
  installed the new release over it and verified the existing profile and
  bottle history survived the database/index migration.
- **Version 1.2 → 1.3 upgrade:** installed the same-key care-plan release over
  signed 1.2 sample history and verified the existing profile and bottle logs.
- **Version 1.3 → 1.4 upgrade:** installed the signed identity refresh over
  signed 1.3 sample history without clearing data; existing profiles and bottle
  logs survived, and timers, care alarms and document-picker imports passed.
- **Version 1.4 → 1.5 upgrade:** existing sample profiles and bottle history
  survived the same-key update. Signed care alarms, dismiss and CSV import
  passed. A synthetic-history import into the final signed APK displayed both
  exact comparison targets and a 26-minute earlier adjustment with its evidence.
- **Version 1.4 visual identity:** warm paper backgrounds, forest-green selected
  navigation, bold sans-serif headings, an original woven-nest mark and rounded
  activity tiles replace the earlier styling. Text-labelled Add controls and
  small icon badges replace circular plus controls and colored section strips.
  The signed APK was reviewed on Today, History, Trends, Family, activity forms
  and care details, with additional 150%/200% font-scale screen checks.
  This is an independent visual direction, not a claim of legal clearance.

The earlier automated checks covered:

- Order-independent revision convergence, concurrent-edit tie-breaking and
  tombstones.
- Pause/resume/side timing, midnight splitting and daylight-saving boundaries.
- Bottle unit normalization, invalid numeric values, CSV quoting/formula
  escaping, version rejection and photo-bearing JSON backup round-trip.
- Folder-ID parsing, bounded downloads and exactly-two-private-user permissions.
- Real Android SQLite transactions, idempotence and atomic invalid-import failure.
- Native UI launch, bottle entry, history, trends and family navigation.
- Two isolated Android SQLite stores communicating through the production
  Drive API boundary with a deterministic simulated Drive transport.
- Independent offline entries, photos, edits, deletions, lost-upload-response
  recovery, offline retry, idempotence and refusal to write with three members.
- Signed release APK verification with Android `apksigner`.
- An exact signed-release installation and UI smoke test, with screenshots in
  `artifacts/`: welcome, Today, History, Trends and Family.
- Background cancellation stops before subsequent Drive requests without
  discarding pending local operations.
- Learned median/range estimates, minimum samples/days, stale and incomplete
  logs, active/paused timers, top-ups, split sleeps, time-of-day matching,
  no nighttime nap prediction, outlier resistance and stable cycle identities.
- Advance timing, quiet-hour rollover/DST, snooze, dismiss and expiry; real
  native notification actions and per-baby/per-phone settings.
- Normal daily-slot clock averages and history-adjusted next-care intervals,
  with no guessed physiological debt or breastfeeding-volume conversion.

The earlier baseline did not verify:

- Google Cloud project creation, actual Google consent, email invitations,
  two-real-account Drive access and policy approval.
- Your two physical phones/OEM battery management and background scheduling.
- Exhaustive accessibility, localization, long-term large-history performance,
  Android versions other than the tested emulator and every image-provider app.

For the current version execute the [two-phone acceptance checklist](Sync-Setup.md#two-phone-acceptance-before-relying-on-sync).
Do not treat simulated network coverage as a claim of real-account verification.

## GitHub checks and releases

The GitHub workflow runs JVM tests, Android lint and a debug build on pushes to
`main` and pull requests. Private-export acceptance is skipped unless a local
path is explicitly supplied; no personal CSV is uploaded to CI.

Production signing remains local. See [GitHub / Obtainium releases](Releases.md)
for the checked publication helper, signing continuity and release asset layout.
No production signing secrets are configured in GitHub Actions.
