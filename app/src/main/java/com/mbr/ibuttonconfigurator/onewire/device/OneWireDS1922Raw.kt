package com.mbr.ibuttonconfigurator.onewire.device

import android.os.SystemClock
import android.util.Log
import com.mbr.ibuttonconfigurator.helper.CRC16
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireAdapterBase
import com.mbr.ibuttonconfigurator.usb.adapter.OneWireRomId
import java.util.concurrent.TimeUnit
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or
import kotlin.experimental.xor

class OneWireDS1922Raw(
    internal val adapter: OneWireAdapterBase,
    private val romId: OneWireRomId,
    private val enableResume: Boolean = false,
    private val useSkipRom: Boolean = false
) {

    val generalPurposeLength: Int = 16 * 32 // 16 pages per 32 bytes
    val generalPurposeStart: Int = 0
    private var canResume = false

    private fun accessDevice() {
        adapter.oneWireResetBus()
        if (useSkipRom) {
            adapter.oneWireSkipRom()
        } else if (enableResume && canResume)
            adapter.oneWireResume()
        else {
            adapter.oneWireMatchRom(romId)
            canResume = true
        }
    }

    class TransmissionErrorException(msg: String) : Exception(msg)
    class TransmissionInternalConflict : Exception()

    private val conflictWaitTime: Long = 500 // ms
    private val maxRetryConflict = 5

    private fun <T> accessDeviceAndRepeatCommand(
        recoveryCmd: () -> T? = { null },
        cmd: () -> T
    ): T {
        for (i in 0 until maxRetryConflict) {
            try {
                accessDevice()
                return cmd()
            } catch (e: TransmissionInternalConflict) {
                Log.d("DS1922RAW", "conflict, retrying ...")
                SystemClock.sleep(conflictWaitTime)
                adapter.oneWireResetBus()

                val ret = recoveryCmd()
                if (ret != null)
                    return ret
            }
        }

        throw Exception("Command failed - maximum number of retries exceeded")
    }

    internal fun cmdWriteScratchpad(offset: Int, data: ByteArray) {
        Log.d("DS1922Raw","cmdWriteScratchpad offset=%d len=%d".format(offset, data.size))

        val numBytes = 0x20 - (offset and 0x0f)
        if (data.size != numBytes)
            throw Exception(
                "cmdWriteScratchpad expected %d bytes, %d given (offset = %d)".format(
                    numBytes,
                    data.size,
                    offset
                )
            )

        val offsetL = (offset and 0xFF).toByte()
        val offsetH = ((offset shr 8) and 0xFF).toByte()
        val dataToTransmit = byteArrayOf(0x0F, offsetL, offsetH) + data

        Log.d("DS1922Raw","cmdWriteScratchpad offsetL=%02x offsetH=%02x".format(offsetL.toUByte().toInt(), offsetH.toUByte().toInt()))

        val crcExpected = run {
            val crc = CRC16()
            crc.update(dataToTransmit, 0, dataToTransmit.size)
            crc.value
        }

        accessDeviceAndRepeatCommand {
            adapter.oneWireWrite(dataToTransmit)

            val invertedCrcRx = adapter.oneWireRead(2)
            val crcRxL = invertedCrcRx[0] xor 0xFF.toByte()
            val crcRxH = invertedCrcRx[1] xor 0xFF.toByte()

            val crcRx = (crcRxL.toUByte().toLong()) + (crcRxH.toUByte().toLong() shl 8)
            if (crcRx != crcExpected) {
                // transmission error
                if (crcRxL == 0x00.toByte() && crcRxH == 0x00.toByte())
                    throw TransmissionInternalConflict() // shall trigger re-run

                throw TransmissionErrorException(
                    "WRITE SCRATCHPAD CRC error: rx %x != tx %x".format(
                        crcRx,
                        crcExpected
                    )
                )
            }
        }
    }

    data class ReadScratchpadResponse(
        val endingOffset: Int,
        val flagAA: Boolean,
        val flagPF: Boolean,
        val scratchpadData: ByteArray,
        val authPattern: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ReadScratchpadResponse

            if (endingOffset != other.endingOffset) return false
            if (flagAA != other.flagAA) return false
            if (flagPF != other.flagPF) return false
            if (!scratchpadData.contentEquals(other.scratchpadData)) return false
            return authPattern.contentEquals(other.authPattern)
        }

        override fun hashCode(): Int {
            var result = endingOffset
            result = 31 * result + flagAA.hashCode()
            result = 31 * result + flagPF.hashCode()
            result = 31 * result + scratchpadData.contentHashCode()
            result = 31 * result + authPattern.contentHashCode()
            return result
        }
    }

    internal fun cmdReadScratchpad(offset: Int): ReadScratchpadResponse {
        Log.d("DS1922Raw","cmdReadScratchpad offset=%d".format(offset))

        val offsetL = (offset and 0xFF).toByte()
        val offsetH = ((offset shr 8) and 0xFF).toByte()
        val dataToTransmit = byteArrayOf(0xAA.toByte())

        return accessDeviceAndRepeatCommand {

            adapter.oneWireWrite(dataToTransmit)

            // Rx: TA (2 bytes), EOS (1 byte), scratchpad data, inverted crc(2 bytes)
            // needs reset pulse after cmd
            val targetAddress = adapter.oneWireRead(2)
            Log.d("DS1922Raw", "TA[0]=%02x, TA[1]=%02x, offsetL=%02x, offsetH=%02x".format(
                targetAddress[0].toUByte().toInt(),
                targetAddress[1].toUByte().toInt(),
                offsetL.toUByte().toInt(),
                offsetH.toUByte().toInt()
            ))
            if (targetAddress[0] != offsetL)
                throw Exception(
                    "bad TA[0] readback, got %02x, expected %02x".format(
                        targetAddress[0].toUInt().toInt(),
                        offsetL.toUInt().toInt()
                    )
                )
            if (targetAddress[1] != offsetH)
                throw Exception(
                    "bad TA[1] readback, got %02x, expected %02x".format(
                        targetAddress[1].toUInt().toInt(),
                        offsetH.toUInt().toInt()
                    )
                )

            val eos = adapter.oneWireRead(1)

            val endingOffset = eos[0].toUInt() and 0x1Fu // inclusive
            val flagAA = eos[0].toUInt() and 0x80u
            val flagPF = eos[0].toUInt() and 0x20u

            val numBytes = 0x20 - (offset and 0x0f)
            val scratchpadData = adapter.oneWireRead(numBytes)

            val dataReceived = targetAddress + eos + scratchpadData
            val invertedCrcRx = adapter.oneWireRead(2)

            val crcRxL = invertedCrcRx[0] xor 0xFF.toByte()
            val crcRxH = invertedCrcRx[1] xor 0xFF.toByte()
            val crcRx = (crcRxL.toUByte().toLong()) + (crcRxH.toUByte().toLong() shl 8)

            val crcExpected = run {
                val crc = CRC16()
                crc.update(dataToTransmit, 0, dataToTransmit.size)
                crc.update(dataReceived, 0, dataReceived.size)
                crc.value
            }

            if (crcRx != crcExpected) {
                // transmission error
                if (crcRxL == 0x00.toByte() && crcRxH == 0x00.toByte())
                    throw TransmissionInternalConflict() // shall trigger re-run

                throw TransmissionErrorException(
                    "READ SCRATCHPAD CRC error: rx %x != tx %x".format(
                        crcRx,
                        crcExpected
                    )
                )
            }

            adapter.oneWireResetBus()

            return@accessDeviceAndRepeatCommand ReadScratchpadResponse(
                endingOffset = endingOffset.toInt(),
                flagAA = flagAA != 0x00u,
                flagPF = flagPF != 0x00u,
                scratchpadData = scratchpadData,
                authPattern = targetAddress + eos
            )
        }
    }

    internal fun cmdCopyScratchpadWithPassword(
        authPattern: ByteArray, // 3 bytes
        password: ByteArray // 8 bytes
    ) {
        if (authPattern.size != 3)
            throw IllegalArgumentException("authPattern needs 3 bytes (ta1, ta2, eos)")
        if (password.size != 8)
            throw IllegalArgumentException("password needs 8 bytes")

        val offsetL = authPattern[0].toUByte().toUInt()
        val offsetH = authPattern[1].toUByte().toUInt()
        val offset = offsetL.toInt() + (offsetH.toInt() shl 8)
        val endingOffset = authPattern[2].toUInt() and 0x1Fu
        val flagAA = authPattern[2].toUInt() and 0x80u

        if (flagAA != 0x00u)
            throw IllegalArgumentException("Flag AA already set")
        if (endingOffset != 0x1Fu)
            throw IllegalArgumentException(
                "Ending offset %02x != 0x1F".format(
                    endingOffset.toInt()
                )
            )

        val numBytes = endingOffset + 1u - (offsetL and 0x0fu) // ending offset is inclusive

        accessDeviceAndRepeatCommand(
            cmd = {
                adapter.oneWireWrite(byteArrayOf(0x99.toByte()) + authPattern + password)

                // wait 2us per byte
                TimeUnit.MICROSECONDS.sleep(2 * numBytes.toLong())

                // wait an extra 100ms just to be sures
                SystemClock.sleep(100)

                // should now be sending alternating zeros and ones
                val read1 = adapter.oneWireReadBit()
                val read2 = adapter.oneWireReadBit()
                if (read1 == read2) {
                    throw TransmissionInternalConflict()
                }
            },
            recoveryCmd = {
                val ret = cmdReadScratchpad(offset)
                if (ret.flagAA)
                    return@accessDeviceAndRepeatCommand Unit
                else
                    return@accessDeviceAndRepeatCommand null // repeat

            }
        )
    }

    open class RegisterData(internal val offset: Int, protected val data : ByteArray ) {

        val size = data.size

        fun contains(from: Int, len: Int): Boolean {
            return (offset <= from && offset + data.size >= from + len)
        }

        operator fun get(index: Int): Byte? {
            if (index < offset || index >= offset + data.size)
                return null
            return data[index - offset]
        }

        operator fun get(indexes: IntRange): ByteArray {
            val ret = ArrayList<Byte?>()

            for (index in indexes)
                ret.add(get(index)!!)

            return ByteArray(ret.size) { ret[it]!! }
        }

        fun toMutableRegisterData(): MutableRegisterData {
            return MutableRegisterData(offset, data.clone())
        }
    }

    class MutableRegisterData(offset: Int, data: ByteArray) : RegisterData(offset, data) {
        var firstChangeIdx: Int? = null
        var lastChangeIdx: Int? = null
        operator fun set(index: Int, value: Byte) {
            if (index < offset || index >= offset + data.size)
                throw ArrayIndexOutOfBoundsException()

            if (data[index - offset] == value)
                return // nothing changed

            data[index - offset] = value

            firstChangeIdx = minOf(index, firstChangeIdx ?: index)
            lastChangeIdx = maxOf(index, lastChangeIdx ?: index)
        }

        fun hasBit(byteIndex: Int, bitIndex: Int): Boolean {
            if (byteIndex < offset || byteIndex >= offset + data.size)
                throw ArrayIndexOutOfBoundsException()
            if (bitIndex < 0 || bitIndex >= 8)
                throw IllegalArgumentException("bit index is 0..7")
            val byte = get(byteIndex)!!.toUByte().toInt()
            val bitMask = 0x01 shl bitIndex

            return (byte and bitMask != 0x00)
        }

        fun setBit(byteIndex: Int, bitIndex: Int, isSet: Boolean) {
            if (byteIndex < offset || byteIndex >= offset + data.size)
                throw ArrayIndexOutOfBoundsException()
            if (bitIndex < 0 || bitIndex >= 8)
                throw IllegalArgumentException("bit index is 0..7")
            val byte = get(byteIndex)!!.toUByte().toInt()
            val bitMask = 0x01 shl bitIndex

            val newByte = (if (isSet) byte or bitMask else byte and bitMask.inv()).toByte()

            set(byteIndex, newByte)
        }

        fun clone(): MutableRegisterData {
            val ret = MutableRegisterData(offset, data.clone())
            ret.firstChangeIdx = firstChangeIdx
            ret.lastChangeIdx = lastChangeIdx
            return ret
        }

    }

    internal val registerConfig = 0x0200
    internal val registerConfigLength = 64 // two pages

    internal val registerDataLog = 0x1000
    internal val registerDataLogLength = 8192

    /**
    * Read iButton internal register memory
    *
    * @param offset iButton memory register
    * @param password password (or random if none configured)
    * @param byteCount Number of bytes to read
  */
    @OptIn(ExperimentalStdlibApi::class)
    internal fun cmdReadMemoryWithPasswordAndCrc(
        offset: Int,
        password: ByteArray,
        numBytesToRead: Int
    ): RegisterData {
        if (password.size != 8)
            throw IllegalArgumentException("password needs 8 bytes")

        val offsetL = (offset and 0xFF).toByte()
        val offsetH = (offset and 0xFF00 shr 8).toByte()
        val command = byteArrayOf(0x69.toByte(), offsetL, offsetH)

        Log.d("DS1922Raw","reading from offset %d num bytes %d".format(
            offset, numBytesToRead
        ))

        return accessDeviceAndRepeatCommand {
            Log.d("DS1922Raw", "TX: %s".format((command + password).toHexString()))
            adapter.oneWireWrite(command + password)

            // read until 32 byte page
            var bytesRx = ByteArray(0)
            val crc = CRC16()
            crc.update(command, 0, command.size)
            while (bytesRx.size < numBytesToRead) {
                val numBytesInPage = 32 - ((offset + bytesRx.size) % 32)
                if (bytesRx.isNotEmpty() && numBytesInPage != 32)
                    throw Exception(
                        "reading next page but bytes in page (%d) != 32".format(
                            numBytesInPage
                        )
                    )

                val rx = adapter.oneWireRead(numBytesInPage)
                Log.d("DS1922Raw", "RX (%d bytes): %s".format(
                    numBytesInPage,
                    rx.toHexString()
                ))
                crc.update(rx, 0, rx.size)
                val invertedCrcRx = adapter.oneWireRead(2)
                Log.d("DS1922Raw", "RX CRC: %s".format(
                    invertedCrcRx.toHexString()
                ))

                val crcRxL = invertedCrcRx[0] xor 0xFF.toByte()
                val crcRxH = invertedCrcRx[1] xor 0xFF.toByte()
                val crcRx = (crcRxL.toUByte().toLong()) + (crcRxH.toUByte().toLong() shl 8)
                if (crcRx != crc.value) {
                    // transmission error
                    if (crcRxL == 0x00.toByte() && crcRxH == 0x00.toByte())
                        throw TransmissionInternalConflict() // shall trigger re-run

                    throw TransmissionErrorException(
                        "READ MEMORY WITH CRC AND PASSWORD error: CRC mismatch: rx %x != tx %x".format(
                            crcRx,
                            crc.value
                        )
                    )
                }
                crc.reset() // subsequent CRC values are not based on command code and address

                val bytesNeeded = minOf(numBytesInPage, numBytesToRead - bytesRx.size)
                Log.d("DS1922Raw","Using %d out of %d bytes".format(
                    bytesNeeded, numBytesInPage
                ))
                bytesRx += rx.slice(0 until bytesNeeded)
            }

            // after end of memory, only ones are read
            // need reset pulse to end

            adapter.oneWireResetBus()

            return@accessDeviceAndRepeatCommand RegisterData(offset, bytesRx)
        }
    }

    private fun maybeReadMemoryWithPasswordAndCrc(
        offset: Int,
        numBytesToRead: Int,
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): ByteArray {
        val rangeNeeded = offset until offset + numBytesToRead

        if (preloadedRegisterData?.contains(offset, numBytesToRead) == true)
            return preloadedRegisterData[rangeNeeded]

        if (!adapter.lock.isHeldByCurrentThread) {
            Log.d("DS1922Raw", "request read: from %d for %d, cache has from %d for %d".format(
                offset, numBytesToRead,
                preloadedRegisterData?.offset,
                preloadedRegisterData?.size
            ))
            throw Exception("Missing cached data but cannot access device as lock is not held")
        }

        val registerData =
            cmdReadMemoryWithPasswordAndCrc(offset, password, numBytesToRead)

        return registerData[rangeNeeded]

    }

    private val registerGeneralStatus = 0x0215

    internal data class RegisterGeneralStatus(val value: Byte) {
        internal val wfta = (value and 0x10) != 0x00.toByte()
        internal val memclr = (value and 0x08) != 0x00.toByte()
        internal val mip = (value and 0x02) != 0x00.toByte()
    }

    internal fun getStatus(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): RegisterGeneralStatus {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerGeneralStatus,
            password = password,
            numBytesToRead = 1,
            preloadedRegisterData = preloadedRegisterData
        )
        return RegisterGeneralStatus(registerData[0])
    }

    // not all of them are supported here but only defined in https://www.analog.com/media/en/technical-documentation/data-sheets/ds1922l-ds1922t.pdf
    // actual support is defined in deviceTypeConfigurations
    internal enum class DS1922DeviceType {
        DS2422, DS1923, DS1922L, DS1922T, DS1922E
    }

    private val registerDeviceConfiguration = 0x0226

    internal fun detectDeviceType(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): DS1922DeviceType {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerDeviceConfiguration,
            password = password,
            numBytesToRead = 1,
            preloadedRegisterData = preloadedRegisterData
        )

        return when (registerData[0].toUInt()) {
            0x00u -> DS1922DeviceType.DS2422
            0x20u -> DS1922DeviceType.DS1923
            0x40u -> DS1922DeviceType.DS1922L
            0x60u -> DS1922DeviceType.DS1922T
            0x80u -> DS1922DeviceType.DS1922E
            else -> throw Exception("Device type not supported")
        }
    }

    data class DeviceTypeConfigurationData(
        var tr1: Int,
        var offset: Int
    )

    private val deviceTypeConfigurations = mapOf(
        DS1922DeviceType.DS1922L to DeviceTypeConfigurationData(60, 41),
        DS1922DeviceType.DS1922T to DeviceTypeConfigurationData(90, 1)
    )

    internal fun getDeviceConfiguration(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): DeviceTypeConfigurationData {
        val deviceType = detectDeviceType(password, preloadedRegisterData)
        return deviceTypeConfigurations[deviceType] ?: throw Exception("Device type not supported")
    }

    private val registerDeviceSamplesCounter = 0x0223
    internal fun getDeviceSamplesCounter(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): Long {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerDeviceSamplesCounter,
            password = password,
            numBytesToRead = 3,
            preloadedRegisterData = preloadedRegisterData
        )

        return registerData[0].toUByte().toLong() + (registerData[1].toUByte().toLong() shl 8) + (registerData[2].toUByte().toLong() shl 16)
    }

    private val registerLatestTemperature = 0x020C
    internal fun getLatestTemperature(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): ByteArray {
        return maybeReadMemoryWithPasswordAndCrc(
            offset = registerLatestTemperature,
            password = password,
            numBytesToRead = 2,
            preloadedRegisterData = preloadedRegisterData
        )
    }

    private val registerRTC = 0x0200
    private val registerMissionTimestamp = 0x0219

    data class DeviceRTC(
        val registerData: ByteArray
    ) {
        var seconds: UByte
        var minutes: UByte
        var is24h: Boolean // mode of RTC
        var hours: UByte // 24-hour format

        var days: UByte
        var months: UByte
        var years: UByte
        var cent: Boolean // is set to true by device when years transition from 99 to 00

        private fun fromBcdByte(b: Byte, mask: Byte): UByte {
            val single = (b and mask and 0x0f).toInt()
            val tens = (b and mask and 0x70.toByte()).toInt() shr 4
            return (single + 10 * tens).toUByte()
        }

        init {
            if (registerData.size != 6)
                throw IllegalArgumentException("invalid DeviceRTC register size")

            seconds = fromBcdByte(registerData[0], 0x7f)
            minutes = fromBcdByte(registerData[1], 0x7f)

            val hoursBase = fromBcdByte(registerData[2], 0x1f)
            is24h = (registerData[2] and 0x40) == 0x00.toByte()
            val hoursOffset = if (is24h) {
                val hoursTwenty = (registerData[2] and 0x20) != 0x00.toByte()
                if (hoursTwenty)
                    20u
                else
                    0u
            } else {
                val hoursPM = (registerData[2] and 0x20) != 0x00.toByte()
                if (hoursPM)
                    12u
                else
                    0u
            }
            hours = (hoursBase + hoursOffset).toUByte()

            days = fromBcdByte(registerData[3], 0x3f)
            months = fromBcdByte(registerData[4], 0x1f)
            cent = (registerData[4] and 0x80.toByte()) != 0x00.toByte()

            years = fromBcdByte(registerData[5], 0xff.toByte())
        }

        @OptIn(ExperimentalStdlibApi::class)
        fun debugHexString(): String {
            return registerData.toHexString()
        }

        private fun bcdByte(number: UByte, maxValue: UByte): Byte {
            if (number > maxValue)
                throw IllegalArgumentException("val %d was bigger than %d for BCD".format(
                    number.toInt(), maxValue.toInt()
                ))
            val single = number % 10u
            val tens = number / 10u
            return (single or (tens shl 4)).toByte()
        }

        fun toRegisterData(): ByteArray {
            registerData[0] = bcdByte(seconds, 59u)
            registerData[1] = bcdByte(minutes, 59u)
            registerData[2] = if (is24h)
                bcdByte(hours, 23u)
            else
                0x40.toByte() or (if (hours >= 12u) 0x20 else 0x00).toByte() or bcdByte((hours % 12u).toUByte(), 11u)

            registerData[3] = bcdByte(days, 31u)
            registerData[4] = bcdByte(months, 12u) or
                    (if (cent) 0x80 else 0).toByte()
            registerData[5] = bcdByte(years, 99u)

            return registerData
        }

        fun isAllZero(): Boolean {
            return registerData.contentEquals(ByteArray(6))
        }

    }

    internal fun getRTC(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): DeviceRTC {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerRTC,
            password = password,
            numBytesToRead = 6,
            preloadedRegisterData = preloadedRegisterData
        )

        return DeviceRTC(registerData)
    }

    internal fun setRTC(
        registerData: MutableRegisterData,
        rtc: DeviceRTC
    )
    {
        val newBytes = rtc.toRegisterData()

        Log.d("DS1922Raw","setRTC writing %02x:%02x:%02x:%02x:%02x:%02x".format(
            newBytes[0].toUByte().toInt(),
            newBytes[1].toUByte().toInt(),
            newBytes[2].toUByte().toInt(),
            newBytes[3].toUByte().toInt(),
            newBytes[4].toUByte().toInt(),
            newBytes[5].toUByte().toInt(),
        ))

        for (i in 0 until 6)
            registerData[registerRTC + i] = newBytes[i]
    }

    internal fun getMissionTimestamp(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): DeviceRTC {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerMissionTimestamp,
            password = password,
            numBytesToRead = 6,
            preloadedRegisterData = preloadedRegisterData
        )

        return DeviceRTC(registerData)
    }

    private val registerSampleRate = 0x0206
    internal fun getSampleRate(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): Int {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerSampleRate,
            password = password,
            numBytesToRead = 2,
            preloadedRegisterData = preloadedRegisterData
        )

        return registerData[0].toUByte().toInt() +
                (registerData[1].toUByte().toInt() shl 8)
    }

    internal fun setSampleRate(
        registerData: MutableRegisterData,
        sampleRate: UInt
    ) {
        if (sampleRate > 0x3fffu)
            throw IllegalArgumentException("Sample rate too big")

        registerData[registerSampleRate] = (sampleRate and 0xffu).toByte()
        registerData[registerSampleRate+1] = ((sampleRate and 0x3f00u) shr 8).toByte()
    }

    private val registerTemperatureAlarm = 0x0208

    data class TemperatureAlarm(
        var lowThreshold: UByte,
        var highThreshold: UByte
    )

    internal fun getTemperatureAlarm(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): TemperatureAlarm {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerTemperatureAlarm,
            password = password,
            numBytesToRead = 2,
            preloadedRegisterData = preloadedRegisterData
        )
        return TemperatureAlarm(
            lowThreshold = registerData[0].toUByte(),
            highThreshold = registerData[1].toUByte()
        )
    }

    internal fun setTemperatureAlarm(
        configurationRegister: MutableRegisterData,
        lowThreshold: UByte?,
        highThreshold: UByte?
    ) {
        if (lowThreshold != null)
            configurationRegister[registerTemperatureAlarm] = lowThreshold.toByte()
        if (highThreshold != null)
            configurationRegister[registerTemperatureAlarm+1] = highThreshold.toByte()
    }

    private val registerTemperatureAlarmEnable = 0x0210

    data class TemperatureAlarmEnable(var value: Byte) {
        fun toByte(): Byte {
            var ret : Byte = 0

            if (etha)
                ret = ret or bitmaskEtha

            if (etla)
                ret = ret or bitmaskEtla

            return ret
        }

        private val bitmaskEtha: Byte = 0x02
        private val bitmaskEtla: Byte = 0x01

        var etha: Boolean = value and bitmaskEtha != 0x00.toByte()
        var etla: Boolean = value and bitmaskEtla != 0x00.toByte()
    }

    internal fun getTemperatureAlarmEnable(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): TemperatureAlarmEnable {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerTemperatureAlarmEnable,
            password = password,
            numBytesToRead = 1,
            preloadedRegisterData = preloadedRegisterData
        )

        return TemperatureAlarmEnable(registerData[0])
    }

    internal fun setTemperatureAlarmEnable(
        configurationRegister: MutableRegisterData,
        ta: TemperatureAlarmEnable
    ) {
        configurationRegister[registerTemperatureAlarmEnable] = ta.toByte()
    }

    private val registerRtcControl = 0x0212

    data class RtcControl(var value: Byte) {
        private val ehssBitmask = 0x02.toByte()
        private val eoscBitmask = 0x01.toByte()


        var ehss: Boolean = value and ehssBitmask != 0x00.toByte()
        var eosc: Boolean = value and eoscBitmask != 0x00.toByte()

        fun toValue(origByte: Byte): Byte {
            var ret = origByte

            ret = if (ehss)
                ret or ehssBitmask
            else
                ret and ehssBitmask.inv()

            ret = if (eosc)
                ret or eoscBitmask
            else
                ret and eoscBitmask.inv()

            return ret
        }
    }

    internal fun getRtcControl(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): RtcControl {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerRtcControl,
            password = password,
            numBytesToRead = 1,
            preloadedRegisterData = preloadedRegisterData
        )

        return RtcControl(registerData[0])
    }

    internal fun setRtcControl(
        registerData: MutableRegisterData,
        rtcControl: RtcControl
    ) {
        val origByte = registerData[registerRtcControl]!!

        val newByte = rtcControl.toValue(origByte)

        registerData[registerRtcControl] = newByte
    }

    private val registerMissionControl = 0x0213

    data class MissionControl(var value: Byte) {
        private val bitmaskSuta: Byte = 0x20
        private val bitmaskRo: Byte = 0x10
        private val bitmaskTlfs: Byte = 0x04
        private val bitmaskEtl: Byte = 0x01


        var suta: Boolean = value and bitmaskSuta != 0x00.toByte()
        var ro: Boolean = value and bitmaskRo != 0x00.toByte()
        var tlfs: Boolean = value and bitmaskTlfs != 0x00.toByte()
        var etl: Boolean = value and bitmaskEtl != 0x00.toByte()

        fun toByte(): Byte {
            var ret: Byte = 0
            if (suta)
                ret = ret or bitmaskSuta
            if (ro)
                ret = ret or bitmaskRo
            if (tlfs)
                ret = ret or bitmaskTlfs
            if (etl)
                ret = ret or bitmaskEtl

            return ret
        }
    }


    internal fun getMissionControl(
        password: ByteArray,
        preloadedRegisterData: RegisterData
    ): MissionControl {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerMissionControl,
            password = password,
            numBytesToRead = 1,
            preloadedRegisterData = preloadedRegisterData
        )

        return MissionControl(registerData[0])
    }

    internal fun setMissionControl(
        configurationRegister: MutableRegisterData,
        missionControl: MissionControl
    ) {
        configurationRegister[registerMissionControl] = missionControl.toByte()
    }

    private val registerAlarmStatus = 0x0214

    data class AlarmStatus(var value: Byte) {
        var bor: Boolean = value and 0x80.toByte() != 0x00.toByte()
        var thf: Boolean = value and 0x02 != 0x00.toByte()
        var tlf: Boolean = value and 0x01 != 0x00.toByte()
    }

    internal fun getAlarmStatus(
        password: ByteArray,
        preloadedRegisterData: RegisterData
    ): AlarmStatus {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerAlarmStatus,
            password = password,
            numBytesToRead = 1,
            preloadedRegisterData = preloadedRegisterData
        )

        return AlarmStatus(registerData[0])
    }

    private val registerStartDelay = 0x0216
    internal fun getStartDelayCounter(
        password: ByteArray,
        preloadedRegisterData: RegisterData
    ): Long {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerStartDelay,
            password = password,
            numBytesToRead = 3,
            preloadedRegisterData = preloadedRegisterData
        )

        return registerData[0].toUByte().toLong()        +(registerData[1].toUByte().toLong() shl 8)        +(registerData[2].toUByte().toLong() shl 16)
    }

    internal fun setStartDelayCounter(
        registerData: MutableRegisterData,
        startDelay: UInt
    ) {
        if (startDelay > 0xffffffu)
            throw java.lang.IllegalArgumentException("start delay too big")

        registerData[registerStartDelay] = (startDelay and 0x0000ffu).toByte()
        registerData[registerStartDelay+1] = ((startDelay and 0x00ff00u) shr 8).toByte()
        registerData[registerStartDelay+2] = ((startDelay and 0xff0000u) shr 16).toByte()
    }

    private val registerMissionSamplesCounter = 0x0220
    internal fun getMissionSamplesCounter(
        password: ByteArray,
        preloadedRegisterData: RegisterData
    ): Long {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerMissionSamplesCounter,
            password = password,
            numBytesToRead = 3,
            preloadedRegisterData = preloadedRegisterData
        )

        return registerData[0].toUByte().toLong()        +(registerData[1].toUByte().toLong() shl 8)        +(registerData[2].toUByte().toLong() shl 16)
    }

    private val registerPwControl = 0x0227
    internal val pwControlPasswordProtected: UByte = 0xAAu
    internal fun getPWControl(
        password: ByteArray,
        preloadedRegisterData: RegisterData
    ): UByte {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerPwControl,
            password = password,
            numBytesToRead = 1,
            preloadedRegisterData = preloadedRegisterData
        )

        return registerData[0].toUByte()
    }
    internal fun setPWControl(
        registerData: MutableRegisterData,
        pwControl: UByte
    ) {
        registerData[registerPwControl] = pwControl.toByte()
    }

    private val registerPasswordFullAccess = 0x0230
    private val registerPasswordReadAccess = 0x0228

    internal fun setPasswords(
        registerData: MutableRegisterData,
        fullAccessPassword: ByteArray,
        readAccessPassword: ByteArray
    ) {
        if (fullAccessPassword.size != 8 || readAccessPassword.size != 8)
            throw IllegalArgumentException("bad password size")

        for (i in 0 until 8)
            registerData[registerPasswordFullAccess + i] = fullAccessPassword[i]

        for (i in 0 until 8)
            registerData[registerPasswordReadAccess + i] = readAccessPassword[i]
    }

    private val registerCalibrationData = 0x0240

    data class CalibrationData(
        var tr2h: UByte,
        var tr2l: UByte,
        var tc2h: UByte,
        var tc2l: UByte,
        var tr3h: UByte,
        var tr3l: UByte,
        var tc3h: UByte,
        var tc3l: UByte
    )

    internal fun getCalibrationData(
        password: ByteArray,
        preloadedRegisterData: RegisterData? = null
    ): CalibrationData {
        val registerData = maybeReadMemoryWithPasswordAndCrc(
            offset = registerCalibrationData,
            password = password,
            numBytesToRead = 8,
            preloadedRegisterData = preloadedRegisterData
        )

        return CalibrationData(
            tr2h = registerData[0].toUByte(),
            tr2l = registerData[1].toUByte(),
            tc2h = registerData[2].toUByte(),
            tc2l = registerData[3].toUByte(),
            tr3h = registerData[4].toUByte(),
            tr3l = registerData[5].toUByte(),
            tc3h = registerData[6].toUByte(),
            tc3l = registerData[7].toUByte()
        )
    }

    internal fun getDataLogEntry(
        offset: Long,
        highResolution: Boolean,
        password: ByteArray,
        preloadedDataLog: RegisterData? = null
    ): ByteArray {

        val registerStart = registerDataLog + offset
        val registerLen = if (highResolution) 2 else 1
        val registerEnd = registerDataLog + registerDataLogLength

        if (registerStart + registerLen > registerEnd)
            throw Exception("No such data log entry.")

        return maybeReadMemoryWithPasswordAndCrc(
            offset = registerStart.toInt(),
            password = password,
            numBytesToRead = registerLen,
            preloadedRegisterData = preloadedDataLog
        )
    }


    internal fun cmdClearMemoryWithPassword(password: ByteArray) {
        if (password.size != 8)
            throw IllegalArgumentException("password needs 8 bytes")

        accessDeviceAndRepeatCommand {
            val dataToTransmit = byteArrayOf(0x96.toByte()) + password + byteArrayOf(0xFF.toByte())
            adapter.oneWireWrite(dataToTransmit)

            if (!getStatus(password).memclr)
                throw TransmissionInternalConflict() // shall trigger re-run

        }
    }

    private val forcedConversationTime: Long = 600 // ms

    /**
     * Sends iButton convert temperature command to the 1-Wire bus.
     * Supports DS1922, DS1923 family.
     *
     * @param romId ROM code of iButton.
     * @return none
     */
    internal fun cmdForcedConversation(password: ByteArray): ByteArray {
        return accessDeviceAndRepeatCommand {
            val oldDevicesSamplesCounter = getDeviceSamplesCounter(password)

            adapter.oneWireWrite(byteArrayOf(0x55, 0xFF.toByte()))

            // Sleep 600ms for temperature conversion time
            SystemClock.sleep(forcedConversationTime)

            val newDevicesSamplesCounter = getDeviceSamplesCounter(password)

            if (newDevicesSamplesCounter <= oldDevicesSamplesCounter)
                throw Exception("force conversion failed - samples counter did not increase")

            return@accessDeviceAndRepeatCommand getLatestTemperature(password)
        }
    }

    internal fun cmdStartMissionWithPassword(
        password: ByteArray
    ) {
        if (password.size != 8)
            throw IllegalArgumentException("password needs 8 bytes")

        accessDeviceAndRepeatCommand {
            val dataToTransmit = byteArrayOf(0xCC.toByte()) + password + byteArrayOf(0xFF.toByte())
            adapter.oneWireWrite(dataToTransmit)

            val status = getStatus(password)
            if (!status.mip || status.memclr)
                throw TransmissionInternalConflict() // shall trigger re-run
        }
    }

    internal fun cmdStopMissionWithPassword(
        password: ByteArray
    ) {
        if (password.size != 8)
            throw IllegalArgumentException("password needs 8 bytes")

        accessDeviceAndRepeatCommand {
            val dataToTransmit = byteArrayOf(0x33.toByte()) + password + byteArrayOf(0xFF.toByte())
            adapter.oneWireWrite(dataToTransmit)

            // check if successful
            // error condition: The General Status register at address 0215h reads
            //                  FFh or the MIP bit is 1 while bits 0, 2, and 5 are 0.
            // recovery: Wait 0.5s, 1-Wire reset, address the device, and
            //           repeat Stop Mission. Perform a 1-Wire reset, address
            //           the device, read the General Status register at
            //           address 0215h, and check the MIP bit. If the MIP bit
            //           is 0, Stop Mission was successful

            if (getStatus(password).mip)
                throw TransmissionInternalConflict() // shall trigger re-run

        }
    }
}