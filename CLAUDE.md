# Hyper VIP — notes for Claude

- Android app (Kotlin, Jetpack Compose) that lets VIP contacts ring through HyperOS silent mode
  and Do Not Disturb. Single module `:app`, package `io.github.jakobholmelund.hypervip`.
- Build with `scripts/build-apk.sh` (installs the SDK if needed, writes `dist/hyper-vip.apk`).
  It needs `dl.google.com`; if a cloud session's network policy blocks it, ask the user to add
  that domain to the environment's allowed domains. Don't use GitHub Actions — the user doesn't
  want it. Deliver the APK to the user as a file (they install from their phone).
- Signing: the release key comes from the `HYPERVIP_KEYSTORE_BASE64` and
  `HYPERVIP_KEYSTORE_PASSWORD` environment variables (set in the user's cloud environment). Never
  commit a keystore, print the key material, or sign with a new key — a different key means the
  user has to uninstall. If the variables are missing, ask the user to add them.
- Releases: bump `versionName` in `app/build.gradle.kts` and add a CHANGELOG.md entry. The
  version code is the commit count, so never squash or rewrite pushed history.
- The pure-Kotlin matching code in `match/Matching.kt` and its tests also compile with a plain
  Kotlin/JVM project, without the Android SDK.
- `targetSdk` stays at 34 on purpose (see README, "Why targetSdk is 34"). Lint's
  `ExpiredTargetSdkVersion` is disabled for that reason.
- App calls (WhatsApp etc.) are handled in `notify/AppCalls.kt`, fed by both the notification
  listener and the accessibility services `notify/CallWatcherService.kt` (light: notification
  events only, so banking apps tolerate it) and `CallWatcherFullService` (adds window content and
  key filtering). Tested on HyperOS: the listener gets WhatsApp messages and missed-call
  notifications but never the ringing one, locked or unlocked.
- VIP ringing is our own MediaPlayer on the alarm stream (`alert/Ringer.kt`) inside a
  foreground service (`alert/RingService.kt`). Don't go back to ringing via notification sounds
  with alarm attributes: tested on a real HyperOS phone, silent mode muted them.
