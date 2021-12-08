package cn.findpiano.romsdk.hardware.midi

import cn.findpiano.utils.extension.toHexContentStringWithPrefix
import java.util.*
import kotlin.collections.ArrayList
import kotlin.experimental.and

/**
 * ble midi和标准midi的适配器，可将ble midi事件转换为标准的midi
 */
class BleMidiAdapter {

    enum class MidiMessageState {
        Protocol,//协议处理状态
        Event,//数据处理状态
    }

    enum class MidiProtocol {
        FullProtocol,//Full格式
        RunningStatusProtocol,//RunningStatus格式
    }

    private var currentState = MidiMessageState.Protocol
    private var currentProtocol = MidiProtocol.FullProtocol

    private val result = ArrayList<ByteArray>()

    private var lastMidiStatus = 0x00.toByte()

    //缓存收到的数据
    private var dataBuffer = LinkedList<Byte>()

    //已经处理过的数据的指针
    private var processed = 0

    //扫描过的指针
    private var scaned = 0

    /**
     * 将Ble MIDI数据转换成标准的MIDI数据
     * @param data ByteArray BLE MIDI协议的数据
     * @return ByteArray 标准的MIDI数据
     */
    @Synchronized
    fun asMidiEvent(data: ByteArray): ArrayList<ByteArray> {
        data.forEach(dataBuffer::addLast)
        return getMidi()
    }

    private fun getMidi(): ArrayList<ByteArray> {
        //初始化数据
        result.clear()
        scaned = 0
        processed = 0
        while (scaned < dataBuffer.size) {
            when (currentState) {
                MidiMessageState.Protocol -> {
                    processProtocol()
                }
                MidiMessageState.Event -> {
                    processEvent()
                }
            }
        }
        while (processed > 0) {
            dataBuffer.pop()
            processed--
        }

        return result
    }

    private fun processProtocol() {
        checkState(MidiMessageState.Protocol)
        //从当前位置开始，至少要三个数据，才能进行判断，否则等待下一次的数据包
        if (scaned + 2 < dataBuffer.size) {
            //判断前三个
            if (dataBuffer[scaned].toUInt() > 0x79.toUInt()
                    && dataBuffer[scaned + 1].toUInt() > 0x79.toUInt()
                    && dataBuffer[scaned + 2].toUInt() > 0x79.toUInt()
            ) {
                currentProtocol = MidiProtocol.FullProtocol
                processed += 2
                scaned += 2
                switchToEvent()
            } else if (dataBuffer[scaned].toUInt() > 0x79.toUInt()
                    && dataBuffer[scaned + 1].toUInt() > 0x79.toUInt()
                    && dataBuffer[scaned + 2].toUInt() <= 0x79.toUInt()) {
                currentProtocol = MidiProtocol.FullProtocol
                processed += 1
                scaned += 1
                switchToEvent()
            } else if (dataBuffer[scaned].toUInt() <= 0x79.toUInt()
                    && dataBuffer[scaned + 1].toUInt() <= 0x79.toUInt()
                    && dataBuffer[scaned + 2].toUInt() <= 0x79.toUInt()) {
                currentProtocol = MidiProtocol.RunningStatusProtocol
                switchToEvent()
            } else if (dataBuffer[scaned].toUInt() <= 0x79.toUInt()
                    && dataBuffer[scaned + 1].toUInt() <= 0x79.toUInt()) {
                currentProtocol = MidiProtocol.RunningStatusProtocol
                switchToEvent()
            } else {
                scaned++
            }
        } else if (scaned + 1 < dataBuffer.size) {
            if (dataBuffer[scaned].toUInt() <= 0x79.toUInt()
                    && dataBuffer[scaned + 1].toUInt() <= 0x79.toUInt()) {
                currentProtocol = MidiProtocol.RunningStatusProtocol
                switchToEvent()
            } else if (dataBuffer[scaned].toUInt() > 0x79.toUInt()
                    && dataBuffer[scaned + 1].toUInt() > 0x79.toUInt()) {
                scaned += 2
                //需要等待下一个数据才能决定使用的协议
            } else {
                throw IllegalArgumentException("无法解析的数据格式")
            }
        } else {
            scaned++
        }

    }


