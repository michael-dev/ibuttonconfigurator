package com.mbr.ibuttonconfigurator.onewire.device

import android.util.Log
import com.mbr.ibuttonconfigurator.onewire.IOneWireDevice
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireAdapterBase
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireRomId
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.SortedMap
import kotlin.math.pow
import kotlin.math.roundToLong

class OneWireDS1922(
    val adapter: OneWireAdapterBase,
    romId: OneWireRomId,
    inputPassword: String
) : IOneWireDevice(adapter, romId) {

    private val rawDevice = OneWireDS1922Raw(adapter, romId)
    private var password = passwordStringToBuf(inputPassword)

    private var configurationRegister: OneWireDS1922Raw.RegisterData
    private val deviceType : OneWireDS1922Raw.DS1922DeviceType
    private var deviceConfiguration : OneWireDS1922Raw.DeviceTypeConfigurationData
    private val calibrationData : OneWireDS1922Raw.CalibrationData
    private lateinit var dataLog : OneWireDS1922Raw.RegisterData

    init {
        configurationRegister = rawDevice.cmdReadMemoryWithPasswordAndCrc(
            offset = rawDevice.registerConfig,
            password = password,
            numBytesToRead = rawDevice.registerConfigLength
        )
        deviceType = rawDevice.detectDeviceType(
            password = password,
            preloadedRegisterData = configurationRegister
        )
        deviceConfiguration = rawDevice.getDeviceConfiguration(
            password = password,
            preloadedRegisterData = configurationRegister
        )
        calibrationData = rawDevice.getCalibrationData(password = password)

        loadDataLog()
    }

    fun reloadConfigurationDataAndDataLog() {
        configurationRegister = rawDevice.cmdReadMemoryWithPasswordAndCrc(
            offset = rawDevice.registerConfig,
            password = password,
            numBytesToRead = rawDevice.registerConfigLength
        )

        // reload data log as mission samples counter might have changed
        loadDataLog()

    }

    private fun loadDataLog() {
        val missionSampleCount = missionSamplesCounter().toInt()
        val highRes = missionTemperatureLoggingHighResolution()
        val stepSize = if (highRes) 2 else 1
        val numBytesWanted = (missionSampleCount + 1) * stepSize
        val numBytesAvailable = minOf(rawDevice.registerDataLogLength, numBytesWanted)
        val numBytesToRead = roundUpToRegisterSize(numBytesAvailable)

        Log.d("DS1922",
            "DataLog Reading: sc=%d, hi=%s, step=%d, w=%d, ava=%d, r=%d".format(
                missionSampleCount,
                highRes,
                stepSize,
                numBytesWanted,
                numBytesAvailable,
                numBytesToRead
            )
        )

        dataLog = rawDevice.cmdReadMemoryWithPasswordAndCrc(
            offset = rawDevice.registerDataLog,
            password = password,
            numBytesToRead = numBytesToRead
        )
    }

    private fun roundDownToRegisterStart(i: Int): Int {
        return i - i % 32
    }

    private fun roundUpToRegisterSize(i: Int): Int {
        val rem = i % 32
        if (rem == 0)
            return i
        return i + 32 - rem
    }

    companion object {
        const val familyCode1922or1923: Byte = 0x41 // DS1922/DS1923 Family Code
    }

    fun deviceTypeAsString(): String {
        return deviceType.toString()
    }

    data class TemperatureMeasurement(val temp: Double, val tempCalibrated: Double)

    /**
     * Real Time Temperature. Performs the temperature conversion,
     * reads the result from registers, and calculates the resulting
     * temperature value.
     *
     * @return Temperature measured
     */
    fun cmdRealTimeTemperature(): TemperatureMeasurement {
        val tempData = rawDevice.cmdForcedConversation(password)

        return TemperatureMeasurement(
            rawToTemp(
                trh = tempData[1].toUByte(),
                trl = tempData[0].toUByte()
            ),
            rawToTempCalibrated(
                trh = tempData[1].toUByte(),
                trl = tempData[0].toUByte()
            )
        )
    }

    private fun rawToTemp(
        trh: UByte,
        trl: UByte? = null,
    ): Double {
        if (trh == 0x00u.toUByte() && (trl ?: 0x00u.toUByte()) == 0x00u.toUByte())
            return Double.NEGATIVE_INFINITY // too cold
        if (trh == 0xFFu.toUByte() && (trl ?: 0xE0u.toUByte()) == 0xE0u.toUByte())
            return Double.POSITIVE_INFINITY // too warm
        if (trh == 0xFFu.toUByte() && (trl ?: 0xE0u.toUByte()) > 0xE0u.toUByte())
            throw Exception(
                "invalid temperature reading: trh=%02x trl=%02x".format(
                    trh.toByte(),
                    trl!!.toByte()
                )
            )

        val offset = deviceConfiguration.offset

        return trh.toDouble() / 2.0 - offset + (trl?.toDouble() ?: 0.0) / 512.0
    }

    private data class CalibrationConstants(
        val a: Double,
        val b: Double,
        val c: Double
    )

    private fun getCalibrationConstants(): CalibrationConstants
    {
        val tr1 = deviceConfiguration.tr1.toDouble()

        val tr2 = rawToTemp(calibrationData.tr2h, calibrationData.tr2l)
        val tr3 = rawToTemp(calibrationData.tr3h, calibrationData.tr3l)
        val tc2 = rawToTemp(calibrationData.tc2h, calibrationData.tc2l)
        val tc3 = rawToTemp(calibrationData.tc3h, calibrationData.tc3l)

        val err3 = tc3 - tr3
        val err2 = tc2 - tr2
        val err1 = err2

        val tr1s = tr1.pow(2)
        val tr2s = tr2.pow(2)
        val tr3s = tr3.pow(2)

        val b =
            (tr2s - tr1s) * (err3 - err1) / ((tr2s - tr1s) * (tr3 - tr1) + (tr3s - tr1s) * (tr1 - tr2))
        val a = b * (tr1 - tr2) / (tr2s - tr1s)
        val c = err1 - a * tr1s - b * tr1

        return CalibrationConstants(a = a, b = b, c = c)
    }

    private fun rawToTempCalibrated(
        trh: UByte,
        trl: UByte? = null
    ): Double {
        val tc = rawToTemp(trh, trl)

        if (tc.isInfinite())
            return tc

        val calCon = getCalibrationConstants()

        val tcs = tc.pow(2)

        return tc - (calCon.a * tcs + calCon.b * tc + calCon.c)
    }

    private fun tempToRaw(
        temp: Double,
    ): UByte {
        if (temp == Double.NEGATIVE_INFINITY)
            return 0x00u
        if (temp == Double.POSITIVE_INFINITY)
            return 0xFFu

        val offset = 2 * deviceConfiguration.offset
        return (2 * temp + offset).roundToLong().toUByte()
    }

    data class RtcTimestamp(
        var datetime: LocalDateTime,
        var is24h: Boolean,
        var cent: Boolean,
        val debugString: String
    )

    data class RtcState(
        var timestamp: RtcTimestamp?,
        var isOscillating: Boolean,
        val isHighSpeed: Boolean,
    )

    private fun deviceRtcToRtcTimestamp(rtc: OneWireDS1922Raw.DeviceRTC): RtcTimestamp? {
        if (rtc.isAllZero())
            return null

        val currentYear = LocalDateTime.now().year
        val centuryNow = (currentYear / 100) * 100

        var year = rtc.years.toInt() + centuryNow
        if (year > currentYear)
            year -= 100

        Log.d("DS1922","converting RTC to LocalDateTime: %s\nyear=%d month=%d, day=%d, hours=%d, minutes=%d, seconds=%d".format(rtc,
            rtc.years.toInt(), rtc.months.toInt(), rtc.days.toInt(), rtc.hours.toInt(), rtc.minutes.toInt(), rtc.seconds.toInt()))

        return RtcTimestamp(
            datetime = LocalDateTime.of(
                year,
                rtc.months.toInt(),
                rtc.days.toInt(),
                rtc.hours.toInt(),
                rtc.minutes.toInt(),
                rtc.seconds.toInt()
            ),
            is24h = rtc.is24h,
            cent = rtc.cent,
            debugString = "RTC: 0x%s".format(
                rtc.debugHexString()
            )
        )
    }

    fun getRtcState(): RtcState {
        val rtc = rawDevice.getRTC(
            password = password,
            preloadedRegisterData = configurationRegister
        )

        val rtcControl = rawDevice.getRtcControl(
            password = password,
            preloadedRegisterData = configurationRegister
        )

        return RtcState(
            timestamp = deviceRtcToRtcTimestamp(rtc),
            isOscillating = rtcControl.eosc,
            isHighSpeed = rtcControl.ehss
        )
    }

    fun deviceSamplesCounter(): Long {
        return rawDevice.getDeviceSamplesCounter(password, configurationRegister)

    }

    fun hasPasswordProtectionEnabled(): Boolean {
        return rawDevice.getPWControl(password, configurationRegister) == rawDevice.pwControlPasswordProtected
    }

    fun latestTemperature(): TemperatureMeasurement {
        val tempData = rawDevice.getLatestTemperature(password, configurationRegister)

        return TemperatureMeasurement(
            rawToTemp(
                trh = tempData[1].toUByte(),
                trl = tempData[0].toUByte()
            ),
            rawToTempCalibrated(
                trh = tempData[1].toUByte(),
                trl = tempData[0].toUByte()
            )
        )
    }

    fun missionSamplesCounter(): Long {
        return rawDevice.getMissionSamplesCounter(password, configurationRegister)
    }

    fun missionInProgress(): Boolean {
        return rawDevice.getStatus(password, configurationRegister).mip
    }

    fun missionMemoryCleared(): Boolean {
        return rawDevice.getStatus(password, configurationRegister).memclr
    }

    fun sampleRate(): Int {
        return rawDevice.getSampleRate(password, configurationRegister)
    }

    fun tempAlarmLow(): TemperatureMeasurement {
        val reg = rawDevice.getTemperatureAlarm(password, configurationRegister).lowThreshold
        return TemperatureMeasurement(
            rawToTemp(
                trh = reg,
            ),
            rawToTempCalibrated(
                trh = reg,
            )
        )
    }

    fun tempAlarmHigh(): TemperatureMeasurement {
        val reg = rawDevice.getTemperatureAlarm(password, configurationRegister).highThreshold
        return TemperatureMeasurement(
            rawToTemp(
                trh = reg,
            ),
            rawToTempCalibrated(
                trh = reg,
            )
        )
    }

    fun tempAlarmLowEnabled(): Boolean {
        return rawDevice.getTemperatureAlarmEnable(password, configurationRegister).etla
    }

    fun tempAlarmHighEnabled(): Boolean {
        return rawDevice.getTemperatureAlarmEnable(password, configurationRegister).etha
    }

    fun tempAlarmLowSeen(): Boolean {
        return rawDevice.getAlarmStatus(password, configurationRegister).tlf
    }

    fun tempAlarmHighSeen(): Boolean {
        return rawDevice.getAlarmStatus(password, configurationRegister).thf
    }

    fun batteryOnResetAlarm(): Boolean {
        return rawDevice.getAlarmStatus(password, configurationRegister).bor

    }

    fun waitingForTemperatureAlarm(): Boolean {
        return rawDevice.getStatus(password, configurationRegister).wfta

    }

    fun missionStartOnTemperatureAlarm(): Boolean {
        return rawDevice.getMissionControl(password, configurationRegister).suta
    }

    fun missionTemperatureLoggingEnabled(): Boolean {
        return rawDevice.getMissionControl(password, configurationRegister).etl

    }

    fun missionTemperatureLoggingRolloverEnabled(): Boolean {
        return rawDevice.getMissionControl(password, configurationRegister).ro

    }

    fun missionTemperatureLoggingHighResolution(): Boolean {
        return rawDevice.getMissionControl(password, configurationRegister).tlfs
    }

    fun missionStartDelayCounter(): Long {
        return rawDevice.getStartDelayCounter(password, configurationRegister)
    }

    fun missionStartTimestamp(): RtcTimestamp? {
        val missionTimestamp = rawDevice.getMissionTimestamp(
            password = password,
            preloadedRegisterData = configurationRegister
        )

        return deviceRtcToRtcTimestamp(missionTimestamp)
    }

    private fun getDataLogEntry(offset: Long, highResolution: Boolean): TemperatureMeasurement {
        check (offset >= 0)

        val registerData = rawDevice.getDataLogEntry(offset, highResolution, password, dataLog)
        val trh = registerData[0]

        if (highResolution) {
            val trl = registerData[1]

            return TemperatureMeasurement(
                rawToTemp(
                    trh = trh.toUByte(),
                    trl = trl.toUByte()
                ),
                rawToTempCalibrated(
                    trh = trh.toUByte(),
                    trl = trl.toUByte()
                )
            )
        } else {
            return TemperatureMeasurement(
                rawToTemp(
                    trh = trh.toUByte(),
                ),
                rawToTempCalibrated(
                    trh = trh.toUByte(),
                )
            )
        }
    }

    private fun getMaxDataLogSize(highResolution: Boolean): Int {
        return if (highResolution)
            rawDevice.registerDataLogLength / 2
        else
            rawDevice.registerConfigLength
    }

    fun getLoggedMeasurements(): SortedMap<Long, Pair<LocalDateTime, TemperatureMeasurement>> {
        val ret = sortedMapOf<Long,Pair<LocalDateTime,TemperatureMeasurement>>()

        val rtcState = getRtcState()
        val missionSamplesCounter = missionSamplesCounter()
        val missionSampleRate = sampleRate()
        val missionTempAlarmLowSeen = tempAlarmLowSeen()
        val missionTempAlarmHighSeen = tempAlarmHighSeen()
        val missionStartTimestamp = missionStartTimestamp()
        val missionEnableTemperatureLoggingRollover = missionTemperatureLoggingRolloverEnabled()
        val missionTemperatureLoggingHighResolution = missionTemperatureLoggingHighResolution()
        val maxLoggedTemperatures = getMaxDataLogSize(missionTemperatureLoggingHighResolution)
        val missionStartOnTemperatureAlarm = missionStartOnTemperatureAlarm()

        val hasExtraDataLogForTempAlarm = missionStartOnTemperatureAlarm && (missionTempAlarmLowSeen || missionTempAlarmHighSeen)
        val offsetForTempAlarm: Long = if (hasExtraDataLogForTempAlarm) 1 else 0
        val totalSamples = missionSamplesCounter + offsetForTempAlarm

        val firstLoggedMeasurement:Long = if (!missionEnableTemperatureLoggingRollover ||
            totalSamples <= maxLoggedTemperatures
        )
            -1 * offsetForTempAlarm
        else
            missionSamplesCounter - maxLoggedTemperatures

        val loggedMeasurements = firstLoggedMeasurement until totalSamples
        val stepSize = if (missionTemperatureLoggingHighResolution) 2 else 1

        val sampleRateAsInterval = if (rtcState.isHighSpeed)
            Duration.ofSeconds(missionSampleRate.toLong())
        else
            Duration.ofMinutes(missionSampleRate.toLong())

        for (i in loggedMeasurements) { // Lazy Column fails as parent column is already scrollable
            val m = getDataLogEntry(
                (offsetForTempAlarm + i) * stepSize,
                missionTemperatureLoggingHighResolution
            )

            val timeOffset = sampleRateAsInterval.multipliedBy(i)
            val ts = (missionStartTimestamp?.datetime ?: fallbackMissionStart).plus(timeOffset)
            // should handle 29.2. automatically

            ret[i] = Pair(ts, m)
        }

        return ret
    }

    val fallbackMissionStart: LocalDateTime = LocalDateTime.of(1970, 1, 1, 0,0,0)

    fun stopMission() {
        rawDevice.cmdStopMissionWithPassword(password)
    }

    fun stopClock() {
        val currentConfigurationRegister = rawDevice.cmdReadMemoryWithPasswordAndCrc(
            offset = rawDevice.registerConfig,
            password = password,
            numBytesToRead = rawDevice.registerConfigLength
        ).toMutableRegisterData()

        val rtcControl = rawDevice.getRtcControl(password, currentConfigurationRegister)
        rtcControl.eosc = false
        rawDevice.setRtcControl(currentConfigurationRegister, rtcControl)

        writeRegisterData(currentConfigurationRegister)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun writeRegisterData(
        currentConfigurationRegister: OneWireDS1922Raw.MutableRegisterData
    ) {
        if (currentConfigurationRegister.firstChangeIdx == null ||
            currentConfigurationRegister.lastChangeIdx == null)
            return // nothing changed

        val startIdx = roundDownToRegisterStart(currentConfigurationRegister.firstChangeIdx!!)
        val endIdx = roundUpToRegisterSize(currentConfigurationRegister.lastChangeIdx!! + 1)

        for (i in startIdx until endIdx step 32) {
            Log.d("DS1922", "Starting to write register data from %04x for 32 bytes".format(i))
            // write 32 bytes starting at startIdx

            var retryCount = 0
            val maxRetry = 3
            val currentData = currentConfigurationRegister[i until i + 32]
            var authPattern: ByteArray? = null

            do {
                rawDevice.cmdWriteScratchpad(i, currentData) // CRC write already checked

                val readBack = rawDevice.cmdReadScratchpad(i)
                readBack.authPattern

                // check data
                check (!readBack.flagAA)
                check(!readBack.flagPF)

                if (currentData contentEquals readBack.scratchpadData) {
                    authPattern = readBack.authPattern
                    break // success
                }

                Log.d("DS1922", "currentData.size = %d, readBack.size = %d".format(
                    currentData.size, readBack.scratchpadData.size
                ))

                Log.d("DS1922", "currentData = %s".format(
                    currentData.toHexString()
                ))

                Log.d("DS1922", "readBack = %s".format(
                    readBack.scratchpadData.toHexString()
                ))

                Log.d("DS1922", "repeat write scratchpad")

                retryCount++
            } while (retryCount < maxRetry)

            check(retryCount < maxRetry) // retries exceeded
            check(authPattern != null) // data did not match

            rawDevice.cmdCopyScratchpadWithPassword(authPattern = authPattern, password = password)
        }
    }

    fun startMissionAndClock(
        now: LocalDateTime,
        missionStartDelay: Long,
        missionWaitForTemperatureLow: Boolean,
        tempLowThreshold: Double,
        missionWaitForTemperatureHigh: Boolean,
        tempHighThreshold: Double,
        missionSampleRateHighSpeed: Boolean,
        missionSampleRate: Int,
        missionDataLogHighResolution: Boolean,
        missionDataLogRollover: Boolean
    ) {
        Log.d("DS1922", ("startMissionAndClock now=%s, " +
                "missionStartDelay=%d, " +
                "missionWaitForTemperatureLow=%s, " +
                "tempLowThreshold=%.1f, " +
                "missionWaitForTemperatureHigh=%s, " +
                "tempHighThreshold=%.1f, " +
                "missionSampleRateHighSpeed=%s, " +
                "missionSampleRate=%d, " +
                "missionDataLogHighResolution=%s, " +
                "missionDataLogRollover = %s").format(
                    now.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    missionStartDelay,
                    missionWaitForTemperatureLow.toString(),
                    tempLowThreshold,
                    missionWaitForTemperatureHigh.toString(),
                    tempHighThreshold,
                    missionSampleRateHighSpeed.toString(),
                    missionSampleRate,
                    missionDataLogHighResolution.toString(),
                    missionDataLogRollover.toString()
                ))

        val currentConfigurationRegister = rawDevice.cmdReadMemoryWithPasswordAndCrc(
            offset = rawDevice.registerConfig,
            password = password,
            numBytesToRead = rawDevice.registerConfigLength
        ).toMutableRegisterData()

        run {
            val rtc = rawDevice.getRTC(password, currentConfigurationRegister)
            rtc.years = (now.year % 100).toUByte()
            rtc.months = now.monthValue.toUByte()
            rtc.days = now.dayOfMonth.toUByte()

            rtc.hours = now.hour.toUByte()
            rtc.minutes = now.minute.toUByte()
            rtc.seconds = now.second.toUByte()

            rtc.is24h = true
            rtc.cent = false

            Log.d("DS1922","LocalDateTime to RTC: %s\nyear=%d month=%d, day=%d, hours=%d, minutes=%d, seconds=%d".format(rtc,
                rtc.years.toInt(), rtc.months.toInt(), rtc.days.toInt(), rtc.hours.toInt(), rtc.minutes.toInt(), rtc.seconds.toInt()))

            rawDevice.setRTC(currentConfigurationRegister, rtc)
        }

        run {
            val rtcControl = rawDevice.getRtcControl(password, currentConfigurationRegister)
            rtcControl.ehss = missionSampleRateHighSpeed
            rtcControl.eosc = true
            rawDevice.setRtcControl(currentConfigurationRegister, rtcControl)
        }

        rawDevice.setSampleRate(currentConfigurationRegister, missionSampleRate.toUInt())
        rawDevice.setStartDelayCounter(currentConfigurationRegister, missionStartDelay.toUInt())

        run {
            val mc = rawDevice.getMissionControl(password, currentConfigurationRegister)

            mc.etl = true // enable temperature logging
            mc.tlfs = missionDataLogHighResolution // high or low resolution temp
            mc.ro = missionDataLogRollover
            mc.suta = missionWaitForTemperatureLow || missionWaitForTemperatureHigh

            rawDevice.setMissionControl(currentConfigurationRegister, mc)
        }

        run {

            val tempThresholdLow: UByte = tempToRaw(tempLowThreshold)

            val tempThresholdHigh: UByte = tempToRaw(tempHighThreshold)

            rawDevice.setTemperatureAlarm(
                configurationRegister = currentConfigurationRegister,
                lowThreshold = tempThresholdLow,
                highThreshold = tempThresholdHigh
            )
        }

        run {
            val ta = rawDevice.getTemperatureAlarmEnable(password, currentConfigurationRegister)
            ta.etha = missionWaitForTemperatureHigh
            ta.etla = missionWaitForTemperatureLow
            rawDevice.setTemperatureAlarmEnable(currentConfigurationRegister, ta)
        }

        writeRegisterData(currentConfigurationRegister)

        rawDevice.cmdClearMemoryWithPassword(password)

        rawDevice.cmdStartMissionWithPassword(password)
    }

    fun disablePasswordProtection() {
        val currentConfigurationRegister = rawDevice.cmdReadMemoryWithPasswordAndCrc(
            offset = rawDevice.registerConfig,
            password = password,
            numBytesToRead = rawDevice.registerConfigLength
        ).toMutableRegisterData()

        rawDevice.setPWControl(currentConfigurationRegister, rawDevice.pwControlPasswordProtected xor 0x5Fu)

        writeRegisterData(currentConfigurationRegister)
    }

    fun enablePasswordProtection(
        fullPassword: String,
        readPassword: String
    ) {
        // needs testing i.e. looking at bit ordering!

        val fullPasswordBuf = passwordStringToBuf(fullPassword)
        val readPasswordBuf = passwordStringToBuf(readPassword)

        check(fullPasswordBuf.size == 8)
        check(readPasswordBuf.size == 8)

        Log.d("DS1922", ("Enable password with " +
                "fp = %02x:%02x:%02x:%02x:%02x:%02x:%02x:%02x and " +
                "rp = %02x:%02x:%02x:%02x:%02x:%02x:%02x:%02x").format(
            fullPasswordBuf[0].toUInt().toInt(),
            fullPasswordBuf[1].toUInt().toInt(),
            fullPasswordBuf[2].toUInt().toInt(),
            fullPasswordBuf[3].toUInt().toInt(),
            fullPasswordBuf[4].toUInt().toInt(),
            fullPasswordBuf[5].toUInt().toInt(),
            fullPasswordBuf[6].toUInt().toInt(),
            fullPasswordBuf[7].toUInt().toInt(),

            readPasswordBuf[0].toUInt().toInt(),
            readPasswordBuf[1].toUInt().toInt(),
            readPasswordBuf[2].toUInt().toInt(),
            readPasswordBuf[3].toUInt().toInt(),
            readPasswordBuf[4].toUInt().toInt(),
            readPasswordBuf[5].toUInt().toInt(),
            readPasswordBuf[6].toUInt().toInt(),
            readPasswordBuf[7].toUInt().toInt()
        ))
        
        val currentConfigurationRegister = rawDevice.cmdReadMemoryWithPasswordAndCrc(
            offset = rawDevice.registerConfig,
            password = password,
            numBytesToRead = rawDevice.registerConfigLength
        ).toMutableRegisterData()

        rawDevice.setPasswords(
            registerData = currentConfigurationRegister,
            fullAccessPassword = fullPasswordBuf,
            readAccessPassword = readPasswordBuf
        )

        rawDevice.setPWControl(currentConfigurationRegister, rawDevice.pwControlPasswordProtected)

        writeRegisterData(currentConfigurationRegister)

        this.password = fullPasswordBuf
    }

    private fun passwordStringToBuf(password: String): ByteArray {
        if (password == "")
            return ByteArray(8) // all-zero

        val passwordUtf8Buf = password.encodeToByteArray() // as UTF-8

        val bytes = MessageDigest
            .getInstance("SHA-256") // no security function. Just needs random input -> random output of fixed length.
            .digest(passwordUtf8Buf)

        return bytes.sliceArray(0 until 8)
    }

    override fun setAccessPassword(password: String) {
        val passwordBuf = passwordStringToBuf(password)

        check (passwordBuf.size == 8)

        this.password = passwordBuf
    }

    fun getGeneralPurposeMemory(): OneWireDS1922Raw.MutableRegisterData {
        return rawDevice.cmdReadMemoryWithPasswordAndCrc(
            offset = rawDevice.generalPurposeStart,
            password = password,
            numBytesToRead = rawDevice.generalPurposeLength
        ).toMutableRegisterData()
    }

    fun writeGeneralPurposeMemory(
        data: ByteArray,
        range: IntRange,
        oneWireDeviceGPMem: OneWireDS1922GeneralPurposeMemory
    ) {
        val rangeLen = range.endInclusive + 1 - range.first
        if (data.size != rangeLen)
            throw IllegalArgumentException("data.size = %d != %d = rangeLen".format(data.size, rangeLen))
        val changedOneWireDeviceGPMem = oneWireDeviceGPMem.clone()
        changedOneWireDeviceGPMem[range.first] = data
        writeRegisterData(changedOneWireDeviceGPMem.memory)
    }
}