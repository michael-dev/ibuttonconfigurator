package com.mbr.ibuttonconfigurator.ui

import androidx.lifecycle.ViewModel
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireRomId

class MainActivityViewModel: ViewModel() {
    var currentOnewirePassword: String = ""
    var currentOnewireDevice: OneWireRomId? = null
    var currentUsbDevice: AppUsbDevice? = null

}