package com.example.hammerdroid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class BluetoothSppTransport {
    private val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private val connectionMutex = Mutex()
    private val ioMutex = Mutex()

    val isConnected: Boolean
        get() = socket?.isConnected == true && input != null && output != null

    @SuppressLint("MissingPermission")
    suspend fun connect(device: BluetoothDevice) = connectionMutex.withLock {
        withContext(Dispatchers.IO) {
            close()
            val opened = device.createRfcommSocketToServiceRecord(spp)
            try {
                opened.connect()
                val openedInput = opened.inputStream
                val openedOutput = opened.outputStream
                socket = opened
                input = openedInput
                output = openedOutput
            } catch (error: Throwable) {
                runCatching { opened.close() }
                throw error
            }
        }
    }

    suspend fun write(bytes: ByteArray) = ioMutex.withLock {
        withContext(Dispatchers.IO) {
            checkConnected()
            val stream = output ?: error("Bluetooth is not connected")
            stream.write(bytes)
            stream.flush()
        }
    }

    suspend fun readByte(timeoutMs: Long): Int = withContext(Dispatchers.IO) {
        val stream = input ?: error("Bluetooth is not connected")
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            coroutineContext.ensureActive()
            if (stream.available() > 0) return@withContext stream.read()
            delay(2)
        }
        -1
    }

    suspend fun readAvailable(idleMs: Long = 80, totalMs: Long = 1500): ByteArray =
        withContext(Dispatchers.IO) {
            val stream = input ?: error("Bluetooth is not connected")
            val bytes = ArrayList<Byte>()
            val end = System.currentTimeMillis() + totalMs
            var idleEnd = System.currentTimeMillis() + idleMs
            while (System.currentTimeMillis() < end) {
                coroutineContext.ensureActive()
                if (stream.available() > 0) {
                    bytes.add(stream.read().toByte())
                    idleEnd = System.currentTimeMillis() + idleMs
                } else if (bytes.isNotEmpty() && System.currentTimeMillis() >= idleEnd) {
                    break
                } else {
                    delay(2)
                }
            }
            bytes.toByteArray()
        }

    suspend fun discardInput() = withContext(Dispatchers.IO) {
        val stream = input ?: return@withContext
        while (stream.available() > 0) stream.read()
    }

    fun checkConnected() {
        check(isConnected) { "Bluetooth connection was lost" }
    }

    fun close() {
        runCatching { input?.close() }
        runCatching { output?.close() }
        runCatching { socket?.close() }
        input = null
        output = null
        socket = null
    }
}
