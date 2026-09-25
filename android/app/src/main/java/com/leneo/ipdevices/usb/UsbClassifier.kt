package com.leneo.ipdevices.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import com.hoho.android.usbserial.driver.UsbSerialProber

object UsbClassifier {
    fun isUvcCamera(device: UsbDevice): Boolean {
        if (device.deviceClass == UsbConstants.USB_CLASS_VIDEO) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VIDEO) {
                return true
            }
        }
        return false
    }

    fun serialDriver(device: UsbDevice) =
        if (isUvcCamera(device)) null else UsbSerialProber.getDefaultProber().probeDevice(device)

    fun displayName(device: UsbDevice): String {
        val product = device.productName?.trim().orEmpty()
        if (product.isNotEmpty()) return product
        return "USB ${device.vendorId.toString(16)}:${device.productId.toString(16)} (#${device.deviceId})"
    }

    fun serialNumber(device: UsbDevice): String =
        runCatching { device.serialNumber?.trim().orEmpty() }.getOrNull().orEmpty()

    fun serialKey(device: UsbDevice): String {
        val serial = serialNumber(device)
        return if (serial.isNotEmpty()) serial else "dev-${device.deviceId}"
    }
}
