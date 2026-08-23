package com.example.hammerdroid.desktop

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

data class GtInfo(
    val name: String,
    val firmware: String,
    val hardware: String,
    val serial: String,
    val voltage: Double,
)

data class PcmIdentity(val osId: Long, val vin: String)
private data class DviPacket(val command: Int, val payload: ByteArray)

class ObdxLinuxClient(private val transport: SerialTransport) {
    fun initialize(log: (String) -> Unit): GtInfo {
        transport.write(byteArrayOf(0x25, 0x00, 0xDA.toByte()))
        Thread.sleep(250)
        transport.discardInput()
        elm("ATZ", 700)
        val description = elm("AT@1", 1_000)
        require(description.contains("OBDX", true)) { "Selected device is not an OBDX adapter" }
        require(elm("DXDP1", 1_000).contains("OK", true)) { "GT refused DVI mode" }

        val name = asciiData(transact(0x22, byteArrayOf(0x03)), 0x03)
        require(name.contains("GT", true)) { "Connected adapter is '" + name + "', not an OBDX Pro GT" }
        val firmware = versionData(transact(0x22, byteArrayOf(0x01)), 0x01)
        val hardware = versionData(transact(0x22, byteArrayOf(0x00)), 0x00)
        val serial = responseData(transact(0x22, byteArrayOf(0x04)), 0x04)
            .joinToString("") { "%02X".format(it) }
        val voltageData = responseData(transact(0x3A, byteArrayOf(0x00)), 0x00)
        require(voltageData.size >= 2) { "Short voltage response" }
        val raw = ((voltageData[0].toInt() and 0xFF) shl 8) or
            (voltageData[1].toInt() and 0xFF)

        transact(0x31, byteArrayOf(0x01, 0x01))
        transact(0x33, byteArrayOf(0x00, 0xF0.toByte(), 0x01))
        transact(0x31, byteArrayOf(0x02, 0x01))
        log("GT initialized in J1850 VPW mode")
        return GtInfo(name, firmware, hardware, serial, raw * 0.009047468 + 0.2)
    }

    fun identifyPcm(log: (String) -> Unit): PcmIdentity {
        log("Querying PCM…")
        val os = sendVpwWithRetry(
            byteArrayOf(0x6C, 0x10, 0xF0.toByte(), 0x3C, 0x0A),
            log,
        )
        require(os.size >= 9 && os[3] == 0x7C.toByte()) { "Unexpected OS response" }
        val osId = os.copyOfRange(5, 9)
            .fold(0L) { value, byte -> (value shl 8) or (byte.toLong() and 0xFF) }

        val vinOut = ByteArrayOutputStream()
        (1..3).forEach { block ->
            val message = sendVpwWithRetry(
                byteArrayOf(0x6C, 0x10, 0xF0.toByte(), 0x3C, block.toByte()),
                log,
            )
            require(
                message.size >= 6 &&
                    message[3] == 0x7C.toByte() &&
                    message[4] == block.toByte(),
            ) { "Unexpected VIN block response" }
            val data = message.copyOfRange(5, message.size)
            if (block == 1 && data.firstOrNull() == 0.toByte()) {
                vinOut.write(data, 1, data.size - 1)
            } else {
                vinOut.write(data)
            }
        }
        val vin = vinOut.toString(StandardCharsets.US_ASCII.name()).take(17)
        require(VIN.matches(vin)) { "PCM returned an invalid VIN" }
        return PcmIdentity(osId, vin)
    }

    fun checkConnection(): Boolean = runCatching {
        transport.checkConnected()
        asciiData(transact(0x22, byteArrayOf(0x03), 800), 0x03)
            .contains("OBDX", ignoreCase = true)
    }.getOrDefault(false)

    fun resetAdapter() {
        if (!transport.isOpen) return
        transport.write(byteArrayOf(0x25, 0x00, 0xDA.toByte()))
        Thread.sleep(250)
        transport.discardInput()
    }

    private fun elm(command: String, timeoutMs: Long): String {
        transport.write((command + "\r").toByteArray(StandardCharsets.US_ASCII))
        Thread.sleep(60)
        val deadline = System.currentTimeMillis() + timeoutMs
        val output = ByteArrayOutputStream()
        while (System.currentTimeMillis() < deadline) {
            val value = transport.readByte((System.currentTimeMillis() + 80).coerceAtMost(deadline))
            if (value != null) {
                output.write(value)
                if (value == '>'.code) break
            }
        }
        return output.toByteArray()
            .toString(StandardCharsets.US_ASCII)
            .replace(command, "", true)
            .replace(">", "")
            .trim()
    }

    private fun checksum(bytes: ByteArray, count: Int = bytes.size - 1): Byte {
        var sum = 0
        repeat(count) { sum = (sum + (bytes[it].toInt() and 0xFF)) and 0xFF }
        return sum.inv().toByte()
    }

