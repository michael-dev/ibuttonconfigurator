package com.mbr.ibuttonconfigurator.ui.onewiredevice

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerState
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.text.isDigitsOnly
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ibuttonconfigurator.R
import com.mbr.ibuttonconfigurator.onewire.OneWireDeviceFactory
import com.mbr.ibuttonconfigurator.onewire.device.OneWireDS1922
import com.mbr.ibuttonconfigurator.onewire.device.OneWireDS1922CurrentConfigurationAndDataLog
import com.mbr.ibuttonconfigurator.onewire.device.OneWireDS1922GeneralPurposeMemory
import com.mbr.ibuttonconfigurator.ui.AppUsbDevice
import com.mbr.ibuttonconfigurator.ui.PasswordInputField
import com.mbr.ibuttonconfigurator.usb.OneWireUsbAdapterFactory
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireRomId
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.LinkedList
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun OneWireDS192xDeviceScreen(
    usbDevice: AppUsbDevice,
    romId: OneWireRomId,
    viewModel: OneWireDS1922DeviceViewModel = viewModel(),
    onErrorCb: (e: Exception) -> Unit = {},
    password: String
) {
    val context = LocalContext.current

    OneWireDS192xDeviceScreenInit(context, usbDevice, romId, password, viewModel, onErrorCb)

    val mainHandler = Handler(context.mainLooper)
    val oneWireDevice by viewModel.oneWireDevice.observeAsState(initial = null)
    val oneWireDeviceState by viewModel.oneWireDeviceState.observeAsState(initial = null)
    val actionButtonsEnabled by viewModel.actionButtonsEnabled.observeAsState(true)

    if (oneWireDevice == null || oneWireDeviceState == null || !actionButtonsEnabled) {
        return PendingUI()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            stringResource(R.string.oneWireDevice_Headline),
            style = MaterialTheme.typography.headlineMedium
        )
        Text(stringResource(R.string.oneWireDevice_Type).format(oneWireDeviceState!!.deviceType))
        Text(stringResource(R.string.oneWireDevice_InfoCalibration))

        CurrentStateRTC(oneWireDeviceState!!)
        CurrentStateDevice(oneWireDeviceState!!)
        CurrentStateMission(oneWireDeviceState!!)
        CurrentStateAlarm(oneWireDeviceState!!)
        CurrentStateDataLog(oneWireDeviceState!!)
        GeneralPurposeMemory(viewModel, onErrorCb, context, oneWireDevice!!)

        DeviceActions(viewModel, oneWireDevice!!, oneWireDeviceState!!, mainHandler, onErrorCb)
    }
}

@Composable
private fun PendingUI() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // A pre-defined composable that's capable of rendering a circular progress indicator. It
        // honors the Material Design specification.
        CircularProgressIndicator(modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally))
    }

}

@Composable
private fun OneWireDS192xDeviceScreenInit(
    context: Context,
    usbDevice: AppUsbDevice,
    romId: OneWireRomId,
    password: String,
    viewModel: OneWireDS1922DeviceViewModel,
    onErrorCb: (e: Exception) -> Unit
) {
    DisposableEffect(context)
    {
        val oneWireAdapterFactory = OneWireUsbAdapterFactory.singleton()
        val oneWireDeviceFactory = OneWireDeviceFactory.singleton()
        val mainHandler = Handler(context.mainLooper)

        if ((viewModel.lastUsbDevice != usbDevice) ||
            (viewModel.lastRomId != romId) ||
            (viewModel.lastPassword != password)
        ) {
            viewModel.reset()
            viewModel.lastUsbDevice = usbDevice
            viewModel.lastRomId = romId
            viewModel.lastPassword = password

            viewModel.oneWireDeviceInitThread = thread {
                val adapter = oneWireAdapterFactory.useDevice(usbDevice)
                adapter.lock.withLock {
                    try {
                        val d = oneWireDeviceFactory.useDevice(
                            password,
                            romId,
                            adapter
                        ) as OneWireDS1922
                        d.setAccessPassword(password) // enable switching between read-only and full access pw
                        val state = OneWireDS1922CurrentConfigurationAndDataLog(d)

                        viewModel.oneWireDevice.postValue(d)
                        viewModel.oneWireDeviceState.postValue(state)
                    } catch (e: Exception) {
                        mainHandler.post {
                            onErrorCb(e)
                        }
                    }
                }
            }
        }

        onDispose {

        }
    }
}

@Composable
private fun EnablePasswordProtectionSection(
    oneWireDevice: OneWireDS1922,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit
) {

    val confirmEnablePassword =
        rememberSaveable(inputs = arrayOf(oneWireDevice)) { mutableStateOf(false) }

    var fullPassword1 by rememberSaveable(inputs = arrayOf(oneWireDevice)) { mutableStateOf("") }
    var fullPassword1Visible by rememberSaveable(inputs = arrayOf(oneWireDevice)) {
        mutableStateOf(
            false
        )
    }
    var fullPassword2 by rememberSaveable(inputs = arrayOf(oneWireDevice)) { mutableStateOf("") }
    var fullPassword2Visible by rememberSaveable(inputs = arrayOf(oneWireDevice)) {
        mutableStateOf(
            false
        )
    }

    var readPassword1 by rememberSaveable(inputs = arrayOf(oneWireDevice)) { mutableStateOf("") }
    var readPassword1Visible by rememberSaveable(inputs = arrayOf(oneWireDevice)) {
        mutableStateOf(
            false
        )
    }
    var readPassword2 by rememberSaveable(inputs = arrayOf(oneWireDevice)) { mutableStateOf("") }
    var readPassword2Visible by rememberSaveable(inputs = arrayOf(oneWireDevice)) {
        mutableStateOf(
            false
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                stringResource(R.string.oneWireDevice_EnablePwProtection_Headline),
                style = MaterialTheme.typography.headlineSmall
            )

            PasswordInputField(
                label = stringResource(R.string.oneWireDevice_EnablePwProtection_FP),
                password = fullPassword1,
                passwordVisible = fullPassword1Visible,
                changePassword = { fullPassword1 = it },
                showPassword = { fullPassword1Visible = it })
            PasswordInputField(
                label = stringResource(R.string.oneWireDevice_EnablePwProtection_FPrepeat),
                password = fullPassword2,
                passwordVisible = fullPassword2Visible,
                changePassword = { fullPassword2 = it },
                showPassword = { fullPassword2Visible = it })

            PasswordInputField(
                label = stringResource(R.string.oneWireDevice_EnablePwProtection_RP),
                password = readPassword1,
                passwordVisible = readPassword1Visible,
                changePassword = { readPassword1 = it },
                showPassword = { readPassword1Visible = it })
            PasswordInputField(
                label = stringResource(R.string.oneWireDevice_EnablePwProtection_RPrepeat),
                password = readPassword2,
                passwordVisible = readPassword2Visible,
                changePassword = { readPassword2 = it },
                showPassword = { readPassword2Visible = it })

            val ok = (fullPassword1 == fullPassword2 && readPassword1 == readPassword2)

            // DO IT
            val actionButtonsEnabled by viewModel.actionButtonsEnabled.observeAsState(true)

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = actionButtonsEnabled && ok,
                onClick = { confirmEnablePassword.value = true }
            ) {
                Text(stringResource(R.string.oneWireDevice_EnablePwProtection_Button))
            }
        }
    }

    when {
        confirmEnablePassword.value -> {
            ConfirmDialog(
                onDismissRequest = { confirmEnablePassword.value = false },
                onConfirmation = {
                    confirmEnablePassword.value = false

                    enablePasswordProtection(
                        oneWireDevice = oneWireDevice,
                        fullPassword1 = fullPassword1,
                        readPassword1 = readPassword1,
                        viewModel = viewModel,
                        mainHandler = mainHandler,
                        onErrorCb = onErrorCb
                    )
                },
                dialogTitle = stringResource(R.string.oneWireDevice_EnablePwProtection_Confirm_Title),
                dialogText = stringResource(R.string.oneWireDevice_EnablePwProtection_Confirm_Text),
            )
        }
    }

}

