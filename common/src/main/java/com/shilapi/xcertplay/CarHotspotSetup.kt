package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.network.CarHotspotSettings
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** Each grant is requested explicitly from settings; startup never calls this authorization path. */
internal object CarHotspotSetup {
    // Older BYD units report QUALCOMM/qti and have no supported navigation-output service.
    fun isBydHeadUnit(context: Context): Boolean = runCatching {
        context.packageManager.getApplicationInfo("com.byd.carsettings", 0).flags and ApplicationInfo.FLAG_SYSTEM != 0
    }.getOrDefault(false)

    // Expose direct hotspot startup without ADB on legacy Android (all 4.4 units except BYD).
    fun isDirectHotspotSupported(context: Context): Boolean = !isBydHeadUnit(context)

    fun bootLaunchNeedsAdb(context: Context): Boolean =
        CarHotspotSettings.shouldEnable(context, true, AirPlayPersistence.loadWirelessHotspotMode(context)) &&
            !isDirectHotspotSupported(context)

    enum class Permission(val appOp: String) {
        HOTSPOT("WRITE_SETTINGS"), BOOT_LAUNCH("SYSTEM_ALERT_WINDOW");

        // API 19 grants these at install time.
        fun granted(@Suppress("UNUSED_PARAMETER") context: Context): Boolean = true
    }

    fun check(context: Context, adb: LocalAdb = LocalAdb(AdbKeys.load(context))): LocalAdb.Access = adb.use {
        it.connect(mayAsk = false)
    }

    fun grant(context: Context, permissions: List<Permission>, adb: LocalAdb = LocalAdb(AdbKeys.load(context))): LocalAdb.Access =
        adb.use {
            val access = it.connect(mayAsk = true)
            if (access == LocalAdb.Access.READY) {
                for (permission in permissions) {
                    if (!permission.granted(context)) {
                        it.shell("appops set ${context.packageName} ${permission.appOp} allow")
                    }
                    val granted = permission.granted(context)
                    if (!granted) break
                }
            }
            access
        }

    fun shouldStartOnLaunch(context: Context, hasSession: Boolean): Boolean =
        !hasSession && CarHotspotSettings.shouldEnable(context,
            AirPlayPersistence.loadWirelessEnabled(context), AirPlayPersistence.loadWirelessHotspotMode(context)) &&
            ManualHotspotValidation.error(AirPlayPersistence.loadManualHotspotSsid(context),
                AirPlayPersistence.loadManualHotspotPassphrase(context)) == null
}
