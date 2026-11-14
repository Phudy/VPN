package com.phudy.redline.vpn

import android.content.Context
import android.content.Intent
import com.phudy.redline.data.Prefs
import com.phudy.redline.data.VpnMode

object VpnController {
    /** Returns an error message, or null when the connection was started. */
    fun connect(ctx: Context): String? {
        return when (Prefs.mode.value) {
            VpnMode.SHIELD -> {
                ctx.startForegroundService(Intent(ctx, ShieldVpnService::class.java))
                null
            }
            VpnMode.WIREGUARD -> {
                val name = Prefs.selectedProfile.value
                val p = Prefs.profiles.value.firstOrNull { it.name == name }
                    ?: return "ยังไม่ได้เลือกเซิร์ฟเวอร์ — นำเข้าไฟล์ WireGuard ที่แท็บเซิร์ฟเวอร์"
                WgManager.connect(p); null
            }
        }
    }

    fun disconnect(ctx: Context) {
        if (WgManager.isUp() || Prefs.mode.value == VpnMode.WIREGUARD) WgManager.disconnect()
        ctx.startService(Intent(ctx, ShieldVpnService::class.java).setAction(ShieldVpnService.ACTION_STOP))
    }

    /** Reconnect so new settings (DNS, split tunnel, ad block) take effect. */
    fun restartIfOn(ctx: Context) {
        if (VpnHub.state.value == ConnState.ON) { disconnect(ctx); connect(ctx) }
    }
}