private fun runActionOnOneWireDeviceAndReload(
    oneWireDevice: OneWireDS1922,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit,
    action: () -> Unit
) {
    viewModel.actionButtonsEnabled.postValue(false)
    thread {
        oneWireDevice.adapter.lock.withLock {
            try {
                action()

                oneWireDevice.reloadConfigurationDataAndDataLog()
                val state = OneWireDS1922CurrentConfigurationAndDataLog(oneWireDevice)
                viewModel.oneWireDeviceState.postValue(state)
                if (viewModel.loadGPMem.value == true) {
                    val gpMem = OneWireDS1922GeneralPurposeMemory(oneWireDevice)
                    viewModel.oneWireDeviceGPMem.postValue(gpMem)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onErrorCb(e)
                }
            } finally {
                viewModel.actionButtonsEnabled.postValue(true)
            }
        }
    }
}

@Composable
private fun DeviceActions(
    viewModel: OneWireDS1922DeviceViewModel,
    oneWireDevice: OneWireDS1922,
    oneWireDeviceState: OneWireDS1922CurrentConfigurationAndDataLog,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit = {}
) {
    ReloadDataButton(oneWireDevice, viewModel, mainHandler, onErrorCb)

    if (oneWireDeviceState.missionInProgress || oneWireDeviceState.rtcState.isOscillating) {
        StopMissionSection(
            oneWireDevice = oneWireDevice,
            viewModel = viewModel,
            mainHandler = mainHandler,
            onErrorCb = onErrorCb
        )
    }

    if (!oneWireDeviceState.missionInProgress) {
        NewMissionSection(
            oneWireDeviceState = oneWireDeviceState,
            viewModel = viewModel,
            mainHandler = mainHandler,
            onErrorCb = onErrorCb
        )
    }

    if (oneWireDeviceState.hasPasswordProtection) {
        DisablePasswordProtectionButton(oneWireDevice, viewModel, mainHandler, onErrorCb)
    } else {
        EnablePasswordProtectionSection(oneWireDevice, viewModel, mainHandler, onErrorCb)
    }

}

@Composable
private fun DisablePasswordProtectionButton(
    oneWireDevice: OneWireDS1922,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit
) {
    val confirmDisablePasswordProtection =
        rememberSaveable(inputs = arrayOf(oneWireDevice)) { mutableStateOf(false) }

    when {
        confirmDisablePasswordProtection.value -> {
            ConfirmDialog(
                onDismissRequest = { confirmDisablePasswordProtection.value = false },
                onConfirmation = {
                    confirmDisablePasswordProtection.value = false
                    disablePasswordProtection(oneWireDevice, viewModel, mainHandler, onErrorCb)
                },
                dialogTitle = stringResource(R.string.oneWireDevice_DisablePwProtection_Confirm_Title),
                dialogText = stringResource(R.string.oneWireDevice_DisablePwProtection_Confirm_Text),
            )
        }
    }

    val actionButtonsEnabled by viewModel.actionButtonsEnabled.observeAsState(true)

    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = actionButtonsEnabled,
        onClick = { confirmDisablePasswordProtection.value = true }
    ) {
        Text(stringResource(R.string.oneWireDevice_DisablePwProtection_Button))
    }
}

@Composable
private fun ReloadDataButton(
    oneWireDevice: OneWireDS1922,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit
) {
    val actionButtonsEnabled by viewModel.actionButtonsEnabled.observeAsState(true)

    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = actionButtonsEnabled,
        onClick = {
            reloadData(oneWireDevice, viewModel, mainHandler, onErrorCb)
        }
    ) {
        Text(stringResource(R.string.oneWireDevice_Reload_Button))
    }
}

@Composable
private fun StopMissionSection(
    oneWireDevice: OneWireDS1922,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit
) {
    val confirmStopMission =
        rememberSaveable(inputs = arrayOf(oneWireDevice)) { mutableStateOf(false) }

    when {
        confirmStopMission.value -> {
            ConfirmDialog(
                onDismissRequest = { confirmStopMission.value = false },
                onConfirmation = {
                    confirmStopMission.value = false
                    stopMissionAndClock(oneWireDevice, viewModel, mainHandler, onErrorCb)
                },
                dialogTitle = stringResource(R.string.oneWireDevice_StopMissionAndClock_Confirm_Title),
                dialogText = stringResource(R.string.oneWireDevice_StopMissionAndClock_Confirm_Text),
            )
        }
    }

    val actionButtonsEnabled by viewModel.actionButtonsEnabled.observeAsState(true)

    Button(
        modifier = Modifier.fillMaxWidth(),
        enabled = actionButtonsEnabled,
        onClick = { confirmStopMission.value = true }
    ) {
        Text(stringResource(R.string.oneWireDevice_StopMissionAndClock_Button))
    }
}

