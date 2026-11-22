package com.example.protocol

import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Target address types expected by `parseInit` in `worker.js`:
 * - `1` = IPv4 (4 raw bytes)
 * - `2` = Domain (1-byte length `l`, followed by `l` UTF-8 bytes)
 * - `3` = IPv6 (16 bytes / eight 16-bit big-endian words)
 */
sealed class TargetAddress(val typeByte: Byte) {
  data class IPv4(val bytes: ByteArray) : TargetAddress(AT_IPV4) {
    init {
      require(bytes.size == 4) { "IPv4 address must be 4 bytes" }
    }

    override fun equals(other: Any?): Boolean =
      this === other || (other is IPv4 && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()
  }

  data class Domain(val host: String) : TargetAddress(AT_DOMAIN) {
    val utf8Bytes: ByteArray = host.toByteArray(Charsets.UTF_8)

    init {
      require(utf8Bytes.size in 1..255) { "Domain length must be 1..255 UTF-8 bytes" }
    }
  }

  data class IPv6(val bytes: ByteArray) : TargetAddress(AT_IPV6) {
    init {
      require(bytes.size == 16) { "IPv6 address must be 16 bytes" }
    }

    override fun equals(other: Any?): Boolean =
      this === other || (other is IPv6 && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int = bytes.contentHashCode()
  }

  companion object {
    const val AT_IPV4: Byte = 1
    const val AT_DOMAIN: Byte = 2
    const val AT_IPV6: Byte = 3

    private val IPV4_LITERAL_REGEX =
      Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

    fun parse(host: String): TargetAddress {
      val trimmed = host.trim()
      require(trimmed.isNotEmpty()) { "Target host must not be empty" }

      if (IPV4_LITERAL_REGEX.matches(trimmed)) {
        val parts = trimmed.split('.')
        val bytes = ByteArray(4) { i -> parts[i].toInt().toByte() }
        return IPv4(bytes)
      }

      val unbracketed = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
        trimmed.substring(1, trimmed.length - 1)
      } else {
        trimmed
      }

      if (unbracketed.contains(':')) {
        val addr = InetAddress.getByName(unbracketed)
        if (addr is Inet6Address) {
          return IPv6(addr.address)
        }
        if (addr is Inet4Address) {
          return IPv4(addr.address)
        }
      }

      return Domain(trimmed)
    }
  }
}

data class InitPacketResult(
  val packetBytes: ByteArray,
  val nonce: ByteArray,
  val sessionKey: ByteArray,
) {
  override fun equals(other: Any?): Boolean =
    this === other ||
      (other is InitPacketResult &&
        packetBytes.contentEquals(other.packetBytes) &&
        nonce.contentEquals(other.nonce) &&
        sessionKey.contentEquals(other.sessionKey))

  override fun hashCode(): Int {
    var result = packetBytes.contentHashCode()
    result = 31 * result + nonce.contentHashCode()
    result = 31 * result + sessionKey.contentHashCode()
    return result
  }
}

/**
 * Builds `parseInit`-compatible binary frames for `worker.js`:
 * `nonce (16 bytes) || xor(body, sessionKey) || trailer (8 bytes)`
 *
 * Where `body` is:
 * `cmd (1) || port_be (2) || at (1) || addr_payload || initialPayload`
 */
object ChatV2Packet {

  const val CMD_TCP: Byte = 1
  const val CMD_UDP: Byte = 2

  fun buildInitPacket(
    uuidToken: String,
    targetHost: String,
    targetPort: Int,
    command: Byte = CMD_TCP,
    initialPayload: ByteArray = ByteArray(0),
    nonce: ByteArray = ChatV2Crypto.randomBytes(ChatV2Crypto.NONCE_SIZE),
    trailer: ByteArray = ChatV2Crypto.randomBytes(ChatV2Crypto.INIT_TRAILER_SIZE),
  ): InitPacketResult {
    require(targetPort in 1..65535) { "Target port must be in 1..65535, got $targetPort" }
    require(nonce.size == ChatV2Crypto.NONCE_SIZE) { "Nonce must be 16 bytes" }
    require(trailer.size == ChatV2Crypto.INIT_TRAILER_SIZE) { "Trailer must be 8 bytes" }

    val targetAddress = TargetAddress.parse(targetHost)
    return buildInitPacket(
      uuidToken = uuidToken,
      targetAddress = targetAddress,
      targetPort = targetPort,
      command = command,
      initialPayload = initialPayload,
      nonce = nonce,
      trailer = trailer,
    )
  }

  fun buildInitPacket(
    uuidToken: String,
    targetAddress: TargetAddress,
    targetPort: Int,
    command: Byte = CMD_TCP,
    initialPayload: ByteArray = ByteArray(0),
    nonce: ByteArray = ChatV2Crypto.randomBytes(ChatV2Crypto.NONCE_SIZE),
    trailer: ByteArray = ChatV2Crypto.randomBytes(ChatV2Crypto.INIT_TRAILER_SIZE),
  ): InitPacketResult {
    require(targetPort in 1..65535) { "Target port must be in 1..65535, got $targetPort" }
    require(nonce.size == ChatV2Crypto.NONCE_SIZE) { "Nonce must be 16 bytes" }
    require(trailer.size == ChatV2Crypto.INIT_TRAILER_SIZE) { "Trailer must be 8 bytes" }

    val sessionKey = ChatV2Crypto.deriveSessionKey(uuidToken, nonce)

    val bodyStream = ByteArrayOutputStream(64 + initialPayload.size)
    bodyStream.write(command.toInt() and 0xFF)
    bodyStream.write((targetPort ushr 8) and 0xFF)
    bodyStream.write(targetPort and 0xFF)
    bodyStream.write(targetAddress.typeByte.toInt() and 0xFF)

    when (targetAddress) {
      is TargetAddress.IPv4 -> bodyStream.write(targetAddress.bytes)
      is TargetAddress.Domain -> {
        val domainBytes = targetAddress.utf8Bytes
        bodyStream.write(domainBytes.size and 0xFF)
        bodyStream.write(domainBytes)
      }
      is TargetAddress.IPv6 -> bodyStream.write(targetAddress.bytes)
    }

    if (initialPayload.isNotEmpty()) {
      bodyStream.write(initialPayload)
    }

    val plainBody = bodyStream.toByteArray()
    val encryptedBody = ChatV2Crypto.xorBuf(plainBody, sessionKey)

    val packet = ByteArray(nonce.size + encryptedBody.size + trailer.size)
    System.arraycopy(nonce, 0, packet, 0, nonce.size)
    System.arraycopy(encryptedBody, 0, packet, nonce.size, encryptedBody.size)
    System.arraycopy(trailer, 0, packet, nonce.size + encryptedBody.size, trailer.size)

    return InitPacketResult(
      packetBytes = packet,
      nonce = nonce,
      sessionKey = sessionKey,
    )
  }
}
