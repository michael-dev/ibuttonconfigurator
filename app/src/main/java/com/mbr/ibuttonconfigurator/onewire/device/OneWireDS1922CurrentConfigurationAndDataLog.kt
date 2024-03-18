package com.mbr.ibuttonconfigurator.onewire.device

class OneWireDS1922CurrentConfigurationAndDataLog(oneWireDevice: OneWireDS1922) {
    val rtcState = oneWireDevice.getRtcState()
    val deviceType = oneWireDevice.deviceTypeAsString()
    val deviceSamplesCounter = oneWireDevice.deviceSamplesCounter()
    val hasPasswordProtection = oneWireDevice.hasPasswordProtectionEnabled()
    val latestTemperature = oneWireDevice.latestTemperature()
    val missionSamplesCounter = oneWireDevice.missionSamplesCounter()
    val missionInProgress = oneWireDevice.missionInProgress()
    val missionMemoryCleared = oneWireDevice.missionMemoryCleared()
    val missionSampleRate = oneWireDevice.sampleRate()
    val missionTempAlarmLow = oneWireDevice.tempAlarmLow()
    val missionTempAlarmHigh = oneWireDevice.tempAlarmHigh()
    val missionTempAlarmLowEnabled = oneWireDevice.tempAlarmLowEnabled()
    val missionTempAlarmHighEnabled = oneWireDevice.tempAlarmHighEnabled()
    val missionTempAlarmLowSeen = oneWireDevice.tempAlarmLowSeen()
    val missionTempAlarmHighSeen = oneWireDevice.tempAlarmHighSeen()
    val borAlarm = oneWireDevice.batteryOnResetAlarm()
    val missionWaitingForTemperatureAlarm = oneWireDevice.waitingForTemperatureAlarm()
    val missionStartOnTemperatureAlarm = oneWireDevice.missionStartOnTemperatureAlarm()
    val missionEnableTemperatureLogging = oneWireDevice.missionTemperatureLoggingEnabled()
    val missionEnableTemperatureLoggingRollover =
        oneWireDevice.missionTemperatureLoggingRolloverEnabled()
    val missionTemperatureLoggingHighResolution =
        oneWireDevice.missionTemperatureLoggingHighResolution()
    val missionStartDelayCounter = oneWireDevice.missionStartDelayCounter()
    val missionStartTimestamp = oneWireDevice.missionStartTimestamp()
    val missionLoggedMeasurements = oneWireDevice.getLoggedMeasurements()
}