@Composable
private fun CurrentStateDataLog(oneWireDeviceState: OneWireDS1922CurrentConfigurationAndDataLog) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                stringResource(R.string.oneWireDevice_DataLog_Headline),
                style = MaterialTheme.typography.headlineSmall
            )
            if (!oneWireDeviceState.missionInProgress) {
                Text(
                    stringResource(R.string.oneWireDevice_DataLog_Warning_nonMiP)
                )
            }

            var lastDate = ""
            val txtList = LinkedList<String>()
            txtList.add("id\tdate\ttime\ttempC\ttempCalibratedC")

            for (entry in oneWireDeviceState.missionLoggedMeasurements.iterator()) { // Lazy Column fails as parent column is already scrollable
                val i = entry.key
                val ts = entry.value.first
                val m = entry.value.second

                val strDate = ts.format(DateTimeFormatter.ISO_LOCAL_DATE)
                val strTime = ts.format(DateTimeFormatter.ISO_LOCAL_TIME)

                if (lastDate != strDate) {
                    Text(stringResource(R.string.oneWireDevice_DataLog_NewDate).format(strDate))
                    lastDate = strDate
                }

                Text(
                    if (i < 0)
                        stringResource(R.string.oneWireDevice_DataLog_AlarmEntry).format(
                            strTime,
                            m.temp,
                            m.tempCalibrated
                        )
                    else
                        stringResource(R.string.oneWireDevice_DataLog_Entry).format(
                            i,
                            strTime,
                            m.temp,
                            m.tempCalibrated
                        )
                )

                txtList.add(
                    "%d\t%s\t%s\t%.3f\t%.3f".format(
                        i, strDate, strTime, m.temp, m.tempCalibrated
                    )
                )
            }

            val context = LocalContext.current
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val sendIntent: Intent = Intent().apply {
                        action = Intent.ACTION_SEND
                        putExtra(Intent.EXTRA_TEXT, txtList.joinToString(separator = "\n"))
                        type = "text/plain"
                    }

                    val shareIntent = Intent.createChooser(sendIntent, null)
                    context.startActivity(shareIntent)

                }
            ) {
                Text(stringResource(R.string.share_measurements))
            }

        }
    }
}

@Composable
private fun CurrentStateAlarm(oneWireDeviceState: OneWireDS1922CurrentConfigurationAndDataLog) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                stringResource(R.string.oneWireDevice_Alarm_Headline),
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                stringResource(R.string.oneWireDevice_Alarm_WaitForAlarmState).format(
                    stringResource(if (oneWireDeviceState.missionStartOnTemperatureAlarm) R.string.state_enabled else R.string.state_disabled),
                    stringResource(if (oneWireDeviceState.missionWaitingForTemperatureAlarm) R.string.state_waiting else R.string.state_not_waiting)
                )
            )
            Text(
                stringResource(R.string.oneWireDevice_Alarm_LowAlarmState).format(
                    stringResource(if (oneWireDeviceState.missionTempAlarmLowEnabled) R.string.state_enabled else R.string.state_disabled),
                    stringResource(if (oneWireDeviceState.missionTempAlarmLowSeen) R.string.state_seen else R.string.state_not_seen),
                    oneWireDeviceState.missionTempAlarmLow.temp,
                    oneWireDeviceState.missionTempAlarmLow.tempCalibrated
                )
            )
            Text(
                stringResource(R.string.oneWireDevice_Alarm_HighAlarmState).format(
                    stringResource(if (oneWireDeviceState.missionTempAlarmHighEnabled) R.string.state_enabled else R.string.state_disabled),
                    stringResource(if (oneWireDeviceState.missionTempAlarmHighSeen) R.string.state_seen else R.string.state_not_seen),
                    oneWireDeviceState.missionTempAlarmHigh.temp,
                    oneWireDeviceState.missionTempAlarmHigh.tempCalibrated
                )
            )
        }
    }
}

@Composable
private fun CurrentStateMission(oneWireDeviceState: OneWireDS1922CurrentConfigurationAndDataLog) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)

    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                text = stringResource(
                    if (oneWireDeviceState.missionInProgress)
                        R.string.oneWireDevice_Mission_Headline_running
                    else if (!oneWireDeviceState.missionMemoryCleared)
                        R.string.oneWireDevice_Mission_Headline_stopped
                    else
                        R.string.oneWireDevice_Mission_Headline_none
                ),
                style = MaterialTheme.typography.headlineSmall
            )

            Text(
                stringResource(R.string.oneWireDevice_Mission_LastStart).format(
                    oneWireDeviceState.missionStartTimestamp?.datetime?.format(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    )
                        ?: stringResource(R.string.date_invalid),
                    stringResource(
                        when (oneWireDeviceState.missionStartTimestamp?.is24h) {
                            true -> R.string.date_24h_mode
                            false -> R.string.date_am_pm_mode
                            null -> R.string.date_none_mode
                        }
                    ),
                    when (oneWireDeviceState.missionStartTimestamp?.cent) {
                        true -> stringResource(R.string.date_cent_rollover)
                        false -> ""
                        null -> ""
                    }
                )
            )
            Text(
                stringResource(R.string.oneWireDevice_Mission_StartDelay).format(
                    oneWireDeviceState.missionStartDelayCounter,
                    if (oneWireDeviceState.missionStartDelayCounter > 0)
                        LocalDateTime
                            .now()
                            .plusMinutes(oneWireDeviceState.missionStartDelayCounter)
                            .truncatedTo(ChronoUnit.MINUTES)
                            .format(
                                DateTimeFormatter.ISO_LOCAL_DATE_TIME
                            )
                    else stringResource(
                        if (oneWireDeviceState.missionInProgress)
                            R.string.oneWireDevice_Mission_StartDelay_MissionInProgress
                        else
                            R.string.oneWireDevice_Mission_StartDelay_Now
                    )
                )
            )
            Spacer(modifier = Modifier.size(30.dp))

            Text(
                stringResource(R.string.oneWireDevice_Mission_SampleCounter).format(
                    oneWireDeviceState.missionSamplesCounter
                )
            )
            Text(
                stringResource(R.string.oneWireDevice_Mission_SampleRate).format(
                    oneWireDeviceState.missionSampleRate,
                    stringResource(if (oneWireDeviceState.rtcState.isHighSpeed) R.string.oneWireDevice_Mission_SampleRate_sec else R.string.oneWireDevice_Mission_SampleRate_min)
                )
            )

            Spacer(modifier = Modifier.size(30.dp))

            Text(
                stringResource(R.string.oneWireDevice_Mission_Logging).format(
                    stringResource(if (oneWireDeviceState.missionEnableTemperatureLogging) R.string.state_enabled else R.string.state_disabled)
                )
            )
            Text(
                stringResource(R.string.oneWireDevice_Mission_Logging_Rollover).format(
                    stringResource(if (oneWireDeviceState.missionEnableTemperatureLoggingRollover) R.string.state_enabled else R.string.state_disabled)
                )
            )
            Text(
                stringResource(R.string.oneWireDevice_Mission_Logging_HighResolution).format(
                    stringResource(if (oneWireDeviceState.missionTemperatureLoggingHighResolution) R.string.state_enabled else R.string.state_disabled)
                )
            )
        }
    }

}