    private fun encode(command: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 0xFF) { "DVI transmit payload is too large" }
        val packet = ByteArray(payload.size + 3)
        packet[0] = command.toByte()
        packet[1] = payload.size.toByte()
        payload.copyInto(packet, 2)
        packet[packet.lastIndex] = checksum(packet)
        return packet
    }

    private fun transact(command: Int, payload: ByteArray, timeoutMs: Long = 1_500): DviPacket {
        transport.checkConnected()
        transport.write(encode(command, payload))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val packet = readPacket(deadline)
            if (packet.command == 0x08 || packet.command == 0x09) continue
            val expected = (command + 0x10) and 0xFF
            require(packet.command == expected) {
                "GT response command 0x%02X did not match request 0x%02X (expected 0x%02X)"
                    .format(packet.command, command, expected)
            }
            return packet
        }
        error("Timed out waiting for GT command 0x%02X".format(command))
    }

    private fun readPacket(deadline: Long): DviPacket {
        val command = transport.readByte(deadline) ?: error("Timed out waiting for GT")
        val lengthHighOrShort = transport.readByte(deadline) ?: error("Timed out waiting for GT length")
        val header: ByteArray
        val length: Int
        if (command == 0x09) {
            val lengthLow = transport.readByte(deadline) ?: error("Timed out waiting for GT packet length")
            length = (lengthHighOrShort shl 8) or lengthLow
            header = byteArrayOf(command.toByte(), lengthHighOrShort.toByte(), lengthLow.toByte())
        } else {
            length = lengthHighOrShort
            header = byteArrayOf(command.toByte(), lengthHighOrShort.toByte())
        }
        require(length in 0..MAX_DVI_PAYLOAD) { "Invalid DVI payload length " + length }
        val body = ByteArray(length + 1)
        body.indices.forEach { index ->
            body[index] = (transport.readByte(deadline)
                ?: error("Timed out waiting for GT packet body")).toByte()
        }
        val complete = header + body
        require(checksum(complete, complete.lastIndex) == complete.last()) { "DVI checksum failed" }
        require(command != 0x7F) { "GT reported a DVI error" }
        return DviPacket(command, body.copyOfRange(0, body.lastIndex))
    }

    private fun responseData(packet: DviPacket, sub: Int): ByteArray {
        require(packet.payload.isNotEmpty() && (packet.payload[0].toInt() and 0xFF) == sub) {
            "GT response did not match requested subcommand"
        }
        return packet.payload.copyOfRange(1, packet.payload.size)
    }

    private fun asciiData(packet: DviPacket, sub: Int) =
        responseData(packet, sub).toString(StandardCharsets.US_ASCII).trim('\u0000', ' ')

    private fun versionData(packet: DviPacket, sub: Int) =
        responseData(packet, sub).joinToString(".") { (it.toInt() and 0xFF).toString() }

    private fun sendVpwWithRetry(message: ByteArray, log: (String) -> Unit): ByteArray {
        var lastError: Throwable? = null
        repeat(MAX_VPW_ATTEMPTS) { attempt ->
            try {
                return sendVpw(message)
            } catch (error: Throwable) {
                if (error is InterruptedException) throw error
                lastError = error
                if (attempt + 1 < MAX_VPW_ATTEMPTS) {
                    log("PCM request timed out; retrying " + (attempt + 2) + "/" + MAX_VPW_ATTEMPTS + "…")
                    Thread.sleep(150L * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("PCM did not respond")
    }

    private fun sendVpw(message: ByteArray): ByteArray {
        require(message.size >= 4) { "VPW request is too short" }
        transport.checkConnected()
        transport.write(encode(0x10, message))
        val end = System.currentTimeMillis() + 2_500
        while (System.currentTimeMillis() < end) {
            val slice = (System.currentTimeMillis() + 600).coerceAtMost(end)
            val packet = try {
                readPacket(slice)
            } catch (error: IllegalStateException) {
                if (error.message?.startsWith("Timed out") == true) continue else throw error
            }
            if (packet.command != 0x08) continue
            if (isCorrelatedNegativeResponse(message, packet.payload)) {
                error(
                    "PCM refused service 0x%02X with code 0x%02X".format(
                        message[3].toInt() and 0xFF,
                        (packet.payload.getOrNull(5)?.toInt() ?: 0) and 0xFF,
                    ),
                )
            }
            if (isMatchingVpwResponse(message, packet.payload)) return packet.payload
        }
        error("PCM did not respond")
    }

    private fun isMatchingVpwResponse(request: ByteArray, response: ByteArray): Boolean {
        if (response.size < 4) return false
        if (response[0] != request[0] || response[1] != request[2] || response[2] != request[1]) {
            return false
        }
        val expectedMode = ((request[3].toInt() and 0xFF) + 0x40) and 0xFF
        if ((response[3].toInt() and 0xFF) != expectedMode) return false
        return request.size < 5 || (response.size >= 5 && response[4] == request[4])
    }

    private fun isCorrelatedNegativeResponse(request: ByteArray, response: ByteArray): Boolean =
        response.size >= 5 &&
            response[0] == request[0] &&
            response[1] == request[2] &&
            response[2] == request[1] &&
            (response[3].toInt() and 0xFF) == 0x7F &&
            response[4] == request[3]

    private companion object {
        const val MAX_VPW_ATTEMPTS = 3
        const val MAX_DVI_PAYLOAD = 65_535
        val VIN = Regex("^[A-HJ-NPR-Z0-9]{17}$")
    }
}
