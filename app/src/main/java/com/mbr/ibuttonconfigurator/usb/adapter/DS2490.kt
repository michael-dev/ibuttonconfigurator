package com.mbr.ibuttonconfigurator.usb.adapter

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.mbr.ibuttonconfigurator.helper.MutableLiveDataWithLifecycle
import com.mbr.ibuttonconfigurator.ui.AppUsbDevice
import java.lang.Thread.sleep
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

// See https://www.analog.com/media/en/technical-documentation/data-sheets/DS2490.pdf

class DS2490(
    device: AppUsbDevice
) : OneWireAdapterBaseUsb(device) {
    companion object {
        const val VID = 0x04fa
        const val PID = 0x2490
    }

    private val TAG = "DS2490"
    private val debugTraceState = false

    private val bAlternateSetting = 0
    private var oneWireDevice = DS2490OneWireVendorCommands(device, bAlternateSetting)

    /**
     * device detected status register
     */

    /** background monitoring for status register */

    private sealed class DS2490Feedback(val timestamp: Long) {
        fun isFresh(minimumFormFresh: Long): Boolean {
            val elapsed = timestamp - minimumFormFresh

            return (elapsed >= 0 && elapsed < Long.MAX_VALUE / 2) ||
                    (elapsed < Long.MIN_VALUE / 2)
        }
    }

    private class DS2490FeedbackFromDevice(
        val deviceFeedback: DS2490DeviceFeedback,
        timestamp: Long
    ) :
        DS2490Feedback(timestamp)

    private class DS2490FeedbackUsbException(val usbException: USBException, timestamp: Long) :
        DS2490Feedback(
            timestamp
        )

    private val onNewDeviceFeedback = object : MutableLiveData<DS2490Feedback>() {
        private val deviceFeedbackCounter = AtomicLong(0)

        fun getCurrentFeedbackCounter(): Long {
            return deviceFeedbackCounter.get()
        }

        override fun onActive() {
            super.onActive()
            startMonitoring()
        }

        override fun onInactive() {
            super.onInactive()
            stopMonitoring()
        }

        private fun startMonitoring() {
            if (oneWireDevice.restrictStatusToThread != null)
                return // already running

            oneWireDevice.restrictStatusToThread = thread {
                while (true) {
                    val c = deviceFeedbackCounter.incrementAndGet()

                    try {
                        val state = oneWireDevice.getDeviceFeedback()

                        if (debugTraceState)
                            Log.d(
                                "DS2490::onNewDeviceFeedback",
                                "new state: %s at %d".format(state.toString(), c)
                            )

                        this.postValue(
                            DS2490FeedbackFromDevice(state, c)
                        )
                        if (state.status.statusEp0f) {
                            resetDeviceWithLock()
                        }
                    } catch (e: USBException) {
                        // USB communication failed
                        Log.d(
                            "DS2490::onNewDeviceFeedback",
                            "new error: %s at %d".format(e.toString(), c)
                        )

                        this.postValue(DS2490FeedbackUsbException(e, c))
                        break
                    }

                    try {
                        // stick to pollInterval or strange output will be read
                        sleep(oneWireDevice.currentAlternateConfig.pollInterval)
                    } catch (e: InterruptedException) {
                        // we have been interrupted, so stop immediately
                        break
                    }
                }
            }
        }

        private fun resetDeviceWithLock() {
            lock.withLock {
                oneWireDevice.ctlResetDevice()
            }
        }

        private fun stopMonitoring() {
            if (oneWireDevice.restrictStatusToThread == null)
                return // already stopped
            oneWireDevice.restrictStatusToThread!!.interrupt()
            oneWireDevice.restrictStatusToThread!!.join()
            oneWireDevice.restrictStatusToThread = null
        }
    }

    /** background monitoring for device detected */
    override val onDeviceDetected: LiveData<Unit> = object : MutableLiveDataWithLifecycle<Unit>() {
        init {
            onNewDeviceFeedback.observe(this) {
                // only called if state is STARTED (or RESUMED)
                val fb = (it as? DS2490FeedbackFromDevice)?.deviceFeedback
                if (fb?.compoundResult?.deviceDetected == true) {
                    this.postValue(Unit)
                }
            }
        }
    }

    override val onDeviceFailure: LiveData<out Exception> =
        object : MutableLiveDataWithLifecycle<USBException>() {
            init {
                onNewDeviceFeedback.observe(this) {
                    // only called if state is STARTED (or RESUMED)
                    val e = (it as? DS2490FeedbackUsbException)?.usbException
                    if (e != null) {
                        this.postValue(e)
                    }
                }
            }
        }

    private val _onDeviceDebug = MutableLiveDataWithLifecycle<USBException>()
    override val onDeviceDebug: LiveData<out USBException> = _onDeviceDebug

    private inner class DS2490StateAndResultCollector(startCollecting: Boolean = false) {
        @Volatile
        private var collectedState: DS2490DeviceFeedback? = null

        @Volatile
        private var collectedResults = ArrayList<DS2490Result>()
        private var stopped = CompletableFuture.completedFuture(Unit)

        @Volatile
        private var stopOnState = false

        @Volatile
        private var stopOnIdle = false

        @Volatile
        private var stopOnResult = false

        @Volatile
        private var monitoringCounter: Long = 0

        @Volatile
        private var myObserver: Observer<DS2490Feedback>? = null

        init {
            clearStop()
            if (startCollecting)
                startIfNotRunning()
        }

        private fun clearStop() {
            stopOnState = false
            stopOnIdle = false
            stopOnResult = false
        }

        private fun startIfNotRunning() {
            if (!lock.isLocked)
                throw USBException("start monitoring for results without lock", device)

            if (isRunning())
                return // already running
            stopped = CompletableFuture()
            collectedState = null
            myObserver = null

            Handler(Looper.getMainLooper()).post {
                // observers can only be registered from main thread ...
                myObserver = object : Observer<DS2490Feedback> {

                    override fun onChanged(value: DS2490Feedback) {
                        val fb = (value as? DS2490FeedbackFromDevice) ?: return

                        var hasResultOtherThanDeviceDetected = false
                        for (r in fb.deviceFeedback.results) {
                            if (r.deviceDetected)
                                continue

                            collectedResults.add(r)
                            hasResultOtherThanDeviceDetected = true
                        }

                        collectedState = fb.deviceFeedback

                        val isFresh = value.isFresh(monitoringCounter)
                        if (isFresh && (stopOnState || stopOnResult)) {
                            if (debugTraceState) Log.d(
                                "DS2490StateAndResultCollector",
                                "skipping state -- too old"
                            )
                        }

                        if ((stopOnState && isFresh) ||
                            (stopOnIdle && fb.deviceFeedback.status.statusIdle && isFresh) ||
                            (stopOnResult && hasResultOtherThanDeviceDetected)
                        ) {
                            if (debugTraceState) Log.d(
                                "DS2490StateAndResultCollector",
                                "stopping with state %s and results %s at %d".format(
                                    fb.deviceFeedback.toString(),
                                    collectedResults.toString(),
                                    value.timestamp
                                )
                            )

                            onNewDeviceFeedback.removeObserver(this)
                            stopped.complete(Unit)
                        }
                    }
                }

                onNewDeviceFeedback.observeForever(myObserver!!)
            }

            // wait for callback to finish
            while (myObserver == null) {
                sleep(100)
            }
        }

        private fun isRunning(): Boolean {
            return !(stopped.isDone || stopped.isCompletedExceptionally || stopped.isCancelled)
        }

        private fun stopMonitoring() {
            Handler(Looper.getMainLooper()).post {
                if (myObserver == null)
                    return@post
                onNewDeviceFeedback.removeObserver(myObserver!!)
                myObserver = null
            }

            // wait for callback to finish
            while (myObserver != null) {
                sleep(100)
            }

        }

        private fun waitForMonitoringToComplete(
            timeoutMillis: Long = defaultTimeout,
        ) {
            try {
                stopped.get(timeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                stopMonitoring()
                throw USBException("waiting for idle took too long, device busy with command %02x, %02x, pending %d bytes in, %d bytes out, last command was %s".format(
                    collectedState?.status?.commCommandByte1,
                    collectedState?.status?.commCommandByte2,
                    collectedState?.status?.commOneWireDataInBuffer?.toInt() ?: -1,
                    collectedState?.status?.commOneWireDataOutBuffer?.toInt() ?: -1,
                    oneWireDevice.lastCommand,

                ), device)
            }

            if (isRunning())
                throw USBException("Did do not stop when returning stopped", device)
        }

        fun getState(): DS2490DeviceFeedback {
            clearStop()
            monitoringCounter = onNewDeviceFeedback.getCurrentFeedbackCounter()

            startIfNotRunning()

            stopOnState = true

            waitForMonitoringToComplete()

            return collectedState!!
        }

        val defaultTimeout: Long = 1000 // 1s

        fun waitUntilIdleOrResult(
            timeoutMillis: Long? = null,
            stopOnIdle: Boolean = true,
            stopOnResult: Boolean = true,
            checkError: Boolean = true
        ) {
            if (!stopOnIdle && !stopOnResult)
                throw IllegalArgumentException("neither stopOnIdle nor stopOnResult set")

            clearStop()
            monitoringCounter = onNewDeviceFeedback.getCurrentFeedbackCounter()

            startIfNotRunning()

            this.stopOnIdle = stopOnIdle
            this.stopOnResult = stopOnResult

            waitForMonitoringToComplete(timeoutMillis ?: defaultTimeout)

            if (checkError) {
                val compoundResult = DS2490CompoundResult(collectedResults)
                if (compoundResult.hasError())
                    throw USBException(
                        "Error condition %s".format(compoundResult.errorString()),
                        device
                    )
            }
        }

        fun getResults(): List<DS2490Result> {
            if (isRunning())
                throw IllegalStateException("cannot access result while monitoring")
            return collectedResults
        }

        fun getCompoundResults(): DS2490CompoundResult {
            return DS2490CompoundResult(getResults())
        }

        fun getLastState(): DS2490DeviceStatus {
            if (isRunning())
                throw IllegalStateException("cannot access state while monitoring")
            return collectedState!!.status
        }

    }

    private fun getState(): DS2490DeviceFeedback {
        return DS2490StateAndResultCollector().getState()
    }

    private fun waitUntilIdle(
        timeoutMillis: Long? = null,
        stopOnIdle: Boolean = true,
    ) {
        if (!this.lock.isHeldByCurrentThread)
            throw USBException("waitUntilIdle called without lock", device)
        
        return DS2490StateAndResultCollector().waitUntilIdleOrResult(
            timeoutMillis = timeoutMillis,
            stopOnIdle = stopOnIdle,
            stopOnResult = false,
            checkError = false // no reasonable as result not captured
        )
    }

    private fun listenForResult(): DS2490StateAndResultCollector {
        return DS2490StateAndResultCollector(startCollecting = true)
    }

    private fun runAndWaitUntilIdleWithResult(
        requireResults: Boolean = false,
        function: () -> Unit
    ): Pair<DS2490CompoundResult, DS2490DeviceStatus> {
        if (!this.lock.isHeldByCurrentThread)
            throw USBException("runAndWaitUntilIdleWithResult called without lock", device)
        
        val resultCollector = listenForResult()        
        function()
        resultCollector.waitUntilIdleOrResult(
            stopOnResult = requireResults,
            stopOnIdle = !requireResults
        )
        // ensure idle
        resultCollector.waitUntilIdleOrResult(
            stopOnResult = false
        )

        return Pair(resultCollector.getCompoundResults(), resultCollector.getLastState())
    }

    /**
     * DS2490 Reset Device
     */
    override fun oneWireResetDevice() {
        Log.d(TAG, "oneWireResetDevice")

        val (_, s) = runAndWaitUntilIdleWithResult {
            oneWireDevice.ctlResetDevice()
        }

        if (s.commOneWireDataInBuffer.toInt() != 0 ||
            s.commOneWireDataOutBuffer.toInt() != 0
        )
            throw USBException(
                "device did not flush its buffer: in=%d out=%d".format(
                    s.commOneWireDataInBuffer.toInt(),
                    s.commOneWireDataOutBuffer.toInt()
                ), device
            )
    }


    override fun oneWireResetBus() {
        Log.d(TAG, "oneWireResetBus")

        // oneWireReset = 0x0042
        // 0xC4B = oneWireReset + 0x0C09
        // 0x0C09 = 0x0001 + 0x0008 + 0x0800 + 0x0400
        //          im         se          f        ntf(ALWAYS)

        // wIndex = 0x0001
        runAndWaitUntilIdleWithResult {
            oneWireDevice.commOneWireReset(
                DS2490OneWireVendorCommands.COMM_RESULT_HANDLING.ONERROR,
                icp = false,
                im = true,
                f = true,
                pst = false,
                se = true,
                speed = (0x01).toUByte() // flexible
            )
        }

    }

    /** DS2490 1-Wire Write Bit
     *
     * @param bit Writes bit to 1-Wire bus
     */
    override fun oneWireWriteBit(bit: Boolean) {
        Log.d(TAG, "oneWireWriteBit")

        // 0x0221 or (bit shl 3)
        // = 0x0221 + COMM_D_bitmask
        // = 0x0020 + 0x0201 + COMM_D_bitmask
        // = CMD_bitIO + ICP + im + D

        runAndWaitUntilIdleWithResult {
            oneWireDevice.commBitIO(
                resultHandling = DS2490OneWireVendorCommands.COMM_RESULT_HANDLING.ONERROR,
                icp = true, // no data to epIN
                im = true,
                cib = false,
                spu = false,
                d = bit
            )
        }

    }

    /**
     *  DS2490 1-Wire Read Bit
     *
     * @return Bit read from 1-Wire bus
     */
    override fun oneWireReadBit(): Boolean {
        Log.d(TAG, "oneWireReadBit")

        // 0x0029
        // 0x0020 + 0x0008 + 0x0001
        // bitIO    d         im

        runAndWaitUntilIdleWithResult {
            oneWireDevice.commBitIO(
                resultHandling = DS2490OneWireVendorCommands.COMM_RESULT_HANDLING.ONERROR,
                icp = false, // enables reading back from epIN
                im = true,
                cib = false,
                spu = false,
                d = true
            )
        }

        val tempData: ByteArray = oneWireDevice.oneWireReadEpIn(byteCount = 1)
        return (tempData[0].toInt() and 0x01) != 0
    }

    /**
     *  DS2490 1-Wire Write Byte
     *
     * @param byte Writes byte to 1-Wire bus
     */
    override fun oneWireWriteByte(byte: Byte) {
        Log.d(TAG, "oneWireWriteByte")

        runAndWaitUntilIdleWithResult {
            oneWireDevice.commByteIO(
                resultHandling = DS2490OneWireVendorCommands.COMM_RESULT_HANDLING.ONERROR,
                icp = true, // no data to epIN
                im = true,
                spu = false,
                d = byte
            )
        }
    }

    /**
     * DS2490 1-Wire Read Byte
     *
     * @return Byte read from 1-Wire bus
     */
    override fun oneWireReadByte(): Byte {
        Log.d(TAG, "oneWireReadByte")

        runAndWaitUntilIdleWithResult {
            oneWireDevice.commByteIO(
                resultHandling = DS2490OneWireVendorCommands.COMM_RESULT_HANDLING.ONERROR,
                icp = false, // irrelevant for reading back from epIN
                im = true,
                spu = false,
                d = 0xFF.toByte()
            )
        }
        val tempData: ByteArray = oneWireDevice.oneWireReadEpIn(byteCount = 1)
        return tempData[0]
    }

    /**
     * DS2490 1-Wire Bulk Write
     *
     * @param data Byte array of data to be written to the 1-Wire bus
     */
    override fun oneWireWrite(data: ByteArray) {
        Log.d(TAG, "oneWireWrite %d bytes".format(data.size))

        // 0x1075,
        // 0x0074 + 0x1000 + 0x0001
        // CMD      spu      im

        val maxChunkSize = oneWireDevice.currentAlternateConfig.inOutMaxPacketSize

        for (start in data.indices step maxChunkSize) {
            val currentLen = minOf(maxChunkSize, data.size - start)
            val currentData = data.copyOfRange(start, start + currentLen)

            oneWireDevice.oneWireWriteEpOut(data = currentData)
            runAndWaitUntilIdleWithResult {
                oneWireDevice.commBlockIO(
                    resultHandling = DS2490OneWireVendorCommands.COMM_RESULT_HANDLING.ONERROR,
                    icp = false, // irrelevant for data to epIN
                    im = true,
                    spu = true,
                    rst = false,
                    size = currentLen
                )
            }
            oneWireDevice.oneWireReadEpIn(currentLen) // drain epIN
        }
    }

    /**
     * DS2490 1-Wire Bulk Read
     *
     * @param byteCount Number of bytes to read
     * @return Byte array read from the 1-Wire bus
     */
    override fun oneWireRead(byteCount: Int): ByteArray {
        Log.d(TAG, "oneWireRead %d bytes".format(byteCount))

        val maxChunkSize = oneWireDevice.currentAlternateConfig.inOutMaxPacketSize
        val ret = ByteArray(byteCount)


        for (start in 0 until byteCount step maxChunkSize) {
            val currentLen = minOf(maxChunkSize, byteCount - start)
            val dataWritten = ByteArray(currentLen) {
                0xFF.toByte()
            }
            oneWireDevice.oneWireWriteEpOut(data = dataWritten)

            runAndWaitUntilIdleWithResult {
                oneWireDevice.commBlockIO(
                    resultHandling = DS2490OneWireVendorCommands.COMM_RESULT_HANDLING.ONERROR,
                    icp = false, // likely needed to read back
                    im = true,
                    spu = false,
                    rst = false,
                    size = currentLen
                )
            }

            val currentData = oneWireDevice.oneWireReadEpIn(byteCount = currentLen)
            currentData.copyInto(ret, start)
        }

        return ret
    }


    /**
     * DS2490 1-Wire Match ROM command
     *
     * @param romId Byte array containing the ROM code match the device to be
     *              accessed
     */
    override fun oneWireMatchRom(romId: OneWireRomId,
                                 overSpeed: Boolean) {
        Log.d(TAG, "oneWireMatchRom %s %s".format(romId.toString(), overSpeed.toString()))

        oneWireDevice.oneWireWriteEpOut(data = romId.asByteArray().reversedArray())

        // 0x0065 =
        // 0x0064 + 0x0001
        // cmd + im

        runAndWaitUntilIdleWithResult {
            oneWireDevice.commMatchAccess(
                resultHandling = DS2490OneWireVendorCommands.COMM_RESULT_HANDLING.ONERROR,
                icp = false,
                im = true,
                se = false,
                rst = false,
                speed = null,
                overdrive = overSpeed
            )
        }

    }


    override fun oneWireBytesAvailableToRead(): Int {
        val state = getState()

        return state.status.commOneWireDataInBuffer.toInt()
    }

    override fun oneWireSearchAll(conditionalSearch: Boolean): List<OneWireRomId> {
        Log.d(
            TAG,
            "SEARCH ACCESS buffer size %d".format(oneWireDevice.currentAlternateConfig.inOutMaxPacketSize)
        )

        val maxDevices = (oneWireDevice.currentAlternateConfig.inOutMaxPacketSize / 8 - 1).toUByte()
        val ret = ArrayList<OneWireRomId>()
        var lastRomId = ByteArray(8)

        check(lock.isLocked)

        do {
            run {
                val s = getState().status

                if (s.commOneWireDataInBuffer.toInt() != 0)
                    throw USBException(
                        "Data still pending on USB device - epIN was not empty, %d found".format(getState().status.commOneWireDataInBuffer.toInt()),
                        device
                    )
                if (s.commOneWireDataOutBuffer.toInt() != 0)
                    throw USBException(
                        "Data still pending on USB device - epOUT was not empty, %d found".format(getState().status.commOneWireDataOutBuffer.toInt()),
                        device
                    )
            }

            check (lastRomId.size == 8)
            oneWireDevice.oneWireWriteEpOut(lastRomId)

            run {
                val s = getState().status

                if (s.commOneWireDataInBuffer.toInt() != 0)
                    throw USBException(
                        "Data still pending on USB device - epIN was not empty, %d found".format(getState().status.commOneWireDataInBuffer.toInt()),
                        device
                    )
                if (s.commOneWireDataOutBuffer.toInt() != 8)
                    throw USBException(
                        "Data not received by USB device - epOUT was not 8, %d found".format(getState().status.commOneWireDataOutBuffer.toInt()),
                        device
                    )
            }

            var readBuf = ByteArray(0)

            Log.d(TAG, "SEARCH ACCESS maxDevices = %d".format(maxDevices.toInt()))

            var (r, s) = runAndWaitUntilIdleWithResult(true) {
                oneWireDevice.commSearchAccess(
                    resultHandling = DS2490OneWireVendorCommands.COMM_RESULT_HANDLING.ALWAYS, // need under-run error
                    icp = false,
                    im = true,
                    rst = true,
                    f = false,
                    sm = true,
                    rts = true,
                    maxDevices = maxDevices,
                    conditionalSearch = conditionalSearch
                )
            }

            if (r.numResults == 0) {
                Log.v(TAG, "SEARCH ACCESS no results")
                throw USBException("SEARCH ACCESS command: got no results", device)
            }

            if (r.noPresencePulse) {
                Log.v(TAG, "SEARCH ACCESS command: no presence pulse")
                break
            }

            Log.d(
                TAG, "SEARCH ACCESS return with r=%s and s=%s".format(
                    r.toString(),
                    s.toString()
                )
            )

            while (s.commOneWireDataInBuffer.toInt() < 8) {
                Log.d(TAG, "SEARCH ACCESS epIN not ready")
                sleep(100)
                s = getState().status
            }

            while (true) {
                Log.d(TAG, "SEARCH ACCESS looping epIN reading")
                s = getState().status

                Log.d(TAG, "SEARCH ACCESS state %s".format(s.toString()))

                val newBuf =
                    try {
                        oneWireDevice.oneWireReadEpIn(oneWireDevice.currentAlternateConfig.inOutMaxPacketSize)
                    } catch (e: USBException) {
                        Log.v(
                            TAG,
                            "Error reading from device, ok if all data already received: %s".format(
                                e.toString()
                            )
                        )
                        ByteArray(0)
                    }

                Log.d(TAG, "SEARCH ACCESS read %d bytes".format(newBuf.size))

                if (s.commOneWireDataInBuffer.toInt() == 0 && readBuf.size >= 8 && newBuf.isEmpty())
                    break

                if (newBuf.isEmpty()) {
                    Log.e(TAG, "SEARCH ACCESS under-run")
                    throw USBException("No data received in response to SEARCH ACCESS command", device)
                }
                //_onDeviceDebug.postValue(USBException("received %d bytes".format(newBuf.size), device))
                readBuf += newBuf

                sleep(100) // wait for more data to arrive.
            }

            if (readBuf.size % 8 != 0)
                throw USBException(
                    "SEARCH ACCESS command did not result in output multiple of 8 bytes: got %d bytes".format(
                        s.commOneWireDataInBuffer.toInt()
                    ), device
                )

            if (readBuf.size < 8)
                throw USBException(
                    "SEARCH ACCESS command did result in too few bytes: got %d bytes".format(
                        s.commOneWireDataInBuffer.toInt()
                    ), device
                )

            val moreDevices = (!r.searchAccessDeviceUnderrun) // there might be more devices
            val numOutputBlocks = readBuf.size / 8
            if (moreDevices) {
                if (numOutputBlocks <= maxDevices.toInt())
                    throw USBException(
                        "SEARCH ACCESS indicates more results, but did not return maximum amount of data",
                        device
                    )
            }
            val numRomId = if (moreDevices) numOutputBlocks - 1 else numOutputBlocks
            for (i in 0 until numRomId) {
                val romId = readBuf.copyOfRange(i * 8, (i + 1) * 8)
                val romIdO = OneWireRomId(romId.reversedArray())
                romIdO.checkCrc()
                ret.add(romIdO)
                Log.d(TAG, "SEARCH ACCESS got romIds: %s".format(romIdO.toString()))
            }

            if (moreDevices) {
                lastRomId = readBuf.copyOfRange(readBuf.size - 8, readBuf.size)
            }

            if (getState().status.commOneWireDataInBuffer.toInt() != 0)
                throw USBException(
                    "Data still pending on USB device - did not drain epIN, %d remaining".format(getState().status.commOneWireDataInBuffer.toInt()),
                    device
                )

        } while (moreDevices)

        Log.d(TAG, "returning %d romIds".format(ret.size))
        return ret
    }
}