@Composable
private fun CurrentStateDevice(oneWireDeviceState: OneWireDS1922CurrentConfigurationAndDataLog) {

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)

    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                stringResource(R.string.oneWireDevice_Device_Headline),
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                stringResource(R.string.oneWireDevice_Device_SampleCounter).format(
                    oneWireDeviceState.deviceSamplesCounter
                )
            )
            Text(
                stringResource(R.string.oneWireDevice_Device_PasswordProtection).format(
                    stringResource(if (oneWireDeviceState.hasPasswordProtection) R.string.state_enabled else R.string.state_disabled)
                )
            )
            Text(
                stringResource(R.string.oneWireDevice_Device_LastTemperature).format(
                    oneWireDeviceState.latestTemperature.temp,
                    oneWireDeviceState.latestTemperature.tempCalibrated
                )
            )
            Text(
                stringResource(R.string.oneWireDevice_Device_BOR).format(
                    stringResource(if (oneWireDeviceState.borAlarm) R.string.oneWireDevice_Device_BOR_failure else R.string.oneWireDevice_Device_BOR_ok)
                )
            )
        }
    }
}

@Composable
private fun CurrentStateRTC(oneWireDeviceState: OneWireDS1922CurrentConfigurationAndDataLog) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)

    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                stringResource(R.string.oneWireDevice_RTC_Headline).format(
                    stringResource(if (oneWireDeviceState.rtcState.isOscillating) R.string.state_rtc_running else R.string.state_rtc_stopped)
                ), style = MaterialTheme.typography.headlineSmall
            )
            Text(
                stringResource(R.string.oneWireDevice_RTC_time).format(
                    oneWireDeviceState.rtcState.timestamp?.datetime?.format(
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME
                    )
                        ?: stringResource(R.string.date_invalid),
                    stringResource(
                        when (oneWireDeviceState.rtcState.timestamp?.is24h) {
                            true -> R.string.date_24h_mode
                            false -> R.string.date_am_pm_mode
                            null -> R.string.date_none_mode
                        }
                    ),
                    when (oneWireDeviceState.rtcState.timestamp?.cent) {
                        true -> stringResource(R.string.date_cent_rollover)
                        false -> ""
                        null -> ""
                    }
                )
            )

            Text(
                stringResource(R.string.oneWireDevice_RTC_HighSpeed).format(
                    stringResource(if (oneWireDeviceState.rtcState.isHighSpeed) R.string.state_enabled else R.string.state_disabled)
                )
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMissionSection(
    oneWireDeviceState: OneWireDS1922CurrentConfigurationAndDataLog,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit
) {
    val confirmStartMission =
        rememberSaveable(inputs = arrayOf(viewModel.oneWireDevice.value)) { mutableStateOf(false) }

    var missionStartDelayEnabled by rememberSaveable(inputs = arrayOf(oneWireDeviceState)) {
        mutableStateOf(
            oneWireDeviceState.missionStartDelayCounter > 0
        )
    }
    val missionStartDelayDate =
        rememberDatePickerState(
            initialSelectedDateMillis = LocalDate.now()
                .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
    val missionStartDelayTime = rememberTimePickerState(
        initialHour = LocalTime.now().hour,
        initialMinute = LocalTime.now().minute,
        is24Hour = true
    )

    var missionWaitForTemperatureLow by rememberSaveable(inputs = arrayOf(oneWireDeviceState)) {
        mutableStateOf(
            oneWireDeviceState.missionTempAlarmLowEnabled
        )
    }
    var tempLowThreshold by
    rememberSaveable(inputs = arrayOf(oneWireDeviceState)) {
        mutableDoubleStateOf(
            oneWireDeviceState.missionTempAlarmLow.temp
        )
    }

    var missionWaitForTemperatureHigh by rememberSaveable(inputs = arrayOf(oneWireDeviceState)) {
        mutableStateOf(
            oneWireDeviceState.missionTempAlarmHighEnabled
        )
    }
    var tempHighThreshold by
    rememberSaveable(inputs = arrayOf(oneWireDeviceState)) {
        mutableDoubleStateOf(
            oneWireDeviceState.missionTempAlarmHigh.temp
        )
    }
    var missionSampleRateHighSpeed by rememberSaveable(inputs = arrayOf(oneWireDeviceState)) {
        mutableStateOf(
            oneWireDeviceState.rtcState.isHighSpeed
        )
    }
    var missionSampleRate by
    rememberSaveable(inputs = arrayOf(oneWireDeviceState)) {
        mutableIntStateOf(
            oneWireDeviceState.missionSampleRate
        )
    }
    var missionDataLogHighResolution by
    rememberSaveable(inputs = arrayOf(oneWireDeviceState)) {
        mutableStateOf(
            oneWireDeviceState.missionTemperatureLoggingHighResolution
        )
    }
    var missionDataLogRollover by rememberSaveable(inputs = arrayOf(oneWireDeviceState)) {
        mutableStateOf(
            oneWireDeviceState.missionEnableTemperatureLoggingRollover
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp)
        ) {
            Text(
                stringResource(R.string.oneWireDevice_NewMission_Headline),
                style = MaterialTheme.typography.headlineSmall
            )

            // 1. Start at Time or instant -> start delay counter
            NewMissionStart(
                missionStartDelayEnabled,
                missionStartDelayDate,
                missionStartDelayTime
            ) { missionStartDelayEnabled = it }

            // 2. wait for temperature alarm how/high
            NewMissionTempAlarm(
                isHigh = false,
                missionWaitForTemperatureLow,
                tempLowThreshold,
                { missionWaitForTemperatureLow = it },
            ) { tempLowThreshold = it }

            NewMissionTempAlarm(
                isHigh = true,
                missionWaitForTemperatureHigh,
                tempHighThreshold,
                { missionWaitForTemperatureHigh = it },
            ) { tempHighThreshold = it }

            // 3. mission data log
            NewMissionSampleRate(
                missionSampleRate = missionSampleRate,
                missionSampleRateHighSpeed = missionSampleRateHighSpeed,
                missionSampleRateChange = { missionSampleRate = it },
                missionSampleRateHighSpeedChange = { missionSampleRateHighSpeed = it }
            )
            NewMissionSampleResolution(missionDataLogHighResolution) {
                missionDataLogHighResolution = it
            }
            NewMissionDataLogRollover(missionDataLogRollover) { missionDataLogRollover = it }

            // DO IT
            val actionButtonsEnabled by viewModel.actionButtonsEnabled.observeAsState(true)

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = actionButtonsEnabled,
                onClick = { confirmStartMission.value = true }
            ) {
                Text(stringResource(R.string.oneWireDevice_NewMission_Button))
            }
        }
    }

    when {
        confirmStartMission.value -> {
            ConfirmDialog(
                onDismissRequest = { confirmStartMission.value = false },
                onConfirmation = {
                    confirmStartMission.value = false
                    startMissionAndClock(
                        viewModel.oneWireDevice.value!!,
                        viewModel,
                        mainHandler,
                        onErrorCb,
                        missionStartDelayEnabled,
                        convertDateTimePickerToLocalDateTime(
                            missionStartDelayDate = missionStartDelayDate,
                            missionStartDelayTime = missionStartDelayTime
                        ),
                        missionWaitForTemperatureLow,
                        tempLowThreshold,
                        missionWaitForTemperatureHigh,
                        tempHighThreshold,
                        missionSampleRateHighSpeed,
                        missionSampleRate,
                        missionDataLogHighResolution,
                        missionDataLogRollover
                    )
                },
                dialogTitle = stringResource(R.string.oneWireDevice_NewMission_Confirm_Title),
                dialogText = stringResource(R.string.oneWireDevice_NewMission_Confirm_Text),
            )
        }
    }
}

