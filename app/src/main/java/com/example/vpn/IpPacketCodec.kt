package com.example.vpn

/**
 * Pure-Kotlin IPv4 / TCP / UDP packet builder and checksum calculator
 * used by [TunPacketForwarder] for user-space TUN bridging.
 */
object IpPacketCodec {

  const val PROTO_TCP = 6
  const val PROTO_UDP = 17

  const val TCP_FIN = 0x01
  const val TCP_SYN = 0x02
  const val TCP_RST = 0x04
  const val TCP_PSH = 0x08
  const val TCP_ACK = 0x10

  fun readUint16(buf: ByteArray, offset: Int): Int {
    return ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)
  }

  fun readUint32(buf: ByteArray, offset: Int): Long {
    return ((buf[offset].toLong() and 0xFF) shl 24) or
      ((buf[offset + 1].toLong() and 0xFF) shl 16) or
      ((buf[offset + 2].toLong() and 0xFF) shl 8) or
      (buf[offset + 3].toLong() and 0xFF)
  }

  fun writeUint16(buf: ByteArray, offset: Int, value: Int) {
    buf[offset] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 1] = (value and 0xFF).toByte()
  }

  fun writeUint32(buf: ByteArray, offset: Int, value: Long) {
    buf[offset] = ((value ushr 24) and 0xFF).toByte()
    buf[offset + 1] = ((value ushr 16) and 0xFF).toByte()
    buf[offset + 2] = ((value ushr 8) and 0xFF).toByte()
    buf[offset + 3] = (value and 0xFF).toByte()
  }

  fun ipv4BytesToString(ipBytes: ByteArray): String {
    require(ipBytes.size == 4)
    return "${ipBytes[0].toInt() and 0xFF}.${ipBytes[1].toInt() and 0xFF}." +
      "${ipBytes[2].toInt() and 0xFF}.${ipBytes[3].toInt() and 0xFF}"
  }

  fun ipv4ToInt(ipBytes: ByteArray): Int {
    return ((ipBytes[0].toInt() and 0xFF) shl 24) or
      ((ipBytes[1].toInt() and 0xFF) shl 16) or
      ((ipBytes[2].toInt() and 0xFF) shl 8) or
      (ipBytes[3].toInt() and 0xFF)
  }

  fun computeChecksum(data: ByteArray, offset: Int, length: Int, initialSum: Long = 0L): Int {
    var sum = initialSum
    var idx = offset
    val end = offset + length
    while (idx + 1 < end) {
      val word = ((data[idx].toInt() and 0xFF) shl 8) or (data[idx + 1].toInt() and 0xFF)
      sum += word.toLong()
      idx += 2
    }
    if (idx < end) {
      sum += ((data[idx].toInt() and 0xFF) shl 8).toLong()
    }
    while ((sum ushr 16) != 0L) {
      sum = (sum and 0xFFFFL) + (sum ushr 16)
    }
    return (sum.inv() and 0xFFFFL).toInt()
  }

  private fun pseudoHeaderSum(
    srcIp: ByteArray,
    dstIp: ByteArray,
    protocol: Int,
    transportLength: Int,
  ): Long {
    var sum = 0L
    sum += (((srcIp[0].toInt() and 0xFF) shl 8) or (srcIp[1].toInt() and 0xFF)).toLong()
    sum += (((srcIp[2].toInt() and 0xFF) shl 8) or (srcIp[3].toInt() and 0xFF)).toLong()
    sum += (((dstIp[0].toInt() and 0xFF) shl 8) or (dstIp[1].toInt() and 0xFF)).toLong()
    sum += (((dstIp[2].toInt() and 0xFF) shl 8) or (dstIp[3].toInt() and 0xFF)).toLong()
    sum += protocol.toLong()
    sum += transportLength.toLong()
    return sum
  }

  fun buildTcpPacket(
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    seqNum: Long,
    ackNum: Long,
    flags: Int,
    windowSize: Int = 65535,
    mssOption: Int? = null,
    payload: ByteArray = ByteArray(0),
  ): ByteArray {
    val tcpHeaderLen = if (mssOption != null) 24 else 20
    val totalLen = 20 + tcpHeaderLen + payload.size
    val pkt = ByteArray(totalLen)

    // IPv4 Header (20 bytes)
    pkt[0] = 0x45.toByte()
    pkt[1] = 0x00
    writeUint16(pkt, 2, totalLen)
    writeUint16(pkt, 4, (seqNum and 0xFFFFL).toInt())
    pkt[6] = 0x40 // Don't Fragment
    pkt[7] = 0x00
    pkt[8] = 64 // TTL
    pkt[9] = PROTO_TCP.toByte()
    // Checksum at [10..11] filled below
    System.arraycopy(srcIp, 0, pkt, 12, 4)
    System.arraycopy(dstIp, 0, pkt, 16, 4)
    val ipChecksum = computeChecksum(pkt, 0, 20)
    writeUint16(pkt, 10, ipChecksum)

    // TCP Header
    val tcpOffset = 20
    writeUint16(pkt, tcpOffset, srcPort)
    writeUint16(pkt, tcpOffset + 2, dstPort)
    writeUint32(pkt, tcpOffset + 4, seqNum)
    writeUint32(pkt, tcpOffset + 8, ackNum)
    pkt[tcpOffset + 12] = (((tcpHeaderLen / 4) shl 4) and 0xF0).toByte()
    pkt[tcpOffset + 13] = (flags and 0xFF).toByte()
    writeUint16(pkt, tcpOffset + 14, windowSize)
    // Checksum at tcpOffset + 16..17 filled below
    writeUint16(pkt, tcpOffset + 18, 0) // Urgent pointer

    if (mssOption != null) {
      pkt[tcpOffset + 20] = 2 // Kind = MSS
      pkt[tcpOffset + 21] = 4 // Length = 4
      writeUint16(pkt, tcpOffset + 22, mssOption)
    }

    if (payload.isNotEmpty()) {
      System.arraycopy(payload, 0, pkt, tcpOffset + tcpHeaderLen, payload.size)
    }

    val tcpLen = tcpHeaderLen + payload.size
    val pseudoSum = pseudoHeaderSum(srcIp, dstIp, PROTO_TCP, tcpLen)
    val tcpChecksum = computeChecksum(pkt, tcpOffset, tcpLen, pseudoSum)
    writeUint16(pkt, tcpOffset + 16, tcpChecksum)

    return pkt
  }

  fun buildUdpPacket(
    srcIp: ByteArray,
    dstIp: ByteArray,
    srcPort: Int,
    dstPort: Int,
    payload: ByteArray,
  ): ByteArray {
    val udpLen = 8 + payload.size
    val totalLen = 20 + udpLen
    val pkt = ByteArray(totalLen)

    // IPv4 Header
    pkt[0] = 0x45.toByte()
    pkt[1] = 0x00
    writeUint16(pkt, 2, totalLen)
    writeUint16(pkt, 4, (System.nanoTime() and 0xFFFFL).toInt())
    pkt[6] = 0x00
    pkt[7] = 0x00
    pkt[8] = 64
    pkt[9] = PROTO_UDP.toByte()
    System.arraycopy(srcIp, 0, pkt, 12, 4)
    System.arraycopy(dstIp, 0, pkt, 16, 4)
    writeUint16(pkt, 10, computeChecksum(pkt, 0, 20))

    // UDP Header
    val udpOffset = 20
    writeUint16(pkt, udpOffset, srcPort)
    writeUint16(pkt, udpOffset + 2, dstPort)
    writeUint16(pkt, udpOffset + 4, udpLen)
    System.arraycopy(payload, 0, pkt, udpOffset + 8, payload.size)

    val pseudoSum = pseudoHeaderSum(srcIp, dstIp, PROTO_UDP, udpLen)
    var udpChecksum = computeChecksum(pkt, udpOffset, udpLen, pseudoSum)
    if (udpChecksum == 0) udpChecksum = 0xFFFF
    writeUint16(pkt, udpOffset + 6, udpChecksum)

    return pkt
  }
}
