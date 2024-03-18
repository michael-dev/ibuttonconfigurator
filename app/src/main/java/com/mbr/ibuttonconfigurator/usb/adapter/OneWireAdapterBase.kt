package com.mbr.ibuttonconfigurator.usb.adapter

import androidx.lifecycle.LiveData
import java.util.concurrent.locks.ReentrantLock

abstract class OneWireAdapterBase {
    /* control commands */
    abstract fun oneWireResetDevice()

    /* mode commands */

    /* communication commands */
    abstract fun oneWireResetBus()

    abstract fun oneWireWriteBit(bit: Boolean)
    abstract fun oneWireReadBit(): Boolean

    abstract fun oneWireWriteByte(byte: Byte)
    abstract fun oneWireReadByte(): Byte

    abstract fun oneWireRead(byteCount: Int): ByteArray
    abstract fun oneWireWrite(data: ByteArray)

    abstract fun oneWireBytesAvailableToRead(): Int

    open fun oneWireSkipRom(overSpeed: Boolean = false) {
        if (overSpeed) {
            oneWireWriteByte(0x3C.toByte())
        } else {
            oneWireWriteByte(0xCC.toByte())
        }
    }

    open fun oneWireResume() {
        oneWireWriteByte(0xA5.toByte()) // RESUME command
    }
    open fun oneWireMatchRom(
        romId: OneWireRomId,
        overSpeed: Boolean = false)
    {
        if (overSpeed) {
            oneWireWriteByte(0x69)
        } else {
            oneWireWriteByte(0x55)
        }
    }

    open fun oneWireSearchAll(conditionalSearch: Boolean = false): List<OneWireRomId> {
        val romNoList = ArrayList<OneWireRomId>()
        var lastRomNo: OneWireRomId? = null
        var lastBranchZero: Int = -1
        while (true) {
            val (romNo, branchZero) = oneWireSearchNext(lastRomNo, lastBranchZero)
            if (romNo == null) break

            romNoList.add(romNo)
            lastRomNo = romNo
            lastBranchZero = branchZero

            if (branchZero == -1) break // was last device on bus
        }
        return romNoList
    }

    /*
	 * 1-Wire Search Algorithm as described in application note 187
	 * http://www.maximintegrated.com/an187
	 */
    private fun oneWireSearchNext(
        lastRomId: OneWireRomId?,
        lastBranchZero: Int
    ): Pair<OneWireRomId?, Int> {
        val romId = OneWireRomId()

        // initialize for search
        var lastBranchPickZero: Int = -1

        // 1-Wire reset
        oneWireResetBus()
        oneWireWrite(byteArrayOf(0xF0.toByte()))
        for (idBitNumber: Int in 63 downTo 0) {
            var nextBit: Boolean // true = 1, false = 0

            // read a bit and its complement
            val idBit = oneWireReadBit()
            val cmpIdBit = oneWireReadBit()

            if (idBit && cmpIdBit ) {
                // no devices on 1-Wire
                return Pair(null, -1)
            } else if (idBit != cmpIdBit) {
                // all devices coupled have 0 or 1
                nextBit = idBit
            } else {
                // idBit == 0 && cmdIdBit == 0
                nextBit = if (idBitNumber < lastBranchZero) {
                    // keep everything before last branching point from lastRomId
                    // if lastRomIs is null, then lastBranchZero is -1, so this branch is not taken
                    lastRomId!!.getBit(idBitNumber)
                } else {
                    // branch at lastBranchZero to 1 (true), later branches always pick 0 (false)
                    idBitNumber == lastBranchZero
                }

                // if 0 was picked then record its position in lastBranchPickZero
                if (!nextBit) {
                    lastBranchPickZero = idBitNumber
                }
            }

            // write nextBit to romId
            romId.setBit(idBitNumber, nextBit)

            // send nextBit to 1-wire bus
            oneWireWriteBit(nextBit)
        }

        romId.checkCrc()

        return Pair(romId, lastBranchPickZero)
    }

    val lock = ReentrantLock()
    abstract val onDeviceDetected: LiveData<Unit>
    abstract val onDeviceFailure: LiveData<out Exception>
    abstract val onDeviceDebug: LiveData<out USBException>
}