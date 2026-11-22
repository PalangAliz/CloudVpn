package com.example.vpn

import com.example.model.TunnelLogEntry
import com.example.protocol.ChatV2Packet
import com.example.protocol.ChatV2WebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * User-space IPv4 TUN packet forwarder bridging per-app Android VPN packets into
 * `chat.v2` Cloudflare Worker WebSocket sessions:
 * - TCP streams -> `ChatV2WebSocketClient` per 4-tuple flow
 * - UDP port 53 DNS queries -> protected `DatagramSocket` to `1.1.1.1:53`
 */
class TunPacketForwarder(
  private val tunFd: FileDescriptor,
  private val okHttpClient: OkHttpClient,
  private val workerWsUrl: String,
  private val uuidToken: String,
  private val socketProtector: (DatagramSocket) -> Boolean,
) {

  private data class TcpFlowKey(
    val srcIpInt: Int,
    val srcPort: Int,
    val dstIpInt: Int,
    val dstPort: Int,
  )

  private enum class TcpState {
    SYN_RECEIVED,
    ESTABLISHED,
    CLOSED,
  }

  private inner class TcpFlowSession(
    val key: TcpFlowKey,
    val clientIp: ByteArray,
    val clientPort: Int,
    val remoteIp: ByteArray,
    val remotePort: Int,
    initialClientSeq: Long,
  ) {
    val flowLock = Any()
    @Volatile
    var tcpState = TcpState.SYN_RECEIVED
    var clientNextSeq: Long = (initialClientSeq + 1L) and 0xFFFFFFFFL
    var serverSeq: Long = (System.nanoTime() and 0x7FFFFFFFL)
    var wsClient: ChatV2WebSocketClient? = null

    fun start() {
      val dstHost = IpPacketCodec.ipv4BytesToString(remoteIp)
      val client = ChatV2WebSocketClient(
        okHttpClient = okHttpClient,
        workerWsUrl = workerWsUrl,
        uuidToken = uuidToken,
        targetHost = dstHost,
        targetPort = remotePort,
        command = ChatV2Packet.CMD_TCP,
        listener = object : ChatV2WebSocketClient.TunnelListener {
          override fun onHandshakeVerified() {
            synchronized(flowLock) {
              if (tcpState == TcpState.CLOSED) return
              val synAck = IpPacketCodec.buildTcpPacket(
                srcIp = remoteIp,
                dstIp = clientIp,
                srcPort = remotePort,
                dstPort = clientPort,
                seqNum = serverSeq,
                ackNum = clientNextSeq,
                flags = IpPacketCodec.TCP_SYN or IpPacketCodec.TCP_ACK,
                mssOption = MAX_TCP_SEGMENT,
              )
              writeToTun(synAck)
              serverSeq = (serverSeq + 1L) and 0xFFFFFFFFL
              tcpState = TcpState.ESTABLISHED
            }
          }

          override fun onDataReceived(plainData: ByteArray) {
            synchronized(flowLock) {
              if (tcpState != TcpState.ESTABLISHED) return
              var offset = 0
              while (offset < plainData.size) {
                val chunkLen = minOf(MAX_TCP_SEGMENT, plainData.size - offset)
                val chunk = plainData.copyOfRange(offset, offset + chunkLen)
                val pkt = IpPacketCodec.buildTcpPacket(
                  srcIp = remoteIp,
                  dstIp = clientIp,
                  srcPort = remotePort,
                  dstPort = clientPort,
                  seqNum = serverSeq,
                  ackNum = clientNextSeq,
                  flags = IpPacketCodec.TCP_PSH or IpPacketCodec.TCP_ACK,
                  payload = chunk,
                )
                writeToTun(pkt)
                serverSeq = (serverSeq + chunkLen.toLong()) and 0xFFFFFFFFL
                offset += chunkLen
              }
            }
          }

          override fun onClosed(reason: String) {
            sendFinAndCleanup()
          }

          override fun onFailure(error: Throwable) {
            TunnelStateHolder.log(
              TunnelLogEntry.LogLevel.WARN,
              "TCP",
              "$dstHost:$remotePort failed: ${error.message}",
            )
            sendRstAndCleanup()
          }
        },
      )
      wsClient = client
      client.connect()
    }

    fun onClientPacket(flags: Int, seqNum: Long, payload: ByteArray) {
      synchronized(flowLock) {
        if (tcpState == TcpState.CLOSED) return

        if ((flags and IpPacketCodec.TCP_RST) != 0) {
          closeSession()
          return
        }

        if (payload.isNotEmpty() && tcpState == TcpState.ESTABLISHED) {
          if (seqNum == clientNextSeq) {
            clientNextSeq = (clientNextSeq + payload.size.toLong()) and 0xFFFFFFFFL
            wsClient?.sendData(payload)
            val ackPkt = IpPacketCodec.buildTcpPacket(
              srcIp = remoteIp,
              dstIp = clientIp,
              srcPort = remotePort,
              dstPort = clientPort,
              seqNum = serverSeq,
              ackNum = clientNextSeq,
              flags = IpPacketCodec.TCP_ACK,
            )
            writeToTun(ackPkt)
          } else {
            // Re-ACK expected sequence on out-of-order / duplicate segment
            val dupAck = IpPacketCodec.buildTcpPacket(
              srcIp = remoteIp,
              dstIp = clientIp,
              srcPort = remotePort,
              dstPort = clientPort,
              seqNum = serverSeq,
              ackNum = clientNextSeq,
              flags = IpPacketCodec.TCP_ACK,
            )
            writeToTun(dupAck)
          }
        }

        if ((flags and IpPacketCodec.TCP_FIN) != 0) {
          clientNextSeq = (clientNextSeq + 1L) and 0xFFFFFFFFL
          val finAck = IpPacketCodec.buildTcpPacket(
            srcIp = remoteIp,
            dstIp = clientIp,
            srcPort = remotePort,
            dstPort = clientPort,
            seqNum = serverSeq,
            ackNum = clientNextSeq,
            flags = IpPacketCodec.TCP_FIN or IpPacketCodec.TCP_ACK,
          )
          writeToTun(finAck)
          serverSeq = (serverSeq + 1L) and 0xFFFFFFFFL
          closeSession()
        }
      }
    }

    private fun sendFinAndCleanup() {
      synchronized(flowLock) {
        if (tcpState == TcpState.CLOSED) return
        if (tcpState == TcpState.ESTABLISHED) {
          val finAck = IpPacketCodec.buildTcpPacket(
            srcIp = remoteIp,
            dstIp = clientIp,
            srcPort = remotePort,
            dstPort = clientPort,
            seqNum = serverSeq,
            ackNum = clientNextSeq,
            flags = IpPacketCodec.TCP_FIN or IpPacketCodec.TCP_ACK,
          )
          writeToTun(finAck)
        }
        closeSession()
      }
    }

    private fun sendRstAndCleanup() {
      synchronized(flowLock) {
        if (tcpState == TcpState.CLOSED) return
        val rst = IpPacketCodec.buildTcpPacket(
          srcIp = remoteIp,
          dstIp = clientIp,
          srcPort = remotePort,
          dstPort = clientPort,
          seqNum = serverSeq,
          ackNum = clientNextSeq,
          flags = IpPacketCodec.TCP_RST or IpPacketCodec.TCP_ACK,
        )
        writeToTun(rst)
        closeSession()
      }
    }

    fun closeSession() {
      synchronized(flowLock) {
        if (tcpState == TcpState.CLOSED) return
        tcpState = TcpState.CLOSED
        tcpFlows.remove(key)
        wsClient?.close("TUN flow closed")
        wsClient = null
      }
    }
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val running = AtomicBoolean(false)
  private val tcpFlows = ConcurrentHashMap<TcpFlowKey, TcpFlowSession>()
  private val writeLock = Any()
  private val dnsDropCounter = AtomicLong(0)

  private val tunInput = FileInputStream(tunFd)
  private val tunOutput = FileOutputStream(tunFd)

  fun start() {
    if (!running.compareAndSet(false, true)) return
    scope.launch {
      val buffer = ByteArray(32767)
      while (running.get() && isActive) {
        val len = try {
          tunInput.read(buffer)
        } catch (_: Exception) {
          break
        }
        if (len <= 0) continue
        if (len < 20) continue
        val packet = buffer.copyOfRange(0, len)
        handleIpPacket(packet)
      }
    }
  }

  private fun handleIpPacket(packet: ByteArray) {
    val version = (packet[0].toInt() ushr 4) and 0xF
    if (version != 4) return

    val ihl = (packet[0].toInt() and 0xF) * 4
    if (ihl < 20 || packet.size < ihl) return
    val totalLen = IpPacketCodec.readUint16(packet, 2).coerceAtMost(packet.size)
    if (totalLen < ihl) return

    val protocol = packet[9].toInt() and 0xFF
    val srcIp = packet.copyOfRange(12, 16)
    val dstIp = packet.copyOfRange(16, 20)

    when (protocol) {
      IpPacketCodec.PROTO_TCP -> handleTcpPacket(packet, ihl, totalLen, srcIp, dstIp)
      IpPacketCodec.PROTO_UDP -> handleUdpPacket(packet, ihl, totalLen, srcIp, dstIp)
    }
  }

  private fun handleTcpPacket(
    packet: ByteArray,
    ihl: Int,
    totalLen: Int,
    srcIp: ByteArray,
    dstIp: ByteArray,
  ) {
    if (totalLen < ihl + 20) return
    val srcPort = IpPacketCodec.readUint16(packet, ihl)
    val dstPort = IpPacketCodec.readUint16(packet, ihl + 2)
    val seqNum = IpPacketCodec.readUint32(packet, ihl + 4)
    val dataOffset = ((packet[ihl + 12].toInt() ushr 4) and 0xF) * 4
    if (dataOffset < 20 || totalLen < ihl + dataOffset) return
    val flags = packet[ihl + 13].toInt() and 0xFF
    val payloadOffset = ihl + dataOffset
    val payload = if (totalLen > payloadOffset) {
      packet.copyOfRange(payloadOffset, totalLen)
    } else {
      ByteArray(0)
    }

    val key = TcpFlowKey(
      srcIpInt = IpPacketCodec.ipv4ToInt(srcIp),
      srcPort = srcPort,
      dstIpInt = IpPacketCodec.ipv4ToInt(dstIp),
      dstPort = dstPort,
    )

    val isSynOnly = (flags and IpPacketCodec.TCP_SYN) != 0 && (flags and IpPacketCodec.TCP_ACK) == 0
    if (isSynOnly) {
      tcpFlows.remove(key)?.closeSession()
      val session = TcpFlowSession(
        key = key,
        clientIp = srcIp,
        clientPort = srcPort,
        remoteIp = dstIp,
        remotePort = dstPort,
        initialClientSeq = seqNum,
      )
      tcpFlows[key] = session
      session.start()
      return
    }

    val existing = tcpFlows[key] ?: return
    existing.onClientPacket(flags, seqNum, payload)
  }

  private fun handleUdpPacket(
    packet: ByteArray,
    ihl: Int,
    totalLen: Int,
    srcIp: ByteArray,
    dstIp: ByteArray,
  ) {
    if (totalLen < ihl + 8) return
    val srcPort = IpPacketCodec.readUint16(packet, ihl)
    val dstPort = IpPacketCodec.readUint16(packet, ihl + 2)
    if (dstPort != 53) {
      // Non-DNS UDP is silently dropped in TCP-only Worker mode
      dnsDropCounter.incrementAndGet()
      return
    }

    val dnsPayload = packet.copyOfRange(ihl + 8, totalLen)
    if (dnsPayload.isEmpty()) return

    scope.launch {
      try {
        DatagramSocket().use { udpSocket ->
          socketProtector(udpSocket)
          udpSocket.soTimeout = 4000
          val dnsAddr = InetAddress.getByName("1.1.1.1")
          udpSocket.send(DatagramPacket(dnsPayload, dnsPayload.size, dnsAddr, 53))
          val respBuf = ByteArray(4096)
          val respPkt = DatagramPacket(respBuf, respBuf.size)
          udpSocket.receive(respPkt)
          val dnsResp = respBuf.copyOfRange(0, respPkt.length)
          val ipResp = IpPacketCodec.buildUdpPacket(
            srcIp = dstIp,
            dstIp = srcIp,
            srcPort = dstPort,
            dstPort = srcPort,
            payload = dnsResp,
          )
          writeToTun(ipResp)
        }
      } catch (_: Exception) {
        // Ignore transient DNS timeouts
      }
    }
  }

  private fun writeToTun(packet: ByteArray) {
    if (!running.get()) return
    synchronized(writeLock) {
      try {
        tunOutput.write(packet)
      } catch (_: Exception) {
      }
    }
  }

  fun stop() {
    if (!running.compareAndSet(true, false)) return
    tcpFlows.values.toList().forEach { it.closeSession() }
    tcpFlows.clear()
    scope.cancel()
  }

  companion object {
    private const val MAX_TCP_SEGMENT = 1380
  }
}
