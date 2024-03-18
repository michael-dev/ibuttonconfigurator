package com.mbr.ibuttonconfigurator.ui.onewiredevices


import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.mbr.ibuttonconfigurator.ui.AppUsbDevice
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireRomId


class OneWireDevicesViewModel : ViewModel() {

    var lastUsbDevice: AppUsbDevice? = null

    val rescanInProgress = MutableLiveData(false)

    var alreadyScanned = false

    // 1-Wire
    private val _oneWireDevices = MutableLiveData<List<OneWireRomId>>().apply {
        value = ArrayList()
    }

    val oneWireDevices: LiveData<List<OneWireRomId>> = _oneWireDevices

    fun updateOneWireDevices(newList: List<OneWireRomId>) {
        _oneWireDevices.postValue(ArrayList(newList))
    }

    private val _selectedOneWireDevice = MutableLiveData<OneWireRomId>().apply {
        value = null
    }

    val selectedOneWireDevice: LiveData<OneWireRomId> = _selectedOneWireDevice

    fun setCurrentOneWireDevice(device: OneWireRomId?) {
        _selectedOneWireDevice.value = device
    }

    fun reset() {
        lastUsbDevice = null
        rescanInProgress.value = false
        alreadyScanned = false
        _oneWireDevices.value = ArrayList()
        _selectedOneWireDevice.value = null
    }


}