@Composable
private fun NewMissionSampleResolution(
    missionDataLogHighResolution: Boolean,
    missionDataLogHighResolutionChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.clickable {
            missionDataLogHighResolutionChange(!missionDataLogHighResolution)
        }
    ) {
        Switch(
            checked = missionDataLogHighResolution,
            onCheckedChange = {
                missionDataLogHighResolutionChange(it)
            }
        )

        Text(stringResource(R.string.oneWireDevice_NewMission_DataLogPrecision))
    }
}

@Composable
private fun NewMissionDataLogRollover(
    missionDataLogRollover: Boolean,
    missionDataLogRolloverChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.clickable {
            missionDataLogRolloverChange(!missionDataLogRollover)
        }
    ) {
        Switch(
            checked = missionDataLogRollover,
            onCheckedChange = {
                missionDataLogRolloverChange(it)
            }
        )

        Text(stringResource(R.string.oneWireDevice_NewMission_DataLogRollover))
    }
}

@Composable
private fun NewMissionSampleRate(
    missionSampleRate: Int,
    missionSampleRateHighSpeed: Boolean,
    missionSampleRateChange: (Int) -> Unit,
    missionSampleRateHighSpeedChange: (Boolean) -> Unit
) {

    Row(
        modifier = Modifier.clickable {
            missionSampleRateHighSpeedChange(!missionSampleRateHighSpeed)
        }
    ) {
        Switch(
            checked = missionSampleRateHighSpeed,
            onCheckedChange = {
                missionSampleRateHighSpeedChange(it)
            }
        )

        Text(stringResource(R.string.oneWireDevice_NewMission_SampleRate_HighSpeed))
    }

    TextField(
        label = {
            Text(
                stringResource(R.string.oneWireDevice_NewMission_SampleRate_Label).format(
                    stringResource(if (missionSampleRateHighSpeed) R.string.oneWireDevice_Mission_SampleRate_sec else R.string.oneWireDevice_Mission_SampleRate_min)
                )
            )
        },
        placeholder = { Text(stringResource(R.string.oneWireDevice_NewMission_SampleRate_Placeholder)) },
        value = if (missionSampleRate > 0) "%d".format(missionSampleRate) else "",
        onValueChange = {
            if (!it.isDigitsOnly())
                return@TextField // no dots!
            missionSampleRateChange(
                if (it.isEmpty()) {
                    0
                } else {
                    it.toIntOrNull() ?: missionSampleRate
                }
            )
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
private fun convertDateTimePickerToLocalDateTime(
    missionStartDelayDate: DatePickerState,
    missionStartDelayTime: TimePickerState
): LocalDateTime {
    return if (missionStartDelayDate.selectedDateMillis != null)
        LocalDateTime.ofEpochSecond(
            missionStartDelayDate.selectedDateMillis!! / 1000,
            0,
            ZoneOffset.UTC
        )
            .truncatedTo(ChronoUnit.DAYS)
            .plusHours(missionStartDelayTime.hour.toLong())
            .plusMinutes(missionStartDelayTime.minute.toLong())
    else
        LocalDateTime.now()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMissionStart(
    missionStartDelayEnabled: Boolean,
    missionStartDelayDate: DatePickerState,
    missionStartDelayTime: TimePickerState,
    missionStartDelayEnabledChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.clickable {
            missionStartDelayEnabledChange(!missionStartDelayEnabled)
        }
    ) {
        Switch(
            checked = missionStartDelayEnabled,
            onCheckedChange = {
                missionStartDelayEnabledChange(it)
            }
        )

        Text(
            if (missionStartDelayEnabled)
                stringResource(R.string.oneWireDevice_NewMission_DelayMissionStart_withTime).format(
                    convertDateTimePickerToLocalDateTime(
                        missionStartDelayDate,
                        missionStartDelayTime
                    )
                        .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                )
            else
                stringResource(R.string.oneWireDevice_NewMission_DelayMissionStart)
        )
    }

    if (missionStartDelayEnabled) {
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {

            DatePicker(
                state = missionStartDelayDate,
                modifier = Modifier.padding(16.dp),
                title = { },
                headline = { Text(stringResource(R.string.oneWireDevice_NewMission_DelayMissionStart_startDate)) }
            )

            TimeInput(
                state = missionStartDelayTime,
                modifier = Modifier.padding(16.dp)
            )
        }
    }
}

private fun roundToHalf(d: Double): Double {
    return (d * 2.0).roundToInt().toDouble() / 2.0
}

private fun formatWithDot(d: Double, infinityNotAsEmptyString: Boolean = false): String {
    if (d.isInfinite() && !infinityNotAsEmptyString)
        return ""

    val decimalFormatSymbols = DecimalFormatSymbols.getInstance()
    decimalFormatSymbols.decimalSeparator = '.'
    return if (d.rem(1) <= 0.0e-8)
        DecimalFormat("0", decimalFormatSymbols).format(d)
    else
        DecimalFormat("0.0", decimalFormatSymbols).format(d)
}

@Composable
private fun NewMissionTempAlarm(
    isHigh: Boolean,
    missionWaitForTemperature: Boolean,
    tempThreshold: Double,
    missionWaitForTemperatureChange: (Boolean) -> Unit,
    tempThresholdChange: (Double) -> Unit
) {

    Row(
        modifier = Modifier.clickable {
            missionWaitForTemperatureChange(!missionWaitForTemperature)
        }
    ) {
        Switch(
            checked = missionWaitForTemperature,
            onCheckedChange = {
                missionWaitForTemperatureChange(it)
            }
        )

        Text(
            stringResource(
                if (isHigh)
                    R.string.oneWireDevice_NewMission_WaitForTempAlarm_High
                else
                    R.string.oneWireDevice_NewMission_WaitForTempAlarm_Low
            )
        )
    }

    if (missionWaitForTemperature) {
        OutlinedCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp)
        ) {

            Text(
                stringResource(
                    if (isHigh)
                        R.string.oneWireDevice_NewMission_WaitForTempAlarm_High_Headline
                    else
                        R.string.oneWireDevice_NewMission_WaitForTempAlarm_Low_Headline
                ),
                style = MaterialTheme.typography.headlineSmall
            )

            var textValue by remember(tempThreshold) { mutableStateOf(formatWithDot(tempThreshold)) }

            TextField(
                modifier = Modifier.padding(10.dp),
                label = {
                    Text(
                        stringResource(R.string.oneWireDevice_NewMission_WaitForTempAlarm_Label).format(
                            formatWithDot(tempThreshold, true)
                        )
                    )
                },
                placeholder = { Text(stringResource(R.string.oneWireDevice_NewMission_WaitForTempAlarm_Placeholder)) },
                value = textValue,
                onValueChange = {
                    textValue = it
                    tempThresholdChange(
                        if (it.isEmpty()) {
                            if (isHigh)
                                Double.POSITIVE_INFINITY // too hot
                            else
                                Double.NEGATIVE_INFINITY // to cold
                        } else {
                            roundToHalf(it.toDoubleOrNull() ?: tempThreshold)
                        }
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
            )
        }
    }
}

private fun reloadData(
    oneWireDevice: OneWireDS1922,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit
) {
    runActionOnOneWireDeviceAndReload(oneWireDevice, viewModel, mainHandler, onErrorCb) {
        // reload happens automatically
    }
}

private fun stopMissionAndClock(
    oneWireDevice: OneWireDS1922,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit
) {
    runActionOnOneWireDeviceAndReload(oneWireDevice, viewModel, mainHandler, onErrorCb) {
        oneWireDevice.stopMission()
        oneWireDevice.stopClock()
    }
}

@Composable
private fun ConfirmDialog(
    onDismissRequest: () -> Unit,
    onConfirmation: () -> Unit,
    dialogTitle: String,
    dialogText: String,
) {
    AlertDialog(

        title = {
            Text(text = dialogTitle)
        },
        text = {
            Text(text = dialogText)
        },
        onDismissRequest = {
            onDismissRequest()
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirmation()
                }
            ) {
                Text(stringResource(R.string.ConfirmDialog_Continue))
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onDismissRequest()
                }
            ) {
                Text(stringResource(R.string.ConfirmDialog_Abort))
            }
        }
    )
}

