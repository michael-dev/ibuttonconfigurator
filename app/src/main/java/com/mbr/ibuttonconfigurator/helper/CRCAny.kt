package com.mbr.ibuttonconfigurator.helper
import java.util.zip.Checksum

abstract class CRCAny (private val polynomial : UInt ) : Checksum {
    private var crc = 0u

    override fun update(input: ByteArray, offset: Int, len: Int) {
        for (i in 0 until len) {
            update(input[offset + i])
        }
    }

    private fun update(b: Byte) {
        var inputByte = b.toUInt()
        for (bit in 0 until 8) {
            val mix = (crc xor inputByte) and 0x01u
            crc = crc shr 1
            if (mix != 0x00u)
                crc = crc xor polynomial
            inputByte = inputByte shr 1
        }
    }

    override fun update(b: Int) {
        update(b.toByte())
    }

    override fun getValue(): Long {
        return crc.toLong()
    }

    override fun reset() {
        crc = 0u
    }
}