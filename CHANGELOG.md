# Changelog

## 1.1.0

- WhatsApp, Signal and Telegram calls from VIPs ring through **without accessibility**.
  HyperOS doesn't announce their ringing notification to notification access, but it does list
  it among the notifications showing, and Hyper VIP now checks that list. Accessibility watchers
  are only an optional backup, so banking apps keep working.
- Declining a VIP's app call (or the caller hanging up) now stops Hyper VIP's ring.
- Fixed a misleading "no call notification is visible" log line that appeared when the call
  notification simply showed up a moment after the phone started ringing.

## 1.0.3

- Experiment: besides being told about new notifications, notification access now also checks
  the list of notifications currently showing whenever notifications change or the phone starts
  ringing. If HyperOS lists WhatsApp's ringing notification there, calls are caught without
  accessibility ("spotted by notification access (list check)").
- Recent activity notes when an app call rings but no call notification is visible.

## 1.0.2

- HyperOS doesn't give notification access the ringing notification for WhatsApp-style calls, so
  those still need an accessibility watcher. There are now two to choose from:
  - **Hyper VIP call watcher** (light): only receives calling apps' notifications. It can't read
    the screen or intercept keys, so most banking apps still open.
  - **Hyper VIP call watcher (full)**: also reads the locked call screen and lets a volume button
    silence the ring.

## 1.0.1

- Accessibility is now an optional backup. Some banking apps refuse to open while any
  accessibility service is on, so Hyper VIP first tries to catch app calls through notification
  access alone.
- Notification access no longer skips a calling app's ringing notification when the app posts it
  as a group summary.
- Recent activity says which route spotted each app call, and notes when a calling app reports a
  missed call whose ringing notification never reached Hyper VIP.

## 1.0.0

First public release.

- VIP contacts ring through HyperOS silent mode, vibrate mode and Do Not Disturb, for normal
  phone calls and for WhatsApp, Signal, Telegram and other calling apps.
- Rings like an alarm clock, at a VIP alert volume you choose, with your own VIP ringtone.
- Optional sound for VIP messages.
- Volume button or **Silence** stops a VIP ring.
- Setup checklist with HyperOS shortcuts (battery saver, autostart, restricted settings), a
  test ring, a live view of the phone's sound settings and a recent-activity log.
- No internet access; nothing is backed up or transferred off the phone.
