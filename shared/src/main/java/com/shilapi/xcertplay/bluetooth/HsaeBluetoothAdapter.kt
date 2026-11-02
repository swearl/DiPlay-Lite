package com.shilapi.xcertplay.bluetooth

import android.content.Context
import android.os.RemoteException
import android.provider.Settings
import android.util.Log
import com.hsae.autosdk.bt.phone.BtPhoneProxy
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream

class HsaeBluetoothAdapter(
    private val context: Context
) : CarPlayBluetooth {

    companion object {
        private const val TAG = "HsaeBluetoothAdapter"
    }

    private val adapterName = "HsaeBluetoothAdapter"

    override fun isSupported(): Boolean {
        return runCatching {
            val pm = context.packageManager
            pm.getPackageInfo(HsaeRfcommStream.SERVICE_PACKAGE, 0) != null
        }.getOrDefault(false)
    }

    override fun isEnabled(): Boolean {
        return try {
            val status = BtPhoneProxy.getInstance().getBTPowerStatus()
            status == 1
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException reading HSAE power status", e)
            false
        }
    }

    override fun enable(): Boolean = true

    override fun disable() {}

    override fun getAdapterName(): String = adapterName

    override fun getPairedDevices(): List<CarPlayBluetoothDevice> {
        return try {
            val paired = BtPhoneProxy.getInstance().getPairedList().orEmpty()
            paired.map { dev ->
                val mac = dev.macAddress ?: ""
                val name = dev.devicesName?.takeIf { it.isNotBlank() } ?: "iPhone"
                val isConnected = dev.a2dpStatus == 1
                CarPlayBluetoothDevice(
                    name = name,
                    address = mac,
                    isConnected = isConnected
                )
            }
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException calling HSAE getPairedDevices", e)
            emptyList()
        }
    }

    override fun getConnectedDevice(): CarPlayBluetoothDevice? {
        return try {
            val devices = BtPhoneProxy.getInstance().getConnectedDeviceInfo().orEmpty()
            if (devices.isNotEmpty()) {
                devices.forEach {
                    if (it.a2dpStatus == 2 || it.hfpStatus == 2) {
                        val mac = it.macAddress ?: ""
                        val name = it.devicesName ?: "iPhone"
                        val isConnected = it.a2dpStatus == 2 || it.hfpStatus == 2
                        val device = CarPlayBluetoothDevice(
                            name = name,
                            address = mac,
                            isConnected = isConnected
                        )
                        return device
                    }
                }
            }
            null
        } catch (e: RemoteException) {
            Log.e(TAG, "RemoteException calling HSAE getConnectedDevice", e)
            null
        }
    }

    override fun localAddress(context: Context): String? {
        val mac = runCatching {
            BtPhoneProxy.getInstance().getBtMacAddress()
        }.getOrNull()
        if (mac != null && isValidMac(mac)) {
            return mac
        }
        val settingAddr = runCatching {
            Settings.Secure.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        if (settingAddr != null && isValidMac(settingAddr)) {
            return settingAddr
        }
        val globalAddr = runCatching {
            Settings.Global.getString(context.contentResolver, "bluetooth_address")
        }.getOrNull()
        if (globalAddr != null && isValidMac(globalAddr)) {
            return globalAddr
        }
        return null
    }

    private fun isValidMac(mac: String): Boolean {
        return Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(mac) &&
                !mac.startsWith("02:00:00:00:00:", ignoreCase = false) &&
                mac != "00:00:00:00:00:00"
    }

    override fun openRfcommStream(deviceAddress: String, uuid: String): BlockingDuplexByteStream {
        return HsaeRfcommStream.connect(context, deviceAddress, uuid)
    }
}
