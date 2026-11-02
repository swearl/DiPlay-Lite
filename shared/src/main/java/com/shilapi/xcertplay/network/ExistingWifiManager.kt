package com.shilapi.xcertplay.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import com.shilapi.xcertplay.orchestration.ManualHotspotValidation

/** Attaches to an existing station network. Never creates an AP, joins Wi-Fi or changes routing. */
class ExistingWifiManager(
    context: Context,
    private val ssid: String,
    private val passphrase: String,
    private val onDiagnostic: (String) -> Unit = {},
    private val onNetworkChanged: () -> Unit = {},
) : WirelessHotspotManager {
    @Suppress("DEPRECATION")
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: throw IllegalStateException("ConnectivityManager is unavailable")
    @Suppress("DEPRECATION")
    private val wifi = context.applicationContext
        .getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: throw IllegalStateException("WifiManager is unavailable")
    private val lock = Any()
    @Volatile private var closed = false
    private var legacy: LegacyExistingWifi? = null

    init {
        require(ManualHotspotValidation.error(ssid, passphrase) == null) {
            "Invalid existing Wi-Fi credentials"
        }
    }

    override fun start(timeoutMillis: Long): WirelessHotspotInfo {
        val adapter = synchronized(lock) {
            check(!closed) { "Existing Wi-Fi attachment was cancelled" }
            LegacyExistingWifi(connectivity, wifi, ssid, passphrase,
                onDiagnostic, onNetworkChanged).also { legacy = it }
        }
        return adapter.start(timeoutMillis)
    }

    override fun connectionDiagnosticSnapshot(): String =
        "existingWifiLink=${if (closed) "lost" else "attached"}"

    override fun close() {
        synchronized(lock) {
            closed = true
            legacy?.close()
        }
    }
}
