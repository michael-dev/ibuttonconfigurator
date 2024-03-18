package com.mbr.ibuttonconfigurator.onewire

import com.mbr.ibuttonconfigurator.usb.adapter.OneWireAdapterBase
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireRomId

abstract class IOneWireDevice(
    adapter: OneWireAdapterBase,
    romId: OneWireRomId
) {
    abstract fun setAccessPassword(password: String)
}