private fun startMissionAndClock(
    oneWireDevice: OneWireDS1922,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit,
    missionStartDelayEnabled: Boolean,
    missionStartDelayDateTime: LocalDateTime,
    missionWaitForTemperatureLow: Boolean,
    tempLowThreshold: Double,
    missionWaitForTemperatureHigh: Boolean,
    tempHighThreshold: Double,
    missionSampleRateHighSpeed: Boolean,
    missionSampleRate: Int,
    missionDataLogHighResolution: Boolean,
    missionDataLogRollover: Boolean
) {

    val startDelay = if (missionStartDelayEnabled)
        Duration.between(LocalDateTime.now(), missionStartDelayDateTime).toMinutes()
    else
        0

    runActionOnOneWireDeviceAndReload(oneWireDevice, viewModel, mainHandler, onErrorCb) {
        oneWireDevice.startMissionAndClock(
            LocalDateTime.now(),
            startDelay,
            missionWaitForTemperatureLow,
            tempLowThreshold,
            missionWaitForTemperatureHigh,
            tempHighThreshold,
            missionSampleRateHighSpeed,
            missionSampleRate,
            missionDataLogHighResolution,
            missionDataLogRollover
        )

    }
}

private fun disablePasswordProtection(
    oneWireDevice: OneWireDS1922,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit
) {
    runActionOnOneWireDeviceAndReload(oneWireDevice, viewModel, mainHandler, onErrorCb) {
        oneWireDevice.disablePasswordProtection()
    }
}

