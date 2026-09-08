# TDM — Telegram Download Manager

TDM is a native Android download manager for Telegram. It is built with Kotlin, Jetpack Compose, Room, coroutines, and TDLib. The application is designed for reliable, resumable downloads from Telegram sources, with persistent queues, scheduling, parallel transfers, recovery, and operational diagnostics.

> This repository is an engineering preview. It is not an official Telegram application and it does not distribute Telegram API credentials.

## Features

| Area | Implementation |
|---|---|
| Telegram access | TDLib Java API with the `libtdjni.so` native runtime |
| Authentication | Phone number, verification code, and optional two-factor password |
| Downloading | Persistent queue, resumable TDLib transfers, checkpoints, retry handling, and parallel work |
| Scheduling | Multiple time windows, weekday matching, overnight windows, and immediate download override |
| Storage | Android Storage Access Framework with user-selected destination folders |
| Reliability | Room database, startup reconciliation, foreground service, watchdog process, and heartbeat monitoring |
| Interface | Jetpack Compose screens for login, sources, downloads, history, schedules, settings, and statistics |
| Optional integration | Shizuku-based reliability enhancements when the user explicitly grants access |

## Important runtime fix

The original source could reach `Client.create(...)` without explicitly loading TDLib's JNI library. On devices where the library was not loaded automatically, this caused:

```text
No implementation found for int org.drinkless.tdlib.Client.createNativeClient()
```

The project now calls `System.loadLibrary("tdjni")` through a synchronized, idempotent loader before `Client.create(...)` and before the static TDLib log configuration. The TDLib AAR contains `libtdjni.so`, `libsslx.so`, and `libcryptox.so`; the Gradle configuration packages only the `arm64-v8a` variant for the smallest supported APK.

## Requirements

- JDK 17 or newer.
- Android SDK Platform 36 and Build Tools 36.0.0.
- Android device or emulator using the `arm64-v8a` ABI.
- A Telegram `api_id` and `api_hash` created at [my.telegram.org](https://my.telegram.org).

The application currently targets Android 12 behavior and uses `minSdk 31`. The release APK is intentionally 64-bit ARM only.

## Build from source

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

To create a signed release build, provide signing values through Gradle properties or environment variables. Do not commit a keystore or passwords.

```bash
RELEASE_STORE_FILE=/absolute/path/to/release.keystore \
RELEASE_STORE_PASSWORD='your-store-password' \
RELEASE_KEY_ALIAS='your-alias' \
RELEASE_KEY_PASSWORD='your-key-password' \
./gradlew :app:assembleRelease
```

The release artifact is written to `app/build/outputs/apk/release/`. Verify its ABI before distribution:

```bash
apkanalyzer apk summary app/build/outputs/apk/release/app-release.apk
unzip -l app/build/outputs/apk/release/app-release.apk | grep 'lib/arm64-v8a/'
```

## First-run setup

1. Install the arm64 release APK on an Android 12 or newer arm64 device.
2. Open TDM and enter your Telegram `api_id` and `api_hash`.
3. Complete the Telegram phone verification and two-factor authentication flow if enabled.
4. In **Settings → Storage**, select a destination folder through the Android document picker.
5. Add a Telegram source and configure its filter and queue behavior.
6. Optionally create a schedule profile and associate it with a source.
7. For better background reliability, review battery optimization settings for the device and application.

TDLib session data is kept in application-private storage. API credentials and session data must be treated as sensitive information.

## Architecture

```text
Compose UI
  -> application and state layer
  -> DownloadEngine
  -> QueueEngine / RetryEngine / Scheduler
  -> StorageAdapter and Room persistence
  -> TelegramClientPort
  -> TdlibClient
  -> TDLib Java API and libtdjni.so
```

`TdlibClient` is the only application adapter that directly uses TDLib. The rest of the application communicates through `TelegramClientPort`, which keeps Telegram-specific details out of the download engine and UI. The download engine persists task state and reconciles it with TDLib after process death or restart.

## Testing and validation

Unit tests cover queue ordering, state transitions, retry policies, schedule matching, natural filename ordering, concurrency decisions, filters, predictions, path templates, and storage behavior. A physical-device test is still required for end-to-end validation of login, large-file resume, Android background restrictions, and device-specific battery management.

A practical recovery test is: start a large download, force-stop the application, reopen it, and confirm that the task resumes from the existing TDLib partial data rather than restarting from zero.

## Security and release hygiene

Release signing is configured through external properties or environment variables. The repository must not contain private keystores, signing passwords, Telegram API credentials, or exported TDLib session directories. The release APK may be signed by the maintainer's private key, but that key must remain outside Git.

## Known limitations

- The application is currently limited to Android API level 31 and above.
- The distributed preview supports only `arm64-v8a` devices.
- Integration tests requiring a real Telegram account and device are not automated.
- Overlapping active schedule profiles use the first matching profile.
- TDLib is consumed as a prebuilt AAR rather than compiled from source in this repository.

## License and third-party notices

TDM source licensing should be selected by the project maintainer before broad distribution. The TDLib AAR is a separate third-party distribution containing TDLib under the Boost Software License 1.0 and OpenSSL components under the Apache License 2.0. Review the upstream notices before redistributing the application.

## Project status

This repository is published as a prerelease engineering build. Feedback should include the device model, Android version, ABI, application version, and relevant redacted log output.

## References

[1]: https://core.telegram.org/tdlib "TDLib official documentation"
[2]: https://developer.android.com/ndk/guides/abis "Android ABI management documentation"
[3]: https://developer.android.com/training/data-storage/shared/documents-files "Android Storage Access Framework documentation"
[4]: https://my.telegram.org "Telegram API development tools"
[5]: https://github.com/capullo-tech/lib-tdlib-android "lib-tdlib-android prebuilt TDLib AAR"
