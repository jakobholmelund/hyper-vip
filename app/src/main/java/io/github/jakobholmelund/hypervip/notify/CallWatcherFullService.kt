package io.github.jakobholmelund.hypervip.notify

/**
 * The full call watcher: same code as [CallWatcherService], but its config
 * (res/xml/call_watcher_full.xml) also grants window content and key filtering, for reading the
 * locked call screen and silencing a ring with a volume button.
 */
class CallWatcherFullService : CallWatcherService()
