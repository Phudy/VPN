package com.phudy.redline

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.phudy.redline.data.Prefs
import com.phudy.redline.vpn.Blocklist
import com.phudy.redline.vpn.WgManager

class RedlineApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Prefs.init(this)
        Blocklist.init(this)
        WgManager.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_VPN, "สถานะ VPN", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_VPN = "vpn_status"
        lateinit var instance: RedlineApp
            private set
    }
}
