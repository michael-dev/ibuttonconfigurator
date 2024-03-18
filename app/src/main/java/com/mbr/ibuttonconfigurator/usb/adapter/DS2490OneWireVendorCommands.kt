package com.mbr.ibuttonconfigurator.usb.adapter

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.util.Log
import com.mbr.ibuttonconfigurator.ui.AppUsbDevice
import java.nio.ByteBuffer

class DS2490OneWireVendorCommands(
    private val device: AppUsbDevice,
    bAlternateSetting: Int = 0
) {
    private lateinit var epIN: UsbEndpoint
    private lateinit var epOUT: UsbEndpoint
    private lateinit var epINT: UsbEndpoint // interrupt endpoint, async
    private var conn: UsbDeviceConnection

    @Volatile
    var restrictStatusToThread: Thread? = null

    class DS2490AlternateFeature(
        var inOutMaxPacketSize: Int,
        var pollInterval: Long // ms
    )

    private val alternateFeatures = mapOf(
        0 to DS2490AlternateFeature(16, 10),
        1 to DS2490AlternateFeature(64, 10),
        2 to DS2490AlternateFeature(16, 1),
        3 to DS2490AlternateFeature(64, 1),
    )
    val currentAlternateConfig = alternateFeatures[bAlternateSetting]
        ?: throw IllegalArgumentException(
            "alternate setting %d not supported".format(
                bAlternateSetting
            )
        )

    /**
     * Opens the USB device and connects the USB endpoints.
     *
     * Endpoint 0 - default
     * Endpoint 1 - interrupt pipe, status register, completion/error information, attach detect (epInt)
     * Endpoint 2 - bulk data to transmit on 1-wire (epOut)
     * Endpoint 3 - bulk data received on 1-wire (epIn)
     */
    init {
        val usbIf: UsbInterface = findInterface(0, bAlternateSetting)

        conn = device.usbService.openDevice(device.device)
        if (!conn.claimInterface(usbIf, true)) {
            throw USBException(
                "Failed to claim interface id=0, alternate=%d".format(
                    bAlternateSetting
                ), device
            )
        }
        conn.setInterface(usbIf)

        for (i in 0 until usbIf.endpointCount) {
            val ep = usbIf.getEndpoint(i)
            when (ep.type) {
                UsbConstants.USB_ENDPOINT_XFER_BULK -> {
                    when (ep.direction) {
                        UsbConstants.USB_DIR_IN -> epIN = ep
                        UsbConstants.USB_DIR_OUT -> epOUT = ep
                    }
                }

                UsbConstants.USB_ENDPOINT_XFER_INT -> epINT = ep
            }
        }

    }

    @Throws(USBException::class)
    private fun findInterface(id: Int, alternateSetting: Int): UsbInterface {
        for (i in 0 until device.device.interfaceCount) {
            val usbIf = device.device.getInterface(i)
            if (usbIf.id == id && usbIf.alternateSetting == alternateSetting)
                return usbIf
        }
        throw USBException(
            "USB interface with id=%d and alternateSetting=%d not found".format(
                id,
                alternateSetting
            ),
            device
        )
    }

    /* definitions */

    // COMMAND TYPE CODES
    private val CONTROL_CMD = 0x00
    private val COMM_CMD = 0x01
    private val MODE_CMD = 0x02

    // CONTROL COMMAND CODES
    private val CTL_RESET_DEVICE = 0x0000
    private val CTL_START_EXE = 0x0001
    private val CTL_RESUME_EXE = 0x0002
    private val CTL_HALT_EXE_IDLE = 0x0003
    private val CTL_HALT_EXE_DONE = 0x0004
    private val CTL_FLUSH_COMM_CMDS = 0x0007
    private val CTL_FLUSH_RCV_BUFFER = 0x0008
    private val CTL_FLUSH_XMT_BUFFER = 0x0009
    private val CTL_GET_COMM_CMDS = 0x000A

    // COMMUNICATION COMMAND CODES (BASE)
    private val COMM_CMD_setDuration = 0x0012
    private val COMM_CMD_pulse = 0x0030
    private val COMM_CMD_oneWireReset = 0x0042
    private val COMM_CMD_bitIO = 0x0020
    private val COMM_CMD_byteIO = 0x0052
    private val COMM_CMD_blockIO = 0x0074
    private val COMM_CMD_matchAccess = 0x0064
    private val COMM_CMD_readStraight = 0x0080
    private val COMM_CMD_doAndRelease = 0x6092
    private val COMM_CMD_setPath = 0x00a2
    private val COMM_CMD_writeSramPage = 0x00b2
    private val COMM_CMD_readCrcProtPage = 0x00d4
    private val COMM_CMD_readRedirectPageCrc = 0x21e4
    private val COMM_CMD_searchAccess = 0x00f4


    // MODE COMMAND CODES
    private val MOD_PULSE_EN = 0x0000
    private val MOD_SPEED_CHANGE_EN = 0x0001
    private val MOD_1WIRE_SPEED = 0x0002
    private val MOD_STRONG_PU_DURATION = 0x0003
    private val MOD_PULLDOWN_SLEWRATE = 0x0004

    // (Reserved) = 0x0005
    private val MOD_WRITE1_LOWTIME = 0x0006
    private val MOD_DSOW0_TREC = 0x0007

    private val COMM_RTS_bitmask = 0x4000
    private val COMM_CIB_bitmask = 0x4000
    private val COMM_PST_bitmask = 0x4000
    private val COMM_PS_bitmask = 0x4000
    private val COMM_DT_bitmask = 0x2000
    private val COMM_SPU_bitmask = 0x1000
    private val COMM_F_bitmask = 0x0800
    private val COMM_NTF_bitmask = 0x0400
    private val COMM_ICP_bitmask = 0x0200
    private val COMM_RST_bitmask = 0x0100
    private val COMM_SE_bitmask = 0x0008
    private val COMM_R_bitmask = 0x0008
    private val COMM_SM_bitmask = 0x0008
    private val COMM_CH_bitmask = 0x0008
    private val COMM_D_bitmask = 0x0008
    private val COMM_IM_bitmask = 0x0001

    /*
     * EP0 : USB CORE COMMANDS
     *  - SET_ADDRESS
     *  - SET_CONFIGURATION : 0 - unconfigured, 1 - configured
     *  - GET_CONFIGURATION
     *  - GET_DESCIPTOR : DEVICE | CONFIGURATION descriptor
     *  - GET_INTERFACE : only 0 allowed
     *  - SET_INTERFACE
     *  - SET_FEATURE : only DEVICE_REMOTE_WAKE-UP
     *  - CLEAR_FEATURE
     *  - GET_STATUS : on USB 1.1 always zero
     */

// not implemented here

    // keep last command send to better debug errors

    data class LastControlCommand(
        var dirIn: Boolean,
        var request: Int,
        var value: Int,
        var index: Int,
        var buffer: ByteArray?,
        var length: Int,
        var timeout: Int,
        val stackTrace: String,
        val valueHex: String
    )

    private var _lastCommand: LastControlCommand? = null
    internal val lastCommand: LastControlCommand?
        get() { return _lastCommand }

    private val defaultUsbTimeout = 1000 // ms

    private fun controlTransferRemember(
        dirIn: Boolean = false,
        request: Int,
        value: Int,
        index: Int = 0,
        buffer: ByteArray? = null,
        length: Int = 0,
        timeout: Int = defaultUsbTimeout
    ): Int {
        _lastCommand = LastControlCommand(
            dirIn = dirIn,
            request = request,
            value = value,
            valueHex = "%04x".format(value),
            index = index,
            buffer = buffer,
            length = length,
            timeout = timeout,
            stackTrace = Throwable().stackTraceToString()
        )

        val ret = conn.controlTransfer(
            /* requestType = */ UsbConstants.USB_TYPE_VENDOR or if (dirIn) UsbConstants.USB_DIR_IN else UsbConstants.USB_DIR_OUT,
            /* request = */ request,
            /* value = */ value,
            /* index = */ index,
            /* buffer = */ buffer,
            /* length = */ length,
            /* timeout = */ timeout
        )

        if (ret < 0) {
            throw USBException("ControlTransfer failed with return code %d, command was %s".format(
                ret,
                _lastCommand
            ), device)
        }

        return ret
    }

    /** hardware reset equivalent to power-on reset
     * Clear all buffer, load default to mode control
     */
    fun ctlResetDevice() {
        Log.d("DS2490V", "ctlResetDevice")
        controlTransferRemember(
            request = CONTROL_CMD,
            value = CTL_RESET_DEVICE
        )
    }

    /**
     * Start execution of commands with IM=0
     */
    fun ctlStartExecution() {
        Log.d("DS2490V", "ctlStartExecution")

        controlTransferRemember(
            request = CONTROL_CMD,
            value = CTL_START_EXE
        )
    }

    /**
     * Resume after HALT
     */
    fun ctlResumeExecution() {
        Log.d("DS2490V", "ctlResumeExecution")

        controlTransferRemember(
            request = CONTROL_CMD,
            value = CTL_RESUME_EXE
        )
    }

    /**
     * Halt execution of commands when bus reaching idle
     */
    fun ctlHaltExecutionWhenIdle() {
        Log.d("DS2490V", "ctlHaltExecutionWhenIdle")

        controlTransferRemember(
            request = CONTROL_CMD,
            value = CTL_HALT_EXE_IDLE
        )
    }

    /**
     * Halt execution of commands after completing current command
     */
    fun ctlHaltExecutionWhenDone() {
        Log.d("DS2490V", "ctlHaltExecutionWhenDone")

        controlTransferRemember(
            request = CONTROL_CMD,
            value = CTL_HALT_EXE_DONE
        )
    }

    /**
     * Clear all not yet executed commands from command (default) FIFO
     * Chip needs to be in halted state
     */
    fun ctlFlushCommCmds() {
        Log.d("DS2490V", "ctlFlushCommCmds")

        controlTransferRemember(
            request = CONTROL_CMD,
            value = CTL_FLUSH_COMM_CMDS
        )
    }

    /**
     * Clear EP3 data (read from wire)
     * Chip needs to be in halted state
     */
    fun ctlFlushDataRcvBuffer() {
        Log.d("DS2490V", "ctlFlushDataRcvBuffer")

        controlTransferRemember(
            request = CONTROL_CMD,
            value = CTL_FLUSH_RCV_BUFFER
        )
    }

    /**
     * Clear EP2 data (write to wire)
     * Chip needs to be in halted state
     */
    fun ctlFlushDataXmtBuffer() {
        Log.d("DS2490V", "ctlFlushDataXmtBuffer")

        controlTransferRemember(
            request = CONTROL_CMD,
            value = CTL_FLUSH_XMT_BUFFER
        )
    }

    /**
     * read commands not yet executed (and clear those transmitted back)
     * Chip needs to be in halted state
     *
     * Result written to EP0
     * Provide an appropriate buffer and size! (needs control transfer data phase)
     */
    fun ctlGetCommCmds(length: Int) {
        Log.d("DS2490V", "ctlGetCommCmds")

        controlTransferRemember(
            dirIn = true,
            request = CONTROL_CMD,
            value = CTL_GET_COMM_CMDS,
            length = length
        )
    }

    /**
     * EP0 : Vendor specific commands: Communication
     * 1-wire data and command communication
     */
    enum class COMM_RESULT_HANDLING {
        ONERROR, ALWAYS
    }

    private fun commDefaultValueFlags(
        base: Int,
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean, // is macro
        im: Boolean
    ): ArrayList<Int> {
        val flags = ArrayList<Int>()
        flags.add(base)
        when (resultHandling) {
            COMM_RESULT_HANDLING.ONERROR -> {}
            COMM_RESULT_HANDLING.ALWAYS -> flags.add(COMM_NTF_bitmask) // will do generate if icp
        }
        if (icp)
            flags.add(COMM_ICP_bitmask)
        if (im)
            flags.add(COMM_IM_bitmask)

        return flags
    }

    private fun commFlagsToValue(flags: List<Int>): Int {
        return flags.fold(0) { acct, curr -> acct or curr } // or them all together
    }

    /**
     * Set State Register pulse duration value for the strong pull-up.
     */
    fun commSetDuration(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        newDuration: UByte
    ) {
        Log.d("DS2490V", "commSetDuration")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_setDuration,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags),
            index = 0x0000 or newDuration.toInt() // upper byte is zero
        )
    }

    /**
     * Generate strong pull-up to 5V for extra power supply to iButton
     */
    fun commPulse(resultHandling: COMM_RESULT_HANDLING, icp: Boolean, im: Boolean, f: Boolean) {
        Log.d("DS2490V", "commPulse")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_pulse,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (f)
            flags.add(COMM_F_bitmask)

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags)
        )
    }


    /**
     * Write reset pule to bus and optionally change the 1-Wire speed.
     *
     * @param se change speed
     */
    fun commOneWireReset(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        f: Boolean,
        pst: Boolean,
        se: Boolean,
        speed: UByte?
    ) {
        Log.d("DS2490V", "commOneWireReset")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_oneWireReset,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (f)
            flags.add(COMM_F_bitmask)
        if (pst)
            flags.add(COMM_PST_bitmask)
        if (se)
            flags.add(COMM_SE_bitmask)

        val wIndex = if (se)
            speed?.toInt() ?: throw IllegalArgumentException("speed cannot be null with se")
        else
            0x0000

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags),
            index = wIndex
        )
    }

    /**
     * Read and write one bit
     *
     * Output: Written (1 byte for 1 bit) to EP3 if ICP=0
     */

    fun commBitIO(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        cib: Boolean,
        spu: Boolean,
        d: Boolean
    ) {
        Log.d("DS2490V", "commBitIO")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_bitIO,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (cib)
            flags.add(COMM_CIB_bitmask)
        if (spu)
            flags.add(COMM_SPU_bitmask)
        if (d)
            flags.add(COMM_D_bitmask)

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags)
        )
    }


    /**
     * Read & Write 1 Byte
     * Set Data to 0xFF for read
     */
    fun commByteIO(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        spu: Boolean,
        d: Byte
    ) {
        Log.d("DS2490V", "commByteIO")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_byteIO,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (spu)
            flags.add(COMM_SPU_bitmask)

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags),
            index = d.toInt()
        )
    }

    /**
     * Read and write 1-Wire, data in/out EP2 (to be written)/EP3 (read from 1-Wire)
     * For reading, all input data should by 0xFF.
     * Do not let the buffer under-run!
     */
    fun commBlockIO(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        spu: Boolean,
        rst: Boolean,
        size: Int
    ) {
        Log.d("DS2490V", "commBlockIO")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_blockIO,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (spu)
            flags.add(COMM_SPU_bitmask)
        if (rst)
            flags.add(COMM_RST_bitmask)

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags),
            index = size
        )
    }

    /**
     * Match device on active bus section
     *
     * EP2: pre-filled with 8-bytes target rom id
     *
     * @param se speed change
     * @param overdrive if set, use Overdrive Match ROM instead of Match ROM
     */
    fun commMatchAccess(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        se: Boolean,
        rst: Boolean,
        speed: UByte?,
        overdrive: Boolean
    ) {
        Log.d("DS2490V", "commMatchAccess")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_matchAccess,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (se)
            flags.add(COMM_SE_bitmask)
        if (rst)
            flags.add(COMM_RST_bitmask)

        val wIndexUpper = if (se)
            (speed?.toInt() ?: throw IllegalArgumentException("speed cannot be null with se"))
        else
            0x00

        val wIndexLower = if (overdrive) 0x69 else 0x55

        val wIndex = (wIndexUpper shl 8) or wIndexLower

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags),
            index = wIndex
        )
    }


    /**
     * transmit preamble (from EP2) and then read back dataSize bytes (to EP3)
     */
    fun commReadStraight(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        rst: Boolean,
        preambleSize: UByte,
        dataSize: UShort
    ) {
        Log.d("DS2490V", "commReadStraight")

        // warning: uses non-default flag values
        val flags = ArrayList<Int>()
        flags.add(COMM_CMD_readStraight)

        when (resultHandling) {
            COMM_RESULT_HANDLING.ONERROR -> {}
            COMM_RESULT_HANDLING.ALWAYS -> flags.add(0x08) // will do generate if icp
        }
        if (icp)
            flags.add(0x04)
        if (im)
            flags.add(0x01)
        if (rst)
            flags.add(0x02)

        val wValue = (preambleSize.toInt() shl 8) or commFlagsToValue(flags)

        controlTransferRemember(
            request = COMM_CMD,
            value = wValue,
            index = dataSize.toInt(),
            length = preambleSize.toInt()
        )
    }

    /** DO & RELEASE command */
    fun commDoAndRelease(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        f: Boolean,
        r: Boolean,
        spu: Boolean,
        preambleSize: UByte
    ) {
        Log.d("DS2490V", "commDoAndRelease")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_doAndRelease,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (f)
            flags.add(COMM_F_bitmask)
        if (r)
            flags.add(COMM_R_bitmask)
        if (spu)
            flags.add(COMM_SPU_bitmask)

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags),
            index = preambleSize.toInt()
        )
    }

    /**
     * enable couplers needed for access to target device
     *
     * EP2: data for coupler
     * EP3: 1-byte indicating number of couplers activated
     */
    fun commSetPath(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        f: Boolean,
        rst: Boolean,
        size: UByte
    ) {
        Log.d("DS2490V", "commSetPath")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_setPath,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (f)
            flags.add(COMM_F_bitmask)
        if (rst)
            flags.add(COMM_RST_bitmask)


        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags),
            index = size.toInt()
        )
    }

    /**
     * read SRAM page
     */
    fun commWriteSramPage(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        f: Boolean,
        ps: Boolean,
        dt: Boolean,
        size: UByte
    ) {
        Log.d("DS2490V", "commWriteSramPage")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_writeSramPage,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (f)
            flags.add(COMM_F_bitmask)
        if (ps)
            flags.add(COMM_PS_bitmask)
        if (dt)
            flags.add(COMM_DT_bitmask)

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags),
            index = size.toInt()
        )
    }

    /**
     * Read Root Page
     */
    fun commReadCrcRootPage(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        f: Boolean,
        ps: Boolean,
        dt: Boolean,
        numPages: UByte,
        logPageSize: UByte
    ) {
        Log.d("DS2490V", "commReadCrcRootPage")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_readCrcProtPage,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (f)
            flags.add(COMM_F_bitmask)
        if (ps)
            flags.add(COMM_PS_bitmask)
        if (dt)
            flags.add(COMM_DT_bitmask)

        val wIndex = (numPages.toInt() shl 8) or logPageSize.toInt()

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags),
            index = wIndex
        )
    }

    /**
     * EP2: ROM ID
     * EP3: data read back
     *
     * @param ch Follow redirects
     */
    fun commReadRedirectPage(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        f: Boolean,
        ch: Boolean,
        pageNumber: UByte,
        logPageSize: UByte
    ) {
        Log.d("DS2490V", "commReadRedirectPage")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_readRedirectPageCrc,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (f)
            flags.add(COMM_F_bitmask)
        if (ch)
            flags.add(COMM_CH_bitmask)

        val wIndex = (pageNumber.toInt() shl 8) or logPageSize.toInt()

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags),
            index = wIndex
        )
    }

    /**
     * search devices on active segment
     * @param sm type of search (false: exact match; true: ROM-ID where to start given)
     * @param rts discrepancy information reporting
     * @param f clear communication buffer and ep2 and ep3 on error
     * @param maxDevices maximum number of devices to be discovery, 0x00 for all
     * @param conditionalSearch if set, only devices in alarm state are searched
     *
     * EP3: ROM-IDs (blocks of 8 bytes). Extra 8 byte discrepancy info at the end if more devices are available
     */
    fun commSearchAccess(
        resultHandling: COMM_RESULT_HANDLING,
        icp: Boolean,
        im: Boolean,
        rst: Boolean,
        f: Boolean,
        sm: Boolean,
        rts: Boolean,
        maxDevices: UByte,
        conditionalSearch: Boolean
    ) {
        Log.d("DS2490V", "commSearchAccess")

        val flags = commDefaultValueFlags(
            base = COMM_CMD_searchAccess,
            resultHandling = resultHandling,
            icp = icp,
            im = im
        )
        if (f)
            flags.add(COMM_F_bitmask)
        if (rst)
            flags.add(COMM_RST_bitmask)
        if (sm)
            flags.add(COMM_SM_bitmask)
        if (rts)
            flags.add(COMM_RTS_bitmask)

        val searchModeByte = if (conditionalSearch) 0xEC else 0xF0
        val wIndex = (maxDevices.toInt() shl 8) or searchModeByte

        controlTransferRemember(
            request = COMM_CMD,
            value = commFlagsToValue(flags),
            index = wIndex
        )
    }

    /**
     * DS2490 1-Wire Bulk Read
     *
     * @param byteCount Number of bytes to read
     * @return Byte array read from the 1-Wire bus
     */
    fun oneWireReadEpIn(byteCount: Int): ByteArray {
        Log.d("DS2490V", "oneWireReadEpIn")

        val tempData = ByteArray(byteCount)
        val numBytes = conn.bulkTransfer(epIN, tempData, byteCount, defaultUsbTimeout)
        if (numBytes < 0)
            throw USBException("USB transfer failed: %d".format(numBytes), device)
        return tempData.copyOf(numBytes)
    }

    /**
     * DS2490 1-Wire Bulk Write
     *
     * @param data Byte array of data to be written to the 1-Wire bus
     */
    fun oneWireWriteEpOut(data: ByteArray) {
        Log.d("DS2490V", "oneWireWriteEpOut")

        val numBytes = conn.bulkTransfer(epOUT, data, data.size, defaultUsbTimeout)
        if (numBytes < 0)
            throw USBException("USB transfer failed", device)
        if (numBytes != data.size)
            throw USBException("USB transfer did not complete: %d returned, %d expected".format(
                numBytes,
                data.size
            ), device)
    }


    /**
     * EP0 : Vendor specific commands: Mode
     * 1-wire characteristics e.g. slew rate, low time, strong pullup
     */

    /**
     *  enable pulse
     *
     *  @param spue enable (true) or disable (false) strong pull-up
     *
     */
    fun modeEnablePulse(spue: Boolean) {
        Log.d("DS2490V", "modeEnablePulse")

        controlTransferRemember(
            request = MODE_CMD,
            value = MOD_PULSE_EN,
            index = if (spue) 0x0002 else 0x0000
        )
    }

    /**
     * enable or disable speed change
     */
    fun modeEnableSpeedChange(speedChange: Boolean) {
        Log.d("DS2490V", "modeEnableSpeedChange")

        controlTransferRemember(
            request = MODE_CMD,
            value = MOD_SPEED_CHANGE_EN,
            index = if (speedChange) 0x0001 else 0x0000
        )
    }

    /**
     * set one wire speed
     */
    fun modeOneWireSpeed(speed: UByte) {
        Log.d("DS2490V", "modeOneWireSpeed")

        controlTransferRemember(
            request = MODE_CMD,
            value = MOD_1WIRE_SPEED,
            index = speed.toInt()
        )
    }

    /**
     * set strong pull-up duration
     */
    fun modeStrongPullupDuration(duration: UByte) {
        Log.d("DS2490V", "modeStrongPullupDuration")

        controlTransferRemember(
            request = MODE_CMD,
            value = MOD_STRONG_PU_DURATION,
            index = duration.toInt()
        )
    }

    /**
     * set pull-down slew rate
     */
    fun modePulldownSlewRate(slewRate: UByte) {
        Log.d("DS2490V", "modePulldownSlewRate")

        controlTransferRemember(
            request = MODE_CMD,
            value = MOD_PULLDOWN_SLEWRATE,
            index = slewRate.toInt()
        )
    }


    /**
     * set 1-wire low time
     */
    fun modeOneWireLowTime(lowTimeDuration: UByte) {
        Log.d("DS2490V", "modeOneWireLowTime")

        controlTransferRemember(
            request = MODE_CMD,
            value = MOD_WRITE1_LOWTIME,
            index = lowTimeDuration.toInt()
        )
    }

    /**
     * set dsow0 recovery time
     */
    fun modeDSOW0RecoveryTime(recoveryTime: UByte) {
        Log.d("DS2490V", "modeDSOW0RecoveryTime")

        controlTransferRemember(
            request = MODE_CMD,
            value = MOD_DSOW0_TREC,
            index = recoveryTime.toInt()
        )
    }

    /* access state register */
    /**
    * Reads the state registers of the DS2490 from 0x00 to 0x1F
    *
    * @return Byte array of register contents
    */
    fun getDeviceFeedback(): DS2490DeviceFeedback {
        // no debugging, would be too often

        if (restrictStatusToThread != null &&
            Thread.currentThread() != restrictStatusToThread)
            throw USBException("getDeviceFeedback() called from non-monitoring thread, possible loss of results returned", device)

        val regData = ByteBuffer.allocate(32)

        val usbRequest = UsbRequest()
        usbRequest.initialize(conn, epINT)
        if (!usbRequest.queue(regData)) {
            usbRequest.close()
            throw USBException(
                "failed to queue receiving of data",
                device
            )
        }
        if (conn.requestWait() != usbRequest) {
            usbRequest.close()

            throw USBException(
                "some other request returned",
                device
            )
        }
        usbRequest.close()

        val numBytesReceived = regData.position()

        if (numBytesReceived < 16)
            throw USBException("too few bytes", device)

        return DS2490DeviceFeedback(regData, numBytesReceived)
    }
}