private fun enablePasswordProtection(
    oneWireDevice: OneWireDS1922,
    fullPassword1: String,
    readPassword1: String,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit
) {
    runActionOnOneWireDeviceAndReload(oneWireDevice, viewModel, mainHandler, onErrorCb) {
        oneWireDevice.enablePasswordProtection(fullPassword1, readPassword1)
    }
}

@Composable
fun GeneralPurposeMemory(
    viewModel: OneWireDS1922DeviceViewModel,
    onErrorCb: (e: Exception) -> Unit,
    context: Context,
    oneWireDevice: OneWireDS1922
) {
    val loadGPMem by viewModel.loadGPMem.observeAsState(false)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(10.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxSize()
        ) {

            Text(
                stringResource(R.string.oneWireDevice_GeneralPurposeMemory_Headline),
                style = MaterialTheme.typography.headlineSmall
            )

            Row(
                modifier = Modifier.clickable {
                    viewModel.loadGPMem.value = !loadGPMem
                }
            ) {
                Switch(
                    checked = loadGPMem,
                    onCheckedChange = {
                        viewModel.loadGPMem.value = it
                    }
                )
                Text(
                    stringResource(R.string.oneWireDevice_GeneralPurposeMemory_ShowGP)
                )
            }

            if (loadGPMem)
                OneWireDS192xDeviceScreenLoadGPMem(context, viewModel, onErrorCb)

            val oneWireDeviceGPMem by viewModel.oneWireDeviceGPMem.observeAsState(initial = null)

            if (loadGPMem && oneWireDeviceGPMem == null) {
                CircularProgressIndicator(modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally))
            } else if (loadGPMem) {
                val mainHandler = Handler(context.mainLooper)
                GeneralPurposeMemoryData(
                    oneWireDeviceGPMem!!,
                    oneWireDevice,
                    onErrorCb,
                    mainHandler,
                    viewModel
                )
            }

        }
    }
}

@Composable
private fun OneWireDS192xDeviceScreenLoadGPMem(
    context: Context,
    viewModel: OneWireDS1922DeviceViewModel,
    onErrorCb: (e: Exception) -> Unit
) {
    DisposableEffect(context)
    {
        val mainHandler = Handler(context.mainLooper)

        if (viewModel.oneWireDeviceGPMem.value == null) {
            // actions not disabled but delayed due to lock
            viewModel.oneWireDeviceLoadGpmemThread = thread {
                val d = viewModel.oneWireDevice.value!!
                val adapter = d.adapter
                adapter.lock.withLock {
                    try {
                        val gpMem = OneWireDS1922GeneralPurposeMemory(d)
                        viewModel.oneWireDeviceGPMem.postValue(gpMem)
                    } catch (e: Exception) {
                        mainHandler.post {
                            onErrorCb(e)
                        }
                    }
                }
            }
        }

        onDispose {

        }
    }
}

private fun dataToString(data: ByteArray, asText: Boolean): String {
    if (asText) {
        val cs = Charset.forName("UTF-8")

        return cs.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(data))
            .toString()
            .replace("\\p{C}".toRegex(), "?")
    } else {
        val tmp = mutableListOf<String>()
        for (i in data.indices) {
            try {
                tmp.add("%02x".format(data[i].toUByte().toInt()))
            } catch (ex: IndexOutOfBoundsException) {
                Log.d(
                    "DS1922Fragment",
                    "i=%d".format(i) + "\n" + ex.message + "\n" + ex.stackTraceToString()
                )
                tmp.add("--".format(i))
            }
        }
        return tmp.joinToString(separator = " ").chunked(8 * 3).joinToString(separator = "\n")
    }
}

@Throws(IllegalArgumentException::class)
private fun stringToData(
    txt: String,
    asText: Boolean,
    selectedLen: Int,
    context: Context
): ByteArray {
    var ret = if (asText) {
        val cs = Charset.forName("UTF-8")

        val bf = cs.newEncoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .encode(CharBuffer.wrap(txt.toCharArray()))

        val tmp = ByteArray(bf.remaining())
        bf.get(tmp)
        tmp
    } else {
        txt.split(regex = "[^a-fA-F0-9]".toRegex()).map { hexStringToByteArray(it) }
            .reduce(operation = { s, t -> s + t })
    }

    if (ret.size < selectedLen) {
        ret += ByteArray(selectedLen - ret.size) { 0x00 }
    }

    if (ret.size > selectedLen)
        throw IllegalArgumentException(
            context.getString(R.string.oneWireDevice_GeneralPurposeMemory_NewValue_InvalidInput)
                .format(selectedLen, ret.size)
        )

    return ret
}

@OptIn(ExperimentalStdlibApi::class)
private fun hexStringToByteArray(it: String): ByteArray {
    val src = if (it.length % 2 != 0) "0%s".format(it) else it
    return src.hexToByteArray()
}

