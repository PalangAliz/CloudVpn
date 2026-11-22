package com.example

import com.example.config.ConfigValidator
import com.example.model.ProxyConfig
import com.example.protocol.ChatV2Crypto
import com.example.protocol.ChatV2Packet
import com.example.protocol.TargetAddress
import com.example.vpn.IpPacketCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatV2ProtocolTest {

  @Test
  fun `session key derivation and xorBuf round trip match worker spec`() {
    val uuid = ProxyConfig.DEFAULT_UUID
    val nonce = ByteArray(16) { idx -> (idx * 13 + 7).toByte() }
    val key = ChatV2Crypto.deriveSessionKey(uuid, nonce)

    val tb = uuid.toByteArray(Charsets.UTF_8)
    for (i in 0 until 16) {
      assertEquals((tb[i % tb.size].toInt() xor nonce[i].toInt()).toByte(), key[i])
    }

    val plaintext = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n".toByteArray(Charsets.UTF_8)
    val encrypted = ChatV2Crypto.xorBuf(plaintext, key)
    val decrypted = ChatV2Crypto.xorBuf(encrypted, key)
    assertArrayEquals(plaintext, decrypted)
  }

  @Test
  fun `ack verification succeeds for expected hmac xor and fails on tampering`() {
    val nonce = ByteArray(16) { it.toByte() }
    val key = ChatV2Crypto.deriveSessionKey(ProxyConfig.DEFAULT_UUID, nonce)
    val expectedAck = ChatV2Crypto.computeExpectedAck(key)

    assertEquals(16, expectedAck.size)
    assertTrue(ChatV2Crypto.verifyAck(expectedAck, key))

    val tampered = expectedAck.copyOf().apply { this[0] = (this[0].toInt() xor 0x01).toByte() }
    assertFalse(ChatV2Crypto.verifyAck(tampered, key))
    assertFalse(ChatV2Crypto.verifyAck(expectedAck.copyOfRange(0, 15), key))
  }

  @Test
  fun `buildInitPacket encodes ipv4 domain and ipv6 targets compatibly with parseInit`() {
    val nonce = ByteArray(16) { (it + 1).toByte() }
    val trailer = ByteArray(8) { (0xA0 + it).toByte() }
    val initial = "HELLO".toByteArray(Charsets.UTF_8)

    // Domain target (at = 2)
    val domainRes = ChatV2Packet.buildInitPacket(
      uuidToken = ProxyConfig.DEFAULT_UUID,
      targetHost = "api.cloudflare.com",
      targetPort = 443,
      command = ChatV2Packet.CMD_TCP,
      initialPayload = initial,
      nonce = nonce,
      trailer = trailer,
    )
    assertArrayEquals(nonce, domainRes.packetBytes.copyOfRange(0, 16))
    assertArrayEquals(
      trailer,
      domainRes.packetBytes.copyOfRange(domainRes.packetBytes.size - 8, domainRes.packetBytes.size),
    )

    val encBody = domainRes.packetBytes.copyOfRange(16, domainRes.packetBytes.size - 8)
    val plainBody = ChatV2Crypto.xorBuf(encBody, domainRes.sessionKey)
    assertEquals(1, plainBody[0].toInt() and 0xFF) // cmd = 1
    assertEquals(443, ((plainBody[1].toInt() and 0xFF) shl 8) or (plainBody[2].toInt() and 0xFF))
    assertEquals(TargetAddress.AT_DOMAIN.toInt(), plainBody[3].toInt() and 0xFF)
    val domainLen = plainBody[4].toInt() and 0xFF
    assertEquals("api.cloudflare.com", String(plainBody.copyOfRange(5, 5 + domainLen), Charsets.UTF_8))
    assertArrayEquals(initial, plainBody.copyOfRange(5 + domainLen, plainBody.size))

    // IPv4 target (at = 1)
    val ipv4Res = ChatV2Packet.buildInitPacket(
      uuidToken = ProxyConfig.DEFAULT_UUID,
      targetHost = "1.1.1.1",
      targetPort = 80,
      nonce = nonce,
      trailer = trailer,
    )
    val ipv4Plain = ChatV2Crypto.xorBuf(
      ipv4Res.packetBytes.copyOfRange(16, ipv4Res.packetBytes.size - 8),
      ipv4Res.sessionKey,
    )
    assertEquals(TargetAddress.AT_IPV4.toInt(), ipv4Plain[3].toInt() and 0xFF)
    assertArrayEquals(byteArrayOf(1, 1, 1, 1), ipv4Plain.copyOfRange(4, 8))

    // IPv6 target (at = 3)
    val ipv6Res = ChatV2Packet.buildInitPacket(
      uuidToken = ProxyConfig.DEFAULT_UUID,
      targetHost = "[2606:4700:4700::1111]",
      targetPort = 443,
      nonce = nonce,
      trailer = trailer,
    )
    val ipv6Plain = ChatV2Crypto.xorBuf(
      ipv6Res.packetBytes.copyOfRange(16, ipv6Res.packetBytes.size - 8),
      ipv6Res.sessionKey,
    )
    assertEquals(TargetAddress.AT_IPV6.toInt(), ipv6Plain[3].toInt() and 0xFF)
    assertEquals(20, ipv6Plain.size) // 1(cmd)+2(port)+1(at)+16(ipv6)
  }

  @Test
  fun `config validator normalizes worker urls and parses clean ips`() {
    val httpsParsed = ConfigValidator.normalizeWorkerUrl("https://my-worker.workers.dev/ws")
    assertNotNull(httpsParsed)
    assertEquals("wss://my-worker.workers.dev/ws", httpsParsed!!.normalizedWsUrl)
    assertEquals("my-worker.workers.dev", httpsParsed.host)
    assertEquals(443, httpsParsed.port)

    val bareParsed = ConfigValidator.normalizeWorkerUrl("edge.example.com:8443/chat")
    assertNotNull(bareParsed)
    assertEquals("wss://edge.example.com:8443/chat", bareParsed!!.normalizedWsUrl)
    assertEquals(8443, bareParsed.port)

    assertNull(ConfigValidator.normalizeWorkerUrl("ftp://invalid.example.com"))
    assertTrue(ConfigValidator.isValidUuid(ProxyConfig.DEFAULT_UUID))
    assertFalse(ConfigValidator.isValidUuid("not-a-uuid"))

    val cleanIpPort = ConfigValidator.parseCleanIp("104.16.132.229:8443").getOrNull()
    assertNotNull(cleanIpPort)
    assertEquals("104.16.132.229", cleanIpPort!!.host)
    assertEquals(8443, cleanIpPort.port)

    val cleanIpv6 = ConfigValidator.parseCleanIp("[2606:4700::1]:443").getOrNull()
    assertNotNull(cleanIpv6)
    assertEquals("2606:4700::1", cleanIpv6!!.host)
    assertEquals(443, cleanIpv6.port)

    assertTrue(ConfigValidator.parseCleanIp("104.16.1.1:99999").isFailure)
  }

  @Test
  fun `ip packet codec builds valid ipv4 tcp packet with zeroed checksum verification`() {
    val pkt = IpPacketCodec.buildTcpPacket(
      srcIp = byteArrayOf(1, 1, 1, 1),
      dstIp = byteArrayOf(10, 233.toByte(), 233.toByte(), 1),
      srcPort = 443,
      dstPort = 51234,
      seqNum = 1000L,
      ackNum = 2000L,
      flags = IpPacketCodec.TCP_SYN or IpPacketCodec.TCP_ACK,
      mssOption = 1380,
    )
    assertEquals(44, pkt.size) // 20 IPv4 + 24 TCP with MSS
    // Verifying IPv4 header checksum over 20 bytes yields 0
    assertEquals(0, IpPacketCodec.computeChecksum(pkt, 0, 20))
  }
}
