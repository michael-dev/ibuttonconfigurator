package com.mbr.ibuttonconfigurator.usb.adapter

import java.nio.ByteBuffer
import kotlin.experimental.and

class DS2490DeviceStatus(result: ByteBuffer) {
    /**
     * strong pull-up enabled
     */
    val enableSpue: Boolean

    /**
     * dynamic 1-wire bus speed change enabled
     */
    val enableSpce: Boolean

    /**
     * current 1-wire bus speed
     */
    val configSpeed: UByte

    /**
     * current pullup duration
     */
    val configPullUpDuration: UByte

    /**
     * current pulldown slew rate
     */
    val configPullDownSlewRate: UByte

    /**
     * 1-wire low time
     */
    val configOneWireLowTime: UByte

    /**
     * Data Sample Offset / Write-0 Recovery Time
     */
    val configDsow0rt: UByte

    /**
     * strong pullup to 5V is currently active
     */
    val statusSpua: Boolean

    /**
     * if true, DS2490 powered from USB and external sources
     * else all DS2490 power is provided from USB.
     */
    val statusPmod: Boolean

    /**
     * DS2490 is currently halted
     */
    val statusHalt: Boolean

    /**
     * DS2490 is currently idle
     */
    val statusIdle: Boolean

    /**
     * Endpoint 0 FIFO status
     */
    val statusEp0f: Boolean

    /**
     * current command byte1
     */
    val commCommandByte1: Byte

    /**
     * current command byte2
     */
    val commCommandByte2: Byte

    /**
     * number of bytes in comm command fifo (16-byte)
     */
    val commCommandBuffer: UByte

    /**
     * number of bytes in 128-byte fifo to write to 1-wire bus
     */
    val commOneWireDataOutBuffer: UByte

    /**
     * number of bytes in 128-byte fifo read from 1-wire bus
     */
    val commOneWireDataInBuffer: UByte

    init {
        /* state register */
        enableSpue = (result[0x00] and 0x01) != 0x00.toByte()
        enableSpce = (result[0x00] and 0x04) != 0x00.toByte()

        configSpeed = result[0x01].toUByte()
        configPullUpDuration = result[0x02].toUByte()
        // 0x03 reserved
        configPullDownSlewRate = result[0x04].toUByte()
        configOneWireLowTime = result[0x05].toUByte()
        configDsow0rt = result[0x06].toUByte()
        // 0x07 reserved (test)

        statusSpua = (result[0x08] and 0x01.toByte()) != 0x00.toByte()
        // 0x02 undefined
        // 0x04 undefined
        statusPmod = (result[0x08] and 0x08.toByte()) != 0x00.toByte()
        statusHalt = (result[0x08] and 0x10.toByte()) != 0x00.toByte()
        statusIdle = (result[0x08] and 0x20.toByte()) != 0x00.toByte()
        statusEp0f = (result[0x08] and 0x80.toByte()) != 0x00.toByte()

        commCommandByte1 = result[0x09]
        commCommandByte2 = result[0x0A]
        commCommandBuffer = result[0x0B].toUByte()
        commOneWireDataOutBuffer = result[0x0C].toUByte()
        commOneWireDataInBuffer = result[0x0D].toUByte()

        // 0x0E reserved
        // 0x0F reserved

    }

    override fun toString(): String {
        return "DS %s %s - %s %s %s %s %s - %s %s %s idle=%s %s - %s %s %s %s %s".format(
            enableSpue.toString(),
            enableSpce.toString(),

            configSpeed.toString(),
            configPullUpDuration.toString(),
            configPullDownSlewRate.toString(),
            configOneWireLowTime.toString(),
            configDsow0rt.toString(),

            statusSpua.toString(),
            statusPmod.toString(),
            statusHalt.toString(),
            statusIdle.toString(),
            statusEp0f.toString(),

            commCommandByte1.toString(),
            commCommandByte2.toString(),
            commCommandBuffer.toString(),
            commOneWireDataOutBuffer.toString(),
            commOneWireDataInBuffer.toString(),
        )
    }
}

abstract class IDS2490Result {
    abstract val deviceDetected: Boolean
    abstract val searchAccessDeviceUnderrun: Boolean
    abstract val pageIsRedirected: Boolean
    abstract val crcError: Boolean
    abstract val compareError: Boolean
    abstract val alarmingPresencePulse: Boolean
    abstract val shortToGround: Boolean
    abstract val noPresencePulse: Boolean

    fun hasError(): Boolean {
        return crcError || compareError || shortToGround
    }

    fun errorString(): String {
        return "crcError = %s, compareError = %s, shortToGround = %s".format(
            crcError.toString(),
            compareError.toString(),
            shortToGround.toString()
        )
    }

