package com.shilapi.xcertplay.bluetooth

import android.content.Context

object CarPlayBluetoothManager {
    @Volatile
    private var instance: HybridBluetoothAdapter? = null

    fun get(context: Context): HybridBluetoothAdapter {
        return instance ?: synchronized(this) {
            instance ?: HybridBluetoothAdapter(
                AndroidBluetoothAdapter(context.applicationContext),
                HsaeBluetoothAdapter(context.applicationContext),
            ).also { instance = it }
        }
    }
}
