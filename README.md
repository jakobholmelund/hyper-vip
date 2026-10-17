# Hyper VIP

A proper VIP list for Xiaomi phones on HyperOS. Pick the people who should always reach you, and
their calls ring even when the phone is on **silent** or **Do Not Disturb**. Optionally it also
works for WhatsApp, Signal and Telegram calls, and plays a sound for VIPs' messages.

## Install

Hyper VIP isn't on any app store (see "Why not the Play Store" below).

1. Get `hyper-vip.apk` from the [Releases](https://github.com/jakobholmelund/hyper-vip/releases)
   page, or build it yourself (see "Building").
2. Open the file on your phone and allow installing apps when HyperOS asks.
3. Open **Hyper VIP** and work through the **Setup** card. Every step has a button that opens
   the right screen.

Newer APKs install over older ones and keep your VIPs.

## Setup on HyperOS

| Step | Why |
| --- | --- |
| Contacts | To pick VIPs and recognise them. |
| Phone and call logs | Android only tells an app who is calling if it has call log access. |
| Notifications | The VIP ring is a notification that plays on the alarm channel. |
| Battery: no restrictions | Otherwise HyperOS puts the app to sleep and it misses calls. If the standard prompt does nothing, use **HyperOS settings** and pick **No restrictions**. |
| Autostart | HyperOS only delivers incoming-call events to apps with Autostart on. Android can't check this, so tap **It's on** once you've enabled it. |
| Notification access (recommended) | Needed for WhatsApp, Signal and Telegram calls and for messages. It also keeps the app alive on HyperOS. |
| Do Not Disturb access (optional) | Only needed if your Do Not Disturb blocks alarms or uses *Total silence*. |

If HyperOS greys out a switch as a **Restricted setting** (this happens for sideloaded apps),
open *App info → ⋮ → Allow restricted settings* and try again.

Then use **Test ring** in the app to hear what a VIP call sounds like.

## How it works

HyperOS's own options don't help here: *Silent mode* mutes calls from everyone, including
starred contacts. Alarms are the one sound that silent mode and (by default) Do Not Disturb both
let through, so Hyper VIP rings on the alarm channel:

1. When a call comes in, Android sends `PHONE_STATE` with the caller's number. Hyper VIP checks
   it against your VIPs using the same contacts lookup the dialer uses for caller ID.
2. If the phone is silenced, it waits about a second to see if the phone rings anyway (for
   example because Do Not Disturb allows starred contacts). If it doesn't, Hyper VIP posts a
   looping (`FLAG_INSISTENT`) notification whose sound uses alarm audio attributes. The system
   plays that sound, so it keeps ringing even if HyperOS freezes the app.
3. When you answer, decline or miss the call, the notification is cancelled. Tapping
   **Silence** also stops it.
4. While a VIP rings, the alarm volume is raised to the level you choose (only ever raised) and
   then put back. If Do Not Disturb blocks alarms and you've granted Do Not Disturb access, it's
   switched to *alarms only* for the length of the ring and then restored.

For other apps, a notification listener spots incoming-call notifications (`CATEGORY_CALL` with a
CallStyle or full-screen intent) and messages from VIPs. It only steps in if Do Not Disturb or
silent mode actually held the notification back.

The **Recent activity** card shows what the app saw and did, which helps when something doesn't
ring.

### Why `targetSdk` is 34

Apps targeting Android 15 (API 35) or later can no longer change the phone-wide Do Not Disturb
state; they can only add their own rules, and the most restrictive rule wins. The *Total silence*
fallback relies on the old behaviour, so the app targets API 34. It is sideloaded, so Google
Play's target SDK requirement doesn't apply.

### Why not the Play Store

Google Play only allows call log access for a few kinds of apps (default dialers, caller ID,
spam blockers), and Android only reveals who is calling to apps with that access. Play also
requires a newer `targetSdk`. Sideloading avoids both.

## Building

You need Linux or macOS with JDK 17+ and access to `dl.google.com`. Run:

```sh
scripts/build-apk.sh
```

It installs the Android SDK into `~/android-sdk` if it isn't there, runs the unit tests and
writes the signed APK to `dist/hyper-vip.apk`. The version code is the git commit count, so
each build installs over the previous one.

### Signing

APKs are signed with `signing/hyper-vip.jks` (password `hypervip`), which is committed so that
builds from any machine install over each other. Because the repository is public, anyone could
sign an APK with this key, so only install Hyper VIP from this repository.

To sign with a private key instead, set `HYPERVIP_KEYSTORE_PATH`, `HYPERVIP_KEYSTORE_PASSWORD`,
`HYPERVIP_KEY_ALIAS` and `HYPERVIP_KEY_PASSWORD` before building. Android won't install an
update signed with a different key, so uninstall the app once after switching.
