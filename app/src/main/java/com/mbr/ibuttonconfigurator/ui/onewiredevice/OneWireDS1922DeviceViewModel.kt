package com.mbr.ibuttonconfigurator.ui.onewiredevice

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.mbr.ibuttonconfigurator.onewire.device.OneWireDS1922
import com.mbr.ibuttonconfigurator.onewire.device.OneWireDS1922CurrentConfigurationAndDataLog
import com.mbr.ibuttonconfigurator.onewire.device.OneWireDS1922GeneralPurposeMemory
import com.mbr.ibuttonconfigurator.ui.AppUsbDevice
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireRomId

class OneWireDS1922DeviceViewModel: ViewModel() {

    val actionButtonsEnabled = MutableLiveData<Boolean>()
    var lastPassword: String = ""
    var oneWireDeviceInitThread: Thread? = null
    var oneWireDeviceLoadGpmemThread: Thread? = null
    val oneWireDevice = MutableLiveData<OneWireDS1922?>()
    val oneWireDeviceState = MutableLiveData<OneWireDS1922CurrentConfigurationAndDataLog?>()
    val oneWireDeviceGPMem = MutableLiveData<OneWireDS1922GeneralPurposeMemory?>()
    var lastRomId: OneWireRomId? = null
    var lastUsbDevice: AppUsbDevice? = null
    var loadGPMem = MutableLiveData<Boolean>()

    fun reset() {
        if (oneWireDeviceInitThread != null &&
            oneWireDeviceInitThread!!.isAlive) {
            oneWireDeviceInitThread!!.interrupt()
        }
        if (oneWireDeviceLoadGpmemThread != null &&
            oneWireDeviceLoadGpmemThread!!.isAlive) {
            oneWireDeviceLoadGpmemThread!!.interrupt()
        }

        lastUsbDevice = null
        lastRomId = null
        lastPassword = ""
        oneWireDevice.value = null
        oneWireDeviceState.value = null
        oneWireDeviceGPMem.value = null
        oneWireDeviceInitThread = null
        actionButtonsEnabled.value = true
        loadGPMem.value = false
    }
}