package com.shilapi.xcertplay.bluetooth

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

class HybridBluetoothAdapter(
    val android: AndroidBluetoothAdapter,
    /** Head unit vendor stack (HSAE/ANW); used only when its service exists and Android cannot connect. */
    val hsae: HsaeBluetoothAdapter,
) : CarPlayBluetooth {

    companion object {
        private const val TAG = "HybridBluetoothAdapter"
    }

    override fun isSupported(): Boolean =
        android.isSupported() || hsae.isSupported()

    override fun isEnabled(): Boolean {
        if (hsae.isSupported() && hsae.isEnabled()) return true
        return android.isEnabled()
    }

    override fun enable(): Boolean {
        var ok = false
        if (hsae.isSupported()) {
            ok = hsae.enable()
        }
        if (android.enable()) ok = true
        return ok
    }

    override fun disable() {
        if (hsae.isSupported()) hsae.disable()
    }

    override fun getAdapterName(): String {
        val parts = mutableListOf<String>()
        if (android.isSupported()) parts.add("Android")
        if (hsae.isSupported()) parts.add("HSAE")
        return if (parts.isEmpty()) android.getAdapterName()
        else "Hybrid(${parts.joinToString("+")})"
    }

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        // Merge all stacks, dedupe by MAC.
        val seen = HashSet<String>()
        val merged = ArrayList<CarPlayBluetoothDevice>()
        fun addAll(devices: List<CarPlayBluetoothDevice>) {
            for (device in devices) {
                val key = device.address.lowercase()
                if (seen.add(key)) merged.add(device)
            }
        }
        runCatching { addAll(android.getPairedDevices()) }
        if (hsae.isSupported()) runCatching { addAll(hsae.getPairedDevices()) }
        return merged
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        android.getConnectedDevice()?.let { return it }
        if (hsae.isSupported()) {
            runCatching { hsae.getConnectedDevice() }.getOrNull()?.let { return it }
        }
        return null
    }

    override fun localAddress(context: Context): String? {
        // Prefer Android's own adapter address: the iPhone must pair with the
        // adapter that actually carries the iAP2 data channel.
        android.localAddress(context)?.let { return it }
        if (hsae.isSupported()) {
            val hsaeAddr = hsae.localAddress(context)
            if (!hsaeAddr.isNullOrBlank()) return hsaeAddr
        }
        return null
    }

    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        var failure: Exception? = null
        if (android.isSupported() && android.isEnabled()) {
            try {
                return android.openRfcommStream(deviceAddress, uuid)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                throw interrupted
            } catch (error: Exception) {
                failure = error
            }
        }
        if (hsae.isSupported()) return hsae.openRfcommStream(deviceAddress, uuid)
        throw java.io.IOException("Bluetooth RFCOMM unavailable: ${failure?.message ?: "no Bluetooth stack available"}", failure)
    }
}
