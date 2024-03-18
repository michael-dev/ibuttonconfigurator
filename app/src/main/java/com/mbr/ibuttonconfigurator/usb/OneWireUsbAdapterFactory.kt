package com.mbr.ibuttonconfigurator.usb

import android.hardware.usb.UsbDevice
import com.mbr.ibuttonconfigurator.ui.AppUsbDevice
import com.mbr.ibuttonconfigurator.usb.adapter.DS2490
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireAdapterBase
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireAdapterBaseUsb
import kotlin.reflect.KClass

class OneWireUsbAdapterFactory private constructor() {
    companion object {
        private val cache = HashMap<AppUsbDevice, OneWireAdapterBase> ()
        private val obj = OneWireUsbAdapterFactory()
        fun singleton(): OneWireUsbAdapterFactory {
            return obj
        }
    }

    class UsbRegisteredDeviceTypes(
        var pid: Int,
        var vid: Int,
        var cls: KClass<out OneWireAdapterBaseUsb>
    )

    private var supportedDevices = listOf(
        UsbRegisteredDeviceTypes(pid = DS2490.PID, vid = DS2490.VID, DS2490::class) // DS1490F use DS2490 chip
    )

    private fun getSupportedClass(d: UsbDevice): KClass<out OneWireAdapterBaseUsb>? {
        for (supportedDevice in supportedDevices) {
            if (supportedDevice.vid != d.vendorId ||
                supportedDevice.pid != d.productId
            )
                continue
            return supportedDevice.cls
        }

        return null
    }

    fun isSupported(d: UsbDevice): Boolean {
        return getSupportedClass(d) != null
    }

    fun useDevice(d: AppUsbDevice): OneWireAdapterBase {
        if (cache.containsKey(d))
            return cache[d]!!

        val cls: KClass<out OneWireAdapterBaseUsb> = getSupportedClass(d.device) ?: throw Exception("device not supported")

        val obj = cls.constructors.first().call(d)

        cache[d] = obj

        return obj
    }
}