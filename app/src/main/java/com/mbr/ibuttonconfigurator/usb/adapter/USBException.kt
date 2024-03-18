package com.mbr.ibuttonconfigurator.usb.adapter

import com.mbr.ibuttonconfigurator.ui.AppUsbDevice

class USBException(message: String, val usbDevice: AppUsbDevice): Exception(message) {

}