@Composable
fun GeneralPurposeMemoryData(
    oneWireDeviceGPMem: OneWireDS1922GeneralPurposeMemory,
    oneWireDevice: OneWireDS1922,
    onErrorCb: (e: Exception) -> Unit,
    mainHandler: Handler,
    viewModel: OneWireDS1922DeviceViewModel
) {
    val showAsText = rememberSaveable { mutableStateOf(false) }
    val editDialogShow = rememberSaveable { mutableStateOf(false) }
    val editDialogConfirm = rememberSaveable { mutableStateOf(false) }

    Row {
        Switch(
            checked = showAsText.value,
            onCheckedChange = {
                showAsText.value = it
            }
        )

        Text(
            stringResource(R.string.oneWireDevice_GeneralPurposeMemory_ShowAsText)
        )
    }

    val rangeSaver = run {
        val firstKey = "start"
        val endKey = "end"
        mapSaver(
            save = { mapOf(firstKey to it.start, endKey to it.endInclusive) },
            restore = { it[firstKey] as Float..it[endKey] as Float }
        )
    }

    var sliderPosition by rememberSaveable(stateSaver = rangeSaver) { mutableStateOf(0f..(oneWireDeviceGPMem.len - 1).toFloat()) }
    RangeSlider(
        value = sliderPosition,
        steps = oneWireDeviceGPMem.len,
        onValueChange = { range -> sliderPosition = range },
        valueRange = 0f..(oneWireDeviceGPMem.len - 1).toFloat()
    )

    Text(
        stringResource(R.string.oneWireDevice_GeneralPurposeMemory_SelectedRange)
    )

    Row(
        modifier = Modifier.fillMaxWidth()
    ) {
        var startStr by rememberSaveable(sliderPosition.start) { mutableStateOf(sliderPosition.start.roundToInt().toString()) }
        var endStr by rememberSaveable(sliderPosition.endInclusive) { mutableStateOf(sliderPosition.endInclusive.roundToInt().toString()) }

        TextField(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(0.3F),
            label = {
                Text(
                    stringResource(R.string.oneWireDevice_GeneralPurposeMemory_Start)
                )
            },
            value = startStr,
            onValueChange = {
                startStr = it
                val start = try { it.toFloat() } catch (ex: Exception) { sliderPosition.start }
                val endInclusive = max(sliderPosition.endInclusive, start)
                sliderPosition = start .. endInclusive
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )

        TextField(
            modifier = Modifier
                .padding(10.dp)
                .fillMaxWidth(0.3F),
            label = {
                Text(
                    stringResource(R.string.oneWireDevice_GeneralPurposeMemory_End)
                )
            },
            value = endStr,
            onValueChange = {
                endStr = it
                val endInclusive = try { it.toFloat() } catch (ex: Exception) { sliderPosition.endInclusive }
                val start = min(sliderPosition.start, endInclusive)
                sliderPosition = start .. endInclusive
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }

    Text(
        text = stringResource(R.string.oneWireDevice_GeneralPurposeMemory_Range).format(
            sliderPosition.start.roundToInt(),
            sliderPosition.endInclusive.roundToInt()
        )
    )

    val r = sliderPosition.start.roundToInt()..sliderPosition.endInclusive.roundToInt()

    val txt =
        try {
            dataToString(oneWireDeviceGPMem[r], showAsText.value)
        } catch (ex: Exception) {
            Log.d("DS1922Fragment", ex.message + "\n" + ex.stackTraceToString())
            stringResource(R.string.oneWireDevice_GeneralPurposeMemory_Error).format(ex.localizedMessage)
        }
    Text(
        text = txt
    )

    TextButton(
        onClick = {
            editDialogShow.value = true
        }
    ) {
        Text(stringResource(R.string.oneWireDevice_GeneralPurposeMemory_EditBtn))
    }

    val selectedLen =
        sliderPosition.endInclusive.roundToInt() + 1 - sliderPosition.start.roundToInt()

    val context = LocalContext.current
    var newTxt by rememberSaveable(inputs = arrayOf(txt)) { mutableStateOf(txt) }
    val newData by rememberSaveable(inputs = arrayOf(newTxt, showAsText.value)) {
        mutableStateOf(
            try {
                stringToData(newTxt, showAsText.value, selectedLen, context)
            } catch (ex: Exception) {
                Log.d("DS1922Fragment", ex.toString())
                null
            }
        )
    }

    val onEditAbort = {
        editDialogShow.value = false
    }

    val onEditContinue = {
        editDialogShow.value = false
        editDialogConfirm.value = true
    }

    when {
        editDialogShow.value -> {
            Dialog(
                onDismissRequest = onEditAbort
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Column(
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {

                        Text(
                            text = stringResource(R.string.oneWireDevice_GeneralPurposeMemory_NewValue_Headline),
                            modifier = Modifier.padding(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.oneWireDevice_GeneralPurposeMemory_RangeWrite).format(
                                sliderPosition.start.roundToInt(),
                                sliderPosition.endInclusive.roundToInt()
                            )
                        )

                        OutlinedTextField(
                            value = newTxt,
                            onValueChange = { newTxt = it },
                            label = { Text(stringResource(R.string.oneWireDevice_GeneralPurposeMemory_newValue_label)) }
                        )

                        if (newData == null)
                            Text(stringResource(R.string.oneWireDevice_GeneralPurposeMemory_errInvalidNew))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            TextButton(
                                onClick = { onEditAbort() },
                                modifier = Modifier.padding(8.dp),
                            ) {
                                Text(stringResource(R.string.oneWireDevice_GeneralPurposeMemory_Dismiss))
                            }
                            TextButton(
                                onClick = { onEditContinue() },
                                modifier = Modifier.padding(8.dp),
                                enabled = newData != null
                            ) {
                                Text(stringResource(R.string.oneWireDevice_GeneralPurposeMemory_Continue))
                            }
                        }
                    }
                }

            }
        }
    }

    when {
        editDialogConfirm.value -> {
            ConfirmDialog(
                onDismissRequest = { editDialogConfirm.value = false },
                onConfirmation = {
                    editDialogConfirm.value = false

                    writeGeneralPurposeMemory(
                        oneWireDevice = oneWireDevice,
                        data = newData!!,
                        range = r,
                        viewModel = viewModel,
                        mainHandler = mainHandler,
                        onErrorCb = onErrorCb,
                        oneWireDeviceGPMem
                    )
                },
                dialogTitle = stringResource(R.string.oneWireDevice_GeneralPurposeMemory_Confirm_Headline),
                dialogText = stringResource(R.string.oneWireDevice_GeneralPurposeMemory_Confirm_Text).format(
                    sliderPosition.start.roundToInt(),
                    sliderPosition.endInclusive.roundToInt(),
                    if (newData != null) dataToString(newData!!, false) else stringResource(R.string.oneWireDevice_GeneralPurposeMemory_error),
                    if (newData != null) dataToString(newData!!, true) else  stringResource(R.string.oneWireDevice_GeneralPurposeMemory_error),
                ),
            )
        }
    }

}

fun writeGeneralPurposeMemory(
    oneWireDevice: OneWireDS1922,
    data: ByteArray,
    range: IntRange,
    viewModel: OneWireDS1922DeviceViewModel,
    mainHandler: Handler,
    onErrorCb: (e: Exception) -> Unit,
    oneWireDeviceGPMem: OneWireDS1922GeneralPurposeMemory
) {
    runActionOnOneWireDeviceAndReload(oneWireDevice, viewModel, mainHandler, onErrorCb) {
        oneWireDevice.writeGeneralPurposeMemory(data, range, oneWireDeviceGPMem)
    }

}