    private fun processEvent() {
        checkState(MidiMessageState.Event)
        //需要判断使用的何种协议
        when (currentProtocol) {
            MidiProtocol.FullProtocol -> {
                processFullProtocol()
            }
            MidiProtocol.RunningStatusProtocol -> {
                processRunningStatusProtocol()
            }
        }
    }

    private fun processRunningStatusProtocol() {
        if (scaned + 1 < dataBuffer.size) {
            result.add(byteArrayOf(lastMidiStatus, dataBuffer[scaned], dataBuffer[scaned + 1]))
            scaned += 2
            processed += 2
            switchToProtocol()
        }
    }

    private fun processFullProtocol() {
        //因为FullProtocol一定会带有TimeStamp，所以，跳过TimeStamp
        //现在scaned指向了MidiStatus
        when (dataBuffer[scaned] and 0xF0.toByte()) { //startIndex 事件的第一个字节
            0x80.toByte(), 0x90.toByte(), 0xA0.toByte(), 0xB0.toByte(), 0xC0.toByte(), 0xD0.toByte(), 0xE0.toByte() -> {
                processMidiEvent()
            }

            0xF0.toByte() -> {//系统消息
                //接下来处理系统消息
                processSysEvent()
            }
            else -> {
                throw IllegalArgumentException("数据错误" + dataBuffer.toByteArray().toHexContentStringWithPrefix())
            }
        }
    }


    private fun processMidiEvent() {
        //判断消息是否完整
        //将完整的消息提出，并将不完整的消息保存在缓冲区中。
        //注意：对于不完整的消息，可能来自于第一个事件，也可能来自于中间事件，所以对于不完整的消息，只保存MidiEvent。而不保存Header和Timestamp
        val end = getNextMidiEvent()
        if (end - scaned == 2) {
            lastMidiStatus = dataBuffer[scaned]
            result.add(byteArrayOf(dataBuffer[scaned++], dataBuffer[scaned++], dataBuffer[scaned++]))
            switchToProtocol()
            processed += 3
        } else {
            scaned = end + 1
        }
    }

    private fun processSysEvent() {
        //找到F7的位置，即结束位置
        val endIndex = getF7Position()
        if (dataBuffer[endIndex] == 0xF7.toByte()) {
            //含有完整的数据
            result.add(dataBuffer.toByteArray().copyOfRange(scaned, endIndex + 1))
            scaned = endIndex + 1
            processed = endIndex + 1
            switchToProtocol()
        } else {
            scaned = endIndex + 1
        }
    }

    private fun getNextMidiEvent(): Int {
        val event = dataBuffer[scaned]
        when {
            event.toUInt() > 0x80.toUInt() -> {
                return if ((scaned + 3 < dataBuffer.size)) {
                    scaned + 2
                } else {
                    dataBuffer.size - 1
                }
            }
            else -> {
                throw java.lang.IllegalArgumentException("未知格式")
            }
        }
    }

    private fun getF7Position(): Int {
        //找到0xF7所在位置
        var sysStart = scaned
        while (sysStart < dataBuffer.size && dataBuffer[sysStart++] != 0xF7.toByte()) {
        }
        if (sysStart < dataBuffer.size) {
            //找到F7的位置
            sysStart--
            return sysStart
        }
        //表示没有找到F7
        return dataBuffer.size - 1
    }

    private fun checkState(needState: MidiMessageState): Boolean {
        if (currentState != needState) {
            throw IllegalArgumentException("状态不对")
        } else {
            return true
        }
    }

    private fun switchToProtocol() {
        currentState = MidiMessageState.Protocol
    }

    private fun switchToEvent() {
        currentState = MidiMessageState.Event
    }

}