package com.mbr.ibuttonconfigurator.ui.onewiredevices

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ibuttonconfigurator.R
import com.mbr.ibuttonconfigurator.onewire.OneWireDeviceFactory
import com.mbr.ibuttonconfigurator.ui.AppUsbDevice
import com.mbr.ibuttonconfigurator.ui.PasswordInputField
import com.mbr.ibuttonconfigurator.usb.OneWireUsbAdapterFactory
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireAdapterBase
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireRomId
import com.mbr.ibuttonconfigurator.usb.adapter.USBException
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

private enum class OneWireDeviceState {
    UNSUPPORTED, SUPPORTED
}

@Composable
fun OneWireDevicesScreen(
    usbDevice: AppUsbDevice,
    viewModel: OneWireDevicesViewModel = viewModel(),
    lifecycleOwner: LifecycleOwner = LocalLifecycleOwner.current,
    onErrorCb: (e: Exception) -> Unit = {},
    onDebugCb: (e: USBException) -> Unit = {},
    onSelectOneWireDevice: (id: OneWireRomId, password: String) -> Unit = { _: OneWireRomId, _: String -> }
) {
    val context = LocalContext.current
    val oneWireAdapterFactory = OneWireUsbAdapterFactory.singleton()
    val oneWireDeviceFactory = OneWireDeviceFactory.singleton()

    if (viewModel.lastUsbDevice != usbDevice) {
        viewModel.reset()
        viewModel.lastUsbDevice = usbDevice
    }

    var ee: Exception? = null
    val adapter = remember {
        try {
            oneWireAdapterFactory.useDevice(usbDevice)
        } catch (e: Exception) {
            ee = e
            return@remember  null
        }
    } ?: return onErrorCb(ee!!)

    val mainHandler = Handler(LocalContext.current.mainLooper)

    Column {
        val selectedDevice by viewModel.selectedOneWireDevice.observeAsState(initial = OneWireRomId())

        val rescanInProgress by viewModel.rescanInProgress.observeAsState(false)

        Button(
            onClick = {
                Log.d("OneWireDevicesFragment", "Button pressed - start scanning")

                scanOneWireDevice(0, viewModel, adapter, context, onErrorCb) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !rescanInProgress
        )
        {
            Text(text = stringResource(R.string.btn_rescan_onewire))
        }

        UIOneWireDevicesColumn(
            devices = viewModel.oneWireDevices,
            deviceState = {
                if (!oneWireDeviceFactory.isSupported(it))
                    OneWireDeviceState.UNSUPPORTED
                else
                    OneWireDeviceState.SUPPORTED
            },
            isSelected = { device -> device == selectedDevice },
            onClickUsbDevice = { device, password ->
                selectOneWireDevice(
                    device,
                    password,
                    viewModel,
                    onSelectOneWireDevice
                )
            }
        )


    }

    DisposableEffect(context)
    {
        Log.d("OneWireDevicesFragment", "Screen started - start scanning")

        adapter.onDeviceDetected.observe(lifecycleOwner) {
            mainHandler.post {
                Log.d("OneWireDevicesFragment", "Device detected - start scanning")
                scanOneWireDevice(2000, viewModel, adapter, context, onErrorCb)
            }
        }
        adapter.onDeviceFailure.observe(lifecycleOwner) {
            mainHandler.post {
                onErrorCb(it)
            }
        }
        adapter.onDeviceDebug.observe(lifecycleOwner) {
            mainHandler.post {
                onDebugCb(it)
            }
        }

        if (!viewModel.alreadyScanned) {
            Log.d("OneWireDevicesFragment","initial scan")
            scanOneWireDevice(0, viewModel, adapter, context, onErrorCb)
            viewModel.alreadyScanned = true
        }

        onDispose {
            adapter.onDeviceDetected.removeObservers(lifecycleOwner)
            adapter.onDeviceFailure.removeObservers(lifecycleOwner)
            adapter.onDeviceDebug.removeObservers(lifecycleOwner)
        }
    }
}

private fun selectOneWireDevice(
    device: OneWireRomId,
    password: String,
    viewModel: OneWireDevicesViewModel,
    onSelectOneWireDevice: (id: OneWireRomId, password: String) -> Unit = { _: OneWireRomId, _: String -> }
) {
    val oneWireDeviceFactory = OneWireDeviceFactory.singleton()

    if (!oneWireDeviceFactory.isSupported(device)) {
        return
    }

    viewModel.setCurrentOneWireDevice(device)

    onSelectOneWireDevice(device, password)
}

@Composable
private fun UIOneWireDevicesColumn(
    devices: LiveData<List<OneWireRomId>>,
    deviceState: (OneWireRomId) -> OneWireDeviceState,
    isSelected: (OneWireRomId) -> Boolean,
    onClickUsbDevice: (device: OneWireRomId, password: String) -> Unit
) {
    Column {
        Text(text = stringResource(R.string.oneWireDevices_Headline), style = MaterialTheme.typography.headlineMedium)

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
            var password by remember { mutableStateOf("")}
            var passwordVisible by rememberSaveable { mutableStateOf(false) }

            LazyColumn {
                items(items = deviceAsState) { device ->
                    UIOneWireDeviceCard(
                        device = device,
                        onClickCb = { selectedDevice -> onClickUsbDevice(selectedDevice, password) },
                        deviceState = deviceState,
                        isSelected = isSelected
                    )
                }
            }

            PasswordInputField(
                label = stringResource(R.string.oneWireDevices_Password_Label),
                password = password,
                passwordVisible = passwordVisible,
                changePassword = { password = it },
                showPassword = { passwordVisible = it }
            )
        }
    }

}

@Composable
private fun UIOneWireDeviceCard(
    device: OneWireRomId,
    onClickCb: (OneWireRomId) -> Unit,
    deviceState: (OneWireRomId) -> OneWireDeviceState,
    isSelected: (OneWireRomId) -> Boolean
) {
    var m = Modifier
        .padding(all = 8.dp)

    if (deviceState(device) != OneWireDeviceState.UNSUPPORTED) {
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
            stringResource(R.string.one_wire_entry_label)

        val text = deviceNameLabelFormat.format(
            device.toString()
        )
        val colorCode = when (deviceState(device)) {
            OneWireDeviceState.UNSUPPORTED -> Color.Gray
            OneWireDeviceState.SUPPORTED -> Color.Black
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

private fun scanOneWireDevice(
    delay: Long,
    viewModel: OneWireDevicesViewModel,
    oneWireAdapter: OneWireAdapterBase,
    context: Context,
    onErrorCb: (Exception) -> Unit
) {
   if (Thread.currentThread() != context.mainLooper.thread)
       throw Exception("scanOneWireDevice called from outside of main thread")

    if (viewModel.rescanInProgress.value == true)
        return // already in progress
    viewModel.rescanInProgress.value = true

    val mainHandler = Handler(context.mainLooper)

    thread {
        if (delay > 0)
            Thread.sleep(delay)

        oneWireAdapter.lock.withLock {
            try {
                oneWireAdapter.oneWireResetDevice()
                val deviceList = oneWireAdapter.oneWireSearchAll()
                viewModel.updateOneWireDevices(deviceList)
            } catch (e: Exception) {
                mainHandler.post {
                    onErrorCb(e)
                }

            }
            viewModel.rescanInProgress.postValue(false)
        }
    }
}