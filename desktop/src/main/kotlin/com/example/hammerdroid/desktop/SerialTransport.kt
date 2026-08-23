package com.example.hammerdroid.desktop

import com.fazecast.jSerialComm.SerialPort
import java.io.Closeable
import java.io.IOException

data class PortChoice(
    val systemName: String,
    val path: String,
    val description: String,
) {
    override fun toString(): String = path + " — " + description
}

class SerialTransport : Closeable {
    @Volatile
    private var port: SerialPort? = null

    val isOpen: Boolean
        get() = port?.isOpen == true

    fun ports(): List<PortChoice> =
        SerialPort.getCommPorts()
            .map {
                PortChoice(
                    systemName = it.systemPortName,
                    path = it.systemPortPath,
                    description = it.descriptivePortName.ifBlank { "Serial device" },
                )
            }
            .sortedWith(
                compareBy<PortChoice>(
                    { !it.path.contains("rfcomm", ignoreCase = true) },
                    { !it.description.contains("OBDX", ignoreCase = true) },
                    { it.path },
                ),
            )

    @Synchronized
    @Throws(IOException::class)
    fun open(choice: PortChoice) {
        close()
        val candidate = SerialPort.getCommPort(choice.systemName)
        candidate.setComPortParameters(115200, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        candidate.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        candidate.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING, 0, 0)
        if (!candidate.openPort(4_000)) {
            candidate.closePort()
            throw IOException("Unable to open " + choice.path)
        }
        port = candidate
        candidate.flushIOBuffers()
    }

    @Throws(IOException::class)
    fun write(data: ByteArray) {
        val active = requireOpen()
        var offset = 0
        while (offset < data.size) {
            checkNotInterrupted()
            val remaining = data.copyOfRange(offset, data.size)
            val written = active.writeBytes(remaining, remaining.size.toLong())
            if (written < 0) throw IOException("Serial write failed")
            if (written == 0) {
                Thread.sleep(5)
            } else {
                offset += written
            }
        }
    }

    @Throws(IOException::class)
    fun readByte(deadlineMillis: Long): Int? {
        val active = requireOpen()
        while (System.currentTimeMillis() < deadlineMillis) {
            checkNotInterrupted()
            if (!active.isOpen) throw IOException("Serial connection closed")
            if (active.bytesAvailable() > 0) {
                val one = ByteArray(1)
                if (active.readBytes(one, 1) == 1) return one[0].toInt() and 0xFF
            }
            Thread.sleep(5)
        }
        return null
    }

    fun discardInput() {
        val active = port ?: return
        val available = active.bytesAvailable()
        if (available > 0) {
            active.readBytes(ByteArray(available), available.toLong())
        }
    }

    fun checkConnected() {
        if (!isOpen) throw IOException("Adapter is disconnected")
    }

    private fun requireOpen(): SerialPort =
        port?.takeIf { it.isOpen } ?: throw IOException("No serial connection")

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException("Operation cancelled")
    }

    @Synchronized
    override fun close() {
        port?.let {
            runCatching { it.flushIOBuffers() }
            runCatching { it.closePort() }
        }
        port = null
    }
}
