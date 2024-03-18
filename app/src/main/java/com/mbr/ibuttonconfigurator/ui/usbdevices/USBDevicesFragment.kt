package com.mbr.ibuttonconfigurator.ui.usbdevices

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ibuttonconfigurator.R
import com.mbr.ibuttonconfigurator.ui.AppUsbDevice
import com.mbr.ibuttonconfigurator.usb.OneWireUsbAdapterFactory

private const val ACTION_USB_PERMISSION = "com.mbr.ibuttonconfigurator.usbpermission"

private enum class USBDeviceState {
    UNSUPPORTED, SUPPORTED_NO_PERMISSION, SUPPORTED_WITH_PERMISSION
}

@SuppressLint("MutableImplicitPendingIntent")
private fun requestUsbPermission(device: AppUsbDevice, context: Context) {

    val pi = PendingIntent.getBroadcast(
        /* context = */ context,
        /* requestCode = */ 0,
        /* intent = */ Intent(ACTION_USB_PERMISSION).putExtra("device", device.device.deviceName),
        /* flags = */ PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_CANCEL_CURRENT // needs to be mutable or ACTION_USB_PERMISSION will not show up
    )

    // receiver already configured - will refresh list of devices and resume
    device.usbService.requestPermission(device.device, pi)
}

@Preview(showBackground=true)
@Composable
fun USBDeviceScreen(
    usbDevices: LiveData<List<AppUsbDevice>> = MutableLiveData(ArrayList()),
    viewModel: USBDevicesViewModel = viewModel(),
    //lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onSelectUsbDevice: (deviceSelected: AppUsbDevice) -> Unit = {}
) {
    val context = LocalContext.current

    val filter = IntentFilter()
    filter.addAction(ACTION_USB_PERMISSION)

    val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_USB_PERMISSION &&
                intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) &&
                intent.hasExtra("device")
            ) {
                val deviceName = intent.getStringExtra("device").toString()
                val currentUsbDeviceList = usbDevices.value ?: return
                for (device in currentUsbDeviceList) {
                    if (device.device.deviceName == deviceName) {
                        selectUsbDevice(device, context, viewModel, onSelectUsbDevice)
                        break
                    }
                }
            }
        }
    }

    DisposableEffect(context)
    {
        context.registerReceiver(
            usbPermissionReceiver,
            filter,
            ComponentActivity.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            context.unregisterReceiver(usbPermissionReceiver)
        }
    }

    val oneWireAdapterFactory = OneWireUsbAdapterFactory.singleton()
    val selectedDevice by viewModel.selectedUsbDevice.observeAsState(-1)

    UIUsbDevicesColumn(
        devices = usbDevices,
        deviceState = {
            if (!oneWireAdapterFactory.isSupported(it.device))
                USBDeviceState.UNSUPPORTED
            else if (!it.hasPermission())
                USBDeviceState.SUPPORTED_NO_PERMISSION
            else
                USBDeviceState.SUPPORTED_WITH_PERMISSION
        },
        isSelected = { device -> selectedDevice == device.device.deviceId },
        onClickUsbDevice = { device ->
            selectUsbDevice(
                device,
                context,
                viewModel,
                onSelectUsbDevice
            )
        }
    )
}

private fun selectUsbDevice(
    device: AppUsbDevice,
    context: Context,
    viewModel: USBDevicesViewModel,
    onSelectUsbDevice: (id: AppUsbDevice) -> Unit = {}
) {
    val oneWireAdapterFactory = OneWireUsbAdapterFactory.singleton()

    if (!oneWireAdapterFactory.isSupported(device.device)) {
        return
    }

    if (!device.hasPermission()) {
        requestUsbPermission(device, context)
        return
    }

    viewModel.selectedUsbDevice.postValue(device.device.deviceId)

    onSelectUsbDevice(device)
}

@Composable
private fun UIUsbDevicesColumn(
    devices: LiveData<List<AppUsbDevice>>,
    deviceState: (AppUsbDevice) -> USBDeviceState,
    isSelected: (AppUsbDevice) -> Boolean,
    onClickUsbDevice: (device: AppUsbDevice) -> Unit
) {
    Column {
        Text(text = stringResource(R.string.usbDevices_Headline), style = MaterialTheme.typography.headlineSmall)

        val deviceAsState by devices.observeAsState(initial = emptyList())

        if (deviceAsState.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // A pre-defined composable that's capable of rendering a circular progress indicator. It
                // honors the Material Design specification.
                CircularProgressIndicator(modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally))
            }
        } else {
            LazyColumn {
                items(items = deviceAsState) { device ->
                    UIUsbDeviceCard(
                        device = device,
                        onClickCb = onClickUsbDevice,
                        deviceState = deviceState,
                        isSelected = isSelected
                    )
                }
            }
        }
    }

}

@Composable
private fun UIUsbDeviceCard(
    device: AppUsbDevice,
    onClickCb: (AppUsbDevice) -> Unit,
    deviceState: (AppUsbDevice) -> USBDeviceState,
    isSelected: (AppUsbDevice) -> Boolean
) {
    var m = Modifier
        .padding(all = 8.dp)
    if (deviceState(device) != USBDeviceState.UNSUPPORTED) {
        m = m
            .then(
                Modifier.clickable {
                    onClickCb(device)

                }
            )
    }

    Row(
        modifier = m
    ) {
        val deviceNameLabelFormat =
            stringResource(R.string.usb_entry_label)

        val text = deviceNameLabelFormat.format(
            device.device.vendorId,
            device.device.productId
            // S/N not readable and causes SecurityError if permission not already granted
        )
        val colorCode = when (deviceState(device)) {
            USBDeviceState.UNSUPPORTED -> Color.Gray
            USBDeviceState.SUPPORTED_NO_PERMISSION -> Color.Black
            USBDeviceState.SUPPORTED_WITH_PERMISSION -> Color.Green
        }

        val fontWeight = if (isSelected(device)) FontWeight.Bold else FontWeight.Normal

        Text(
            text = text,
            color = colorCode,
            fontWeight = fontWeight,
            modifier = Modifier.padding(all = 8.dp)
        )

    }
}