    override fun toString(): String {
        return "R devideDetected=%s - searchAccessDeviceUnderrun=%s %s %s %s %s %s noPresencePulse=%s".format(
            deviceDetected.toString(),

            searchAccessDeviceUnderrun.toString(),
            pageIsRedirected.toString(),
            crcError.toString(),
            compareError.toString(),
            alarmingPresencePulse.toString(),
            shortToGround.toString(),
            noPresencePulse.toString()
        )
    }
}
class DS2490Result(result: Byte): IDS2490Result() {
    /** 1-Wire Device Detect Byte
     */
    private val devDetect_code = 0xA5

    /** EOS A value of 1 indicates that a SEARCH ACCESS with SM = 1 ended sooner than expected
    reporting less ROM ID s than specified in the  number of devices  parameter.
     */
    private val EOS_bitmask = 0x80

    /** RDP A value of 1 indicates that a READ REDIRECT PAGE WITH/CRC encountered a page that is redirected.
     */
    private val RDP_bitmask = 0x40

    /** CRC A value of 1 indicates that a CRC error occurred when executing one of the following
    commands: WRITE SRAM PAGE, WRITE EPROM, READ CRC PROT PAGE, or READ REDIRECT PAGE W/CRC.
     */
    private val CRC_bitmask = 0x20

    /** CMP A value of 1 indicates an error with one of the following: Error when reading the confirmation
    byte with a SET PATH command. The WRITE EPROM command did not program successfully. There was
    a difference between the byte written and then read back with a BYTE I/O command
     */
    private val CMP_bitmask = 0x10

    /** APP A value of 1 indicates that a 1-WIRE RESET revealed an Alarming Presence Pulse.
     */
    private val APP_bitmask = 0x04

    /** SH  A value of 1 indicates that a 1-WIRE RESET revealed a short to the 1-Wire bus or the
    SET PATH command could not successfully connect a branch due to a short.
     */
    private val SH_bitmask = 0x02

    /** NRS A value of 1 indicates an error with one of the following: 1-WIRE RESET did not reveal
    a Presence Pulse. SET PATH command did not get a Presence Pulse from the branch that was
    to be connected. No response from one or more ROM ID bits during a SEARCH ACCESS command.
     */
    private val NRS_bitmask = 0x01

    override val deviceDetected: Boolean
    override val searchAccessDeviceUnderrun: Boolean
    override val pageIsRedirected: Boolean
    override val crcError: Boolean
    override val compareError: Boolean
    override val alarmingPresencePulse: Boolean
    override val shortToGround: Boolean
    override val noPresencePulse: Boolean

    init {
        val intResult = result.toInt()
        deviceDetected = (result == devDetect_code.toByte())
        searchAccessDeviceUnderrun = !deviceDetected && (intResult and EOS_bitmask) != 0
        pageIsRedirected = !deviceDetected && (intResult and RDP_bitmask != 0)
        crcError = !deviceDetected && (intResult and CRC_bitmask != 0)
        compareError = !deviceDetected && (intResult and CMP_bitmask != 0)
        alarmingPresencePulse = !deviceDetected && (intResult and APP_bitmask != 0)
        shortToGround = !deviceDetected && (intResult and SH_bitmask != 0)
        noPresencePulse = !deviceDetected && (intResult and NRS_bitmask != 0)
    }
}

class DS2490CompoundResult(rr: List<DS2490Result>): IDS2490Result() {
    override val deviceDetected = rr.fold(false) { a, b -> a or b.deviceDetected }
    override val searchAccessDeviceUnderrun =
    rr.fold(false) { a, b -> a or b.searchAccessDeviceUnderrun }
    override val pageIsRedirected = rr.fold(false) { a, b -> a or b.pageIsRedirected }
    override val crcError = rr.fold(false) { a, b -> a or b.crcError }
    override val compareError = rr.fold(false) { a, b -> a or b.compareError }
    override val alarmingPresencePulse = rr.fold(false) { a, b -> a or b.alarmingPresencePulse }
    override val shortToGround = rr.fold(false) { a, b -> a or b.shortToGround }
    override val noPresencePulse = rr.fold(false) { a, b -> a or b.noPresencePulse }

    val numResults : Int = rr.size

    override fun toString(): String {
        return "%s - #%d".format(super.toString(), numResults)
    }

}

class DS2490DeviceFeedback(bytesRead: ByteBuffer, numBytesReceived: Int) {
    val status: DS2490DeviceStatus

    private val _results = ArrayList<DS2490Result>()
    val results: List<DS2490Result> = _results

    val compoundResult: DS2490CompoundResult

    init {
        /* state register */
        if (numBytesReceived < 16)
            throw java.lang.IllegalArgumentException("too few bytes")

        status = DS2490DeviceStatus(bytesRead)

        /* result register */
        for (i in 16 until numBytesReceived) {
            _results.add(DS2490Result(bytesRead[i]))
        }

        compoundResult = DS2490CompoundResult(results)
    }

    override fun toString(): String {
        return status.toString() + "\n" + compoundResult.toString()
    }
}