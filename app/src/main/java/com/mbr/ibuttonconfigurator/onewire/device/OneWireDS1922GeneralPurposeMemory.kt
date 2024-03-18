package com.mbr.ibuttonconfigurator.onewire.device

class OneWireDS1922GeneralPurposeMemory private constructor(internal val memory: OneWireDS1922Raw.MutableRegisterData) {
    val len = memory.size

    constructor(device: OneWireDS1922) : this(device.getGeneralPurposeMemory())

    operator fun get(start: Int): Byte {
        if (!memory.contains(start, 1))
            throw IndexOutOfBoundsException()
        return memory[memory.offset + start]!!
    }
    operator fun get(start: Int, len: Int): ByteArray {
        if (!memory.contains(start, len))
            throw IndexOutOfBoundsException()
        return memory[memory.offset + start until memory.offset + start + len]
    }
    operator fun get(idxRange: IntRange): ByteArray {
        if (!memory.contains(idxRange.first, idxRange.last - idxRange.first + 1))
            throw IndexOutOfBoundsException()
        return memory[idxRange]
    }

    operator fun set(start: Int, data: Byte) {
        if (!memory.contains(start, 1))
            throw IndexOutOfBoundsException()
        memory[memory.offset + start] = data
    }

    operator fun set(start: Int, data: ByteArray) {
        if (!memory.contains(start, data.size))
            throw IndexOutOfBoundsException()
        for (i in 0 until data.size)
            memory[memory.offset + start+ i] = data[i]
    }

    fun clone(): OneWireDS1922GeneralPurposeMemory {
        return OneWireDS1922GeneralPurposeMemory(memory.clone())
    }
}