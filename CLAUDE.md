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
- App calls (WhatsApp etc.) are handled in `notify/AppCalls.kt`, fed by both the notification
  listener and the accessibility service `notify/CallWatcherService.kt`. Tested on HyperOS: the
  listener gets WhatsApp messages and missed-call notifications but never the ringing one.
- VIP ringing is our own MediaPlayer on the alarm stream (`alert/Ringer.kt`) inside a
  foreground service (`alert/RingService.kt`). Don't go back to ringing via notification sounds
  with alarm attributes: tested on a real HyperOS phone, silent mode muted them.
