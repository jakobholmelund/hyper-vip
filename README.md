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
| Accessibility (recommended) | HyperOS doesn't pass WhatsApp's ringing notification to notification access. The *Hyper VIP call watcher* accessibility service sees it instead, only for calling apps. While a VIP rings, a volume button press silences it. |
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
2. In silent or vibrate mode it rings straight away. If only Do Not Disturb is on, it first
   waits about a second to see if the phone rings anyway (for example because Do Not Disturb
   allows starred contacts).
3. It rings the way alarm clock apps do: a foreground service plays the ringtone with its own
   player on the alarm stream. (Posting a notification whose sound uses alarm audio attributes
   doesn't work: HyperOS silent mode mutes notification sounds regardless.)
4. When you answer, decline or miss the call, the ringing stops. Tapping **Silence** also stops
   it.
5. While a VIP rings, the alarm volume is raised to the level you choose (only ever raised) and
   then put back. If Do Not Disturb blocks alarms and you've granted Do Not Disturb access, it's
   switched to *alarms only* for the length of the ring and then restored.

For other apps, a notification listener spots incoming-call notifications (`CATEGORY_CALL`) and
messages from VIPs. Calls ring through whenever silent mode or any Do Not Disturb mode is on,
because Do Not Disturb can mute a calling app's own ringtone even when it lets the notification
through. Messages only alert if Do Not Disturb or silent mode held them back.

### Battery use

Hyper VIP has no background service, timer or wakelock. It only runs when Android hands it an
event: a call-state broadcast, a notification, or an accessibility event from a calling app (the
accessibility service is limited to those apps, and only intercepts volume buttons while a VIP is
ringing). The foreground service exists only while a VIP rings. HyperOS's "No restrictions"
battery setting is needed so that service may start from the background; it doesn't make the
app use more power.

### Privacy

Nothing leaves the phone: the app has no internet permission, and backups and device transfers
exclude its data. The activity log keeps caller names and app names only, never message text.

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

Release APKs are signed with a private key that is never committed. `scripts/build-apk.sh`
refuses to build without it. Provide it with either:

- `HYPERVIP_KEYSTORE_BASE64`: the PKCS12 keystore, base64-encoded on one line (convenient as a
  cloud environment variable), or `HYPERVIP_KEYSTORE_PATH`: a path to the keystore file
- `HYPERVIP_KEYSTORE_PASSWORD`: its password
- optionally `HYPERVIP_KEY_ALIAS` (default `hypervip`) and `HYPERVIP_KEY_PASSWORD` (default: the
  keystore password)

Keep a backup of the keystore and password. Android only installs an update signed with the same
key, so losing it means uninstalling and setting the app up again.

Early builds were signed with a key that was committed to this repository (now removed). That
key is public, so don't trust any APK signed with it.
