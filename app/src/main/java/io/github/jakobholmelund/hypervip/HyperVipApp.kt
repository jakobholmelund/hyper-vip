package io.github.jakobholmelund.hypervip

import android.app.Application
import io.github.jakobholmelund.hypervip.alert.VipAlerts

class HyperVipApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VipAlerts.createChannels(this)
    }
}
