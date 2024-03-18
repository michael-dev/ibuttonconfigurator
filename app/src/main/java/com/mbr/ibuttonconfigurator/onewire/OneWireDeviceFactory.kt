package com.mbr.ibuttonconfigurator.onewire

import com.mbr.ibuttonconfigurator.onewire.device.OneWireDS1922
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireAdapterBase
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireRomId
import kotlin.reflect.KClass

class OneWireDeviceFactory {

    companion object {
        private val cache = HashMap<OneWireRomId, IOneWireDevice>()
        private val obj = OneWireDeviceFactory()
        fun singleton(): OneWireDeviceFactory {
            return obj
        }
    }

    class OneWireRegisteredDeviceTypes(
        var familyCode: Byte,
        var cls: KClass<out IOneWireDevice>
    )

    private var supportedDevices = listOf(
        //OneWireRegisteredDeviceTypes(familyCode = OneWireDS1922LT.familyCode1921G, OneWireDS1922LT::class),
        OneWireRegisteredDeviceTypes(
            familyCode = OneWireDS1922.familyCode1922or1923,
            OneWireDS1922::class
        )
    )

    fun getSupportedClass(d: OneWireRomId): KClass<out IOneWireDevice>? {
        for (supportedDevice in supportedDevices) {
            if (!d.isFamily(supportedDevice.familyCode))
                continue
            return supportedDevice.cls
        }

        return null
    }

    fun isSupported(d: OneWireRomId): Boolean {
        return getSupportedClass(d) != null
    }

    fun useDevice(password: String, d: OneWireRomId, adapter: OneWireAdapterBase): IOneWireDevice {
        if (cache.containsKey(d))
            return cache[d]!!

        val cls: KClass<out IOneWireDevice> =
            getSupportedClass(d) ?: throw Exception("device not supported")

        val obj = cls.constructors.first().call(adapter, d, password)
        cache[d] = obj
        return obj
    }
}