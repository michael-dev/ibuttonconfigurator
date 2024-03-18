package com.mbr.ibuttonconfigurator.usb.adapter

import com.mbr.ibuttonconfigurator.ui.AppUsbDevice

abstract class OneWireAdapterBaseUsb(
    var device: AppUsbDevice,
) : OneWireAdapterBase() {
    fun isUsbDevice(device: AppUsbDevice) : Boolean {
        return device == this.device
    }
}