# Build fixes applied (Oct 2026)

Ye notes is folder ke fixes ka record hai — GitHub Actions build log me jo
compilation errors aaye the, sab yahan fix hue hain.

## Kotlin / Gradle compilation fixes

| File | Problem | Fix |
|---|---|---|
| `app/build.gradle.kts` | `ApiClient.kt` uses `BuildConfig.VERSION_NAME`, but AGP 8 disables BuildConfig generation by default | Added `buildFeatures { buildConfig = true }` |
| `DeviceService.kt` | `PendingIntent` used in `buildNotification()` with no import | Added `import android.app.PendingIntent` |
| `DeviceService.kt` | `storageMb()` returns `Pair<Long, Long>?` but code did `storage.first` / `storage.second` | Changed to `storage?.first` / `storage?.second` |
| `DeviceService.kt` | `requestLocationUpdates(..., mainLooper)` — no such member on `Service` | `Looper.getMainLooper()` + `import android.os.Looper` |
| `ScreenCaptureService.kt` | `Handler(getMainLooper())` — no such member on `Service` | `Handler(Looper.getMainLooper())` + `import android.os.Looper` |
| `res/values/strings.xml` | `activity_main.xml` referenced `@string/share_button` + `@string/share_stop_button` which were never defined (AAPT link failure) | Both strings added |

## CI workflow (`.github/workflows/android-build.yml`)

- Runs on `ubuntu-22.04` (stable image, preinstalled Android SDK; not affected by the `ubuntu-latest` → Ubuntu 26 migration).
- Pins **Gradle 8.7** and forces it first on `PATH` — the runner ships Gradle 9.x, which AGP 8.2.2 cannot use (it dies fingerprinting task inputs). A guard aborts with a clear error if 8.7 does not take effect.
- Accepts all Android SDK licences, then installs `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`.
- Writes `android/local.properties` with `sdk.dir` automatically.
- Writes a full diagnostic report (Java / Gradle / SDK versions, `e:` compiler lines, last log lines) to the job summary, so a failure is readable without digging through logs.
- Uploads any produced APK as artifact `connectdesk-debug-apk`.

## Verification status

Kotlin/Gradle compilation errors: all fixed and audited (imports, resource
references `@string`/`@color`/`R.id`, nullable receivers).
The APK build itself is verified on GitHub Actions — run
`Build Android APK` and download the `connectdesk-debug-apk` artifact.
