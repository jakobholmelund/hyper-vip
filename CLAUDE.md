# Hyper VIP — notes for Claude

- Android app (Kotlin, Jetpack Compose) that lets VIP contacts ring through HyperOS silent mode
  and Do Not Disturb. Single module `:app`, package `io.github.jakobholmelund.hypervip`.
- Cloud sessions can't reach `dl.google.com`/`maven.google.com`, so the Android build can't run
  locally. Push and read the GitHub Actions run (`Build APK`) instead. The pure-Kotlin matching
  code in `match/Matching.kt` and its tests can be compiled with a plain Kotlin/JVM project.
- `targetSdk` stays at 34 on purpose (see README, "Why targetSdk is 34"). Lint's
  `ExpiredTargetSdkVersion` is disabled for that reason.
- VIP ringing is an insistent notification on an alarm-usage channel (`alert/VipAlerts.kt`).
  Notification channel settings are fixed once created, so changing a channel's sound or
  attributes needs a new channel id.
- The user installs from GitHub releases on their phone; every push publishes `build-<run>`.
