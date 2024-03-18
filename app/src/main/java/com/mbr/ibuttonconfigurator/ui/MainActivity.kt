package com.mbr.ibuttonconfigurator.ui

import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ibuttonconfigurator.R
import com.mbr.ibuttonconfigurator.onewire.OneWireDeviceFactory
import com.mbr.ibuttonconfigurator.onewire.device.OneWireDS1922
import com.mbr.ibuttonconfigurator.ui.onewiredevice.OneWireDS192xDeviceScreen
import com.mbr.ibuttonconfigurator.ui.onewiredevices.OneWireDevicesScreen
import com.mbr.ibuttonconfigurator.ui.usbdevices.USBDeviceScreen
import com.mbr.ibuttonconfigurator.usb.adapter.USBException
import kotlin.reflect.full.isSubclassOf


class AppUsbDevice(val device: UsbDevice, val usbService: UsbManager) {
    fun hasPermission(): Boolean {
        return usbService.hasPermission(device)
    }

    fun isPresent(): Boolean {
        return usbService.deviceList.values.contains(device)
    }

    override fun equals(other: Any?): Boolean {
        return other is AppUsbDevice &&
                this.device == other.device
    }

    override fun hashCode(): Int {
        return device.hashCode()
    }
}

class MainActivity : ComponentActivity() {
    private var usbDevices = MutableLiveData<List<AppUsbDevice>>()
    private lateinit var viewModel: MainActivityViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[MainActivityViewModel::class.java]

        setContent {
            ComposeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainApp()
                }
            }
        }
    }

    @Composable
    private fun MainApp() {
        val navController = rememberNavController()
        val usbService = getUsbManagerService()

        ListenForUsbDevices(usbService, navController)

        Column {
            Text(
                stringResource(R.string.app_headline),
                style = MaterialTheme.typography.headlineLarge
            )
            MainNavHost(navController = navController)
        }
    }

    @Composable
    fun getUsbManagerService(): UsbManager {
        val context = LocalContext.current
        return requireNotNull(context.getSystemService(Context.USB_SERVICE)) as UsbManager
    }

    private fun updateUsbDeviceFromService(usbService: UsbManager) {
        Log.d(
            "MainActivity", "update USB device list, set %d devices".format(
                usbService.deviceList.count()
            )
        )
        usbDevices.postValue(
            usbService.deviceList.values.toList().map { AppUsbDevice(it, usbService) })
    }

    @Composable
    private fun ListenForUsbDevices(
        usbService: UsbManager,
        navController: NavHostController
    ) {
        val filter = IntentFilter()
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)

        val usbReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                updateUsbDeviceFromService(usbService)

                if (viewModel.currentUsbDevice?.isPresent() == false) {
                    navController.popBackStack("usbdevices", false)
                    viewModel.currentUsbDevice = null
                }
            }
        }

        val context = LocalContext.current

        DisposableEffect(context)
        {
            context.registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)

            updateUsbDeviceFromService(usbService)

            onDispose {
                context.unregisterReceiver(usbReceiver)
            }
        }
    }

    @Composable
    private fun MainNavHost(
        navController: NavHostController
    ) {
        NavHost(navController = navController, startDestination = "usbdevices") {
            composable("usbdevices") {
                USBDeviceScreen(usbDevices = usbDevices, onSelectUsbDevice = {
                    viewModel.currentUsbDevice = it
                    navController.navigate("onewiredevices")
                })
            }

            composable("onewiredevices") {
                val context = LocalContext.current

                if (viewModel.currentUsbDevice == null) {
                    navController.popBackStack()
                    return@composable
                }
                OneWireDevicesScreen(
                    usbDevice = viewModel.currentUsbDevice!!,
                    onSelectOneWireDevice = { romId, password ->
                        viewModel.currentOnewireDevice = romId
                        viewModel.currentOnewirePassword = password
                        navController.navigate("onewiredevice")
                    },
                    onErrorCb = {
                        val usbDevice = (it as? USBException)?.usbDevice
                        if (usbDevice?.isPresent() == false)
                        // device has been just plugged out
                            return@OneWireDevicesScreen

                        showErrorDialog(it, context)
                        navController.popBackStack("usbdevices", false)
                    },
                    onDebugCb = {
                        val usbDevice = it.usbDevice
                        if (!usbDevice.isPresent())
                        // device has been just plugged out
                            return@OneWireDevicesScreen

                        showDebugDialog(it, context)
                    }
                )
            }

            composable("onewiredevice") {
                val context = LocalContext.current

                if (viewModel.currentUsbDevice == null) {
                    navController.popBackStack("usbdevices", false)
                    return@composable
                }
                if (viewModel.currentOnewireDevice == null) {
                    navController.popBackStack("onewiredevices", false)
                    return@composable
                }

                val oneWireDeviceFactory = OneWireDeviceFactory.singleton()
                val expectedClass =
                    remember { oneWireDeviceFactory.getSupportedClass(viewModel.currentOnewireDevice!!) }

                if (expectedClass?.isSubclassOf(OneWireDS1922::class) == true) {
                    OneWireDS192xDeviceScreen(
                        usbDevice = viewModel.currentUsbDevice!!,
                        romId = viewModel.currentOnewireDevice!!,
                        onErrorCb = {
                            Log.d(
                                "MainActivity", "Error in device page for DS1922: %s".format(
                                    it.toString()
                                )
                            )
                            val usbDevice = (it as? USBException)?.usbDevice
                            if (usbDevice?.isPresent() == false)
                            // device has been just plugged out
                                return@OneWireDS192xDeviceScreen

                            showErrorDialog(it, context)
                            navController.popBackStack("onewiredevices", false)
                        },
                        password = viewModel.currentOnewirePassword
                    )
                } else {
                    showErrorDialog(Exception("Device type not supported by UI"), context)
                    navController.popBackStack("onewiredevices", false)
                }
            }
        }
    }

    private fun showErrorDialog(e: Exception, context: Context, withStackTrace: Boolean = false) {
        Log.e("MainActivity", e.toString())

        val b = AlertDialog.Builder(
            context
        )
        val title = applicationContext.resources.getString(R.string.onewire_error_title)
        b.setTitle(title)
        if (withStackTrace)
            b.setMessage("%s\n\n%s".format(e.localizedMessage, e.stackTraceToString()))
        else {
            b.setMessage("%s".format(e.localizedMessage))
            b.setNeutralButton("Details") { _, _ ->
                showErrorDialog(e, context, true)
            }
        }
        b.show()
    }

    private fun showDebugDialog(e: Exception, context: Context, withStackTrace: Boolean = false) {
        Log.e("MainActivity", e.toString())

        val b = AlertDialog.Builder(
            context
        )
        val title = getString(R.string.debug_1_wire_adapter)
        b.setTitle(title)
        if (withStackTrace)
            b.setMessage("%s\n\n%s".format(e.localizedMessage, e.stackTraceToString()))
        else {
            b.setMessage("%s".format(e.localizedMessage))
            b.setNeutralButton("Details") { _, _ ->
                showErrorDialog(e, context, true)
            }
        }
        b.show()
    }
}

