package com.mbr.ibuttonconfigurator.usb.adapter

import com.mbr.ibuttonconfigurator.helper.CRC8
import kotlin.experimental.and
import kotlin.experimental.inv
import kotlin.experimental.or

class OneWireRomId {
    private val romId = ByteArray(8)

    constructor()

    constructor(romId: ByteArray) {
        romId.copyInto(this.romId, 0, 0, 8)
    }

    private fun bitMask(bitInByte: Int): Byte {
        return (1 shl bitInByte).toByte()
    }

    fun setBit(bitId: Int, bitValue: Boolean) {
        val byteId = bitId / 8
        val bitInByte = bitId % 8
        val bitMask = bitMask(7 - bitInByte)

        if (bitValue) {
            romId[byteId] = romId[byteId] or bitMask
        } else {
            romId[byteId] = romId[byteId] and bitMask.inv()
        }
    }

    fun getBit(bitId: Int): Boolean {
        val byteId = bitId / 8
        val bitInByte = bitId % 8
        val bitMask = bitMask(bitInByte)

        return (romId[byteId] and bitMask).toInt() != 0
    }

    fun isFamily(familyCode: Byte): Boolean {
        return getFamily() == familyCode
    }

    fun getFamily(): Byte {
        return romId[7] and 0x7F
    }

    fun asByteArray(): ByteArray {
        return romId.copyOf()
    }

    override fun toString(): String {

        val isCustomerSpecific = (romId[7] and 0x80.toByte()).toInt() != 0

        if (!isCustomerSpecific) {
            return "Family 0x%02X S/N 0x%02X:%02X:%02X:%02X:%02X:%02X CRC 0x%02X".format(
                romId[7],
                romId[1],
                romId[2],
                romId[3],
                romId[4],
                romId[5],
                romId[6],
                romId[0]
            )
        } else {
            return "Family 0x%02X Customer 0x%03X Id 0x%01X:%02X:%02X:%02X:%02X CRC 0x%02X".format(
                romId[7] and 0x7F,
                romId[1].toInt().shl(4) + (romId[2] and 0xF0.toByte()).toInt().shr(4),
                romId[2] and 0x0F.toByte(),
                romId[3],
                romId[4],
                romId[5],
                romId[6],
                romId[0]
            )
        }
    }

    operator fun get(i: Int): Byte {
        return romId[i]
    }

    operator fun set(i: Int, v: Byte) {
        romId[i] = v
    }

    fun checkCrc() {
        val crc = CRC8()

        for (i in romId.indices.reversed()) {
            crc.update(romId[i].toUByte().toInt())
        }

        if (crc.value.toInt() != 0)
            throw Exception(
                "CRC error: romId=%s, crc=%d".format(
                    this.toString(),
                    crc.value.toInt()
                )
            )
    }

    override fun equals(other: Any?): Boolean {
        return (other is OneWireRomId)
                && romId.contentEquals(other.romId)
    }

    override fun hashCode(): Int {
        return romId.hashCode()
    }
}