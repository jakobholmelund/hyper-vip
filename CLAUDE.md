# Hyper VIP — notes for Claude

- Android app (Kotlin, Jetpack Compose) that lets VIP contacts ring through HyperOS silent mode
  and Do Not Disturb. Single module `:app`, package `io.github.jakobholmelund.hypervip`.
- Build with `scripts/build-apk.sh` (installs the SDK if needed, writes `dist/hyper-vip.apk`).
  It needs `dl.google.com`; if a cloud session's network policy blocks it, ask the user to add
  that domain to the environment's allowed domains. Don't use GitHub Actions — the user doesn't
  want it. Deliver the APK to the user as a file (they install from their phone).
- The pure-Kotlin matching code in `match/Matching.kt` and its tests also compile with a plain
  Kotlin/JVM project, without the Android SDK.
- `targetSdk` stays at 34 on purpose (see README, "Why targetSdk is 34"). Lint's
  `ExpiredTargetSdkVersion` is disabled for that reason.
- VIP ringing is an insistent notification on an alarm-usage channel (`alert/VipAlerts.kt`).
  Notification channel settings are fixed once created, so changing a channel's sound or
  attributes needs a new channel id.
