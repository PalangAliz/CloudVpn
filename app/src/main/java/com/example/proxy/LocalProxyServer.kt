package com.example.proxy

import com.example.model.TunnelLogEntry
import com.example.protocol.ChatV2Packet
import com.example.protocol.ChatV2WebSocketClient
import com.example.vpn.TunnelStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Local SOCKS5 (RFC 1928), SOCKS4/4a, and HTTP CONNECT/HTTP proxy server listening on
 * `127.0.0.1:<port>`. Translates incoming local app TCP connections into `chat.v2`
 * WebSocket tunnel sessions to the Cloudflare Worker (supporting IPv4, Domain, and IPv6).
 */
class LocalProxyServer(
  private val port: Int,
  private val okHttpClient: OkHttpClient,
  private val workerWsUrl: String,
  private val uuidToken: String,
) {

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val running = AtomicBoolean(false)

  @Volatile
  private var serverSocket: ServerSocket? = null

  fun start(): Int {
    if (!running.compareAndSet(false, true)) return serverSocket?.localPort ?: port
    val server = ServerSocket(port, 64, InetAddress.getByName("127.0.0.1"))
    serverSocket = server
    val boundPort = server.localPort

    scope.launch {
      while (running.get() && isActive) {
        val client = try {
          server.accept()
        } catch (_: Exception) {
          break
        }
        scope.launch { handleClient(client) }
      }
    }
    return boundPort
  }

  fun stop() {
    if (!running.compareAndSet(true, false)) return
    try {
      serverSocket?.close()
    } catch (_: Exception) {
    }
    serverSocket = null
    scope.cancel()
  }

  private fun handleClient(socket: Socket) {
    try {
      socket.soTimeout = 30_000
      val input = BufferedInputStream(socket.getInputStream())
      val output = socket.getOutputStream()

      input.mark(1)
      val firstByte = input.read()
      if (firstByte == -1) {
        socket.close()
        return
      }
      input.reset()

      when (firstByte) {
        0x05 -> handleSocks5(socket, input, output)
        0x04 -> handleSocks4(socket, input, output)
        else -> handleHttpProxy(socket, input, output)
      }
    } catch (_: Exception) {
      try {
        socket.close()
      } catch (_: Exception) {
      }
    }
  }

  private fun handleSocks5(socket: Socket, input: InputStream, output: OutputStream) {
    val ver = input.read()
    val nMethods = input.read()
    if (ver != 0x05 || nMethods <= 0) {
      socket.close()
      return
    }
    val methods = readExact(input, nMethods) ?: run {
      socket.close()
      return
    }
    if (!methods.contains(0x00.toByte())) {
      output.write(byteArrayOf(0x05, 0xFF.toByte()))
      output.flush()
      socket.close()
      return
    }
    output.write(byteArrayOf(0x05, 0x00))
    output.flush()

    val header = readExact(input, 4) ?: run {
      socket.close()
      return
    }
    if (header[0] != 0x05.toByte() || header[1] != 0x01.toByte()) {
      output.write(byteArrayOf(0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
      output.flush()
      socket.close()
      return
    }

    val targetHost = when (header[3].toInt() and 0xFF) {
      0x01 -> {
        val raw = readExact(input, 4) ?: return socket.close()
        "${raw[0].toInt() and 0xFF}.${raw[1].toInt() and 0xFF}." +
          "${raw[2].toInt() and 0xFF}.${raw[3].toInt() and 0xFF}"
      }
      0x03 -> {
        val len = input.read()
        if (len <= 0) return socket.close()
        val domainBytes = readExact(input, len) ?: return socket.close()
        String(domainBytes, Charsets.UTF_8)
      }
      0x04 -> {
        val raw = readExact(input, 16) ?: return socket.close()
        InetAddress.getByAddress(raw).hostAddress ?: return socket.close()
      }
      else -> {
        output.write(byteArrayOf(0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
        socket.close()
        return
      }
    }

    val portBytes = readExact(input, 2) ?: return socket.close()
    val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

    bridgeToWorker(
      socket = socket,
      input = input,
      output = output,
      targetHost = targetHost,
      targetPort = targetPort,
      initialPayload = ByteArray(0),
      onHandshakeOk = {
        output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
        output.flush()
      },
      onHandshakeFail = {
        runCatching {
          output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0))
          output.flush()
        }
      },
    )
  }

  private fun handleSocks4(socket: Socket, input: InputStream, output: OutputStream) {
    val header = readExact(input, 8) ?: return socket.close()
    val cmd = header[1].toInt() and 0xFF
    if (cmd != 0x01) return socket.close()
    val targetPort = ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
    val ip = header.copyOfRange(4, 8)
    readNullTerminatedString(input) // userId

    val isSocks4a = ip[0] == 0.toByte() && ip[1] == 0.toByte() && ip[2] == 0.toByte() && ip[3] != 0.toByte()
    val targetHost = if (isSocks4a) {
      readNullTerminatedString(input)
    } else {
      "${ip[0].toInt() and 0xFF}.${ip[1].toInt() and 0xFF}." +
        "${ip[2].toInt() and 0xFF}.${ip[3].toInt() and 0xFF}"
    }

    bridgeToWorker(
      socket = socket,
      input = input,
      output = output,
      targetHost = targetHost,
      targetPort = targetPort,
      initialPayload = ByteArray(0),
      onHandshakeOk = {
        output.write(byteArrayOf(0x00, 0x5A, 0, 0, 0, 0, 0, 0))
        output.flush()
      },
      onHandshakeFail = {
        runCatching {
          output.write(byteArrayOf(0x00, 0x5B, 0, 0, 0, 0, 0, 0))
          output.flush()
        }
      },
    )
  }

  private fun handleHttpProxy(socket: Socket, input: InputStream, output: OutputStream) {
    val headerBytes = readHttpHeaderBlock(input) ?: return socket.close()
    val headerText = String(headerBytes, Charsets.ISO_8859_1)
    val lines = headerText.split("\r\n")
    val requestLine = lines.firstOrNull()?.trim() ?: return socket.close()
    val parts = requestLine.split(" ")
    if (parts.size < 3) return socket.close()

    val method = parts[0].uppercase()
    val target = parts[1]
    val version = parts[2]

    if (method == "CONNECT") {
      val (host, port) = parseHostPort(target, defaultPort = 443) ?: return socket.close()
      bridgeToWorker(
        socket = socket,
        input = input,
        output = output,
        targetHost = host,
        targetPort = port,
        initialPayload = ByteArray(0),
        onHandshakeOk = {
          output.write("HTTP/1.1 200 Connection Established\r\nProxy-Agent: EdgeTunnel\r\n\r\n".toByteArray())
          output.flush()
        },
        onHandshakeFail = {
          runCatching {
            output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            output.flush()
          }
        },
      )
    } else {
      val hostHeader = lines.drop(1)
        .firstOrNull { it.startsWith("Host:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()

      val (host, port, rewrittenPath) = parsePlainHttpTarget(target, hostHeader)
        ?: return socket.close()

      val rewrittenHeaders = buildString {
        append("$method $rewrittenPath $version\r\n")
        for (line in lines.drop(1)) {
          if (line.isEmpty()) continue
          if (line.startsWith("Proxy-Connection:", ignoreCase = true)) continue
          append(line).append("\r\n")
        }
        append("\r\n")
      }.toByteArray(Charsets.ISO_8859_1)

      bridgeToWorker(
        socket = socket,
        input = input,
        output = output,
        targetHost = host,
        targetPort = port,
        initialPayload = rewrittenHeaders,
        onHandshakeOk = {},
        onHandshakeFail = {
          runCatching {
            output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            output.flush()
          }
        },
      )
    }
  }

  private fun bridgeToWorker(
    socket: Socket,
    input: InputStream,
    output: OutputStream,
    targetHost: String,
    targetPort: Int,
    initialPayload: ByteArray,
    onHandshakeOk: () -> Unit,
    onHandshakeFail: () -> Unit,
  ) {
    val ackLatch = CountDownLatch(1)
    val handshakeError = AtomicReference<Throwable?>(null)
    val open = AtomicBoolean(true)

    val wsClient = ChatV2WebSocketClient(
      okHttpClient = okHttpClient,
      workerWsUrl = workerWsUrl,
      uuidToken = uuidToken,
      targetHost = targetHost,
      targetPort = targetPort,
      command = ChatV2Packet.CMD_TCP,
      initialPayload = initialPayload,
      listener = object : ChatV2WebSocketClient.TunnelListener {
        override fun onHandshakeVerified() {
          ackLatch.countDown()
        }

        override fun onDataReceived(plainData: ByteArray) {
          try {
            output.write(plainData)
            output.flush()
          } catch (_: Exception) {
            open.set(false)
            runCatching { socket.close() }
          }
        }

        override fun onClosed(reason: String) {
          open.set(false)
          ackLatch.countDown()
          runCatching { socket.close() }
        }

        override fun onFailure(error: Throwable) {
          handshakeError.compareAndSet(null, error)
          open.set(false)
          ackLatch.countDown()
          runCatching { socket.close() }
        }
      },
    )

    wsClient.connect()

    val ackReceived = ackLatch.await(15, TimeUnit.SECONDS)
    if (!ackReceived || handshakeError.get() != null || !open.get()) {
      val errMsg = handshakeError.get()?.message ?: "Handshake timeout"
      TunnelStateHolder.log(
        TunnelLogEntry.LogLevel.WARN,
        "PROXY",
        "$targetHost:$targetPort rejected: $errMsg",
      )
      onHandshakeFail()
      wsClient.close("Handshake aborted")
      runCatching { socket.close() }
      return
    }

    onHandshakeOk()
    socket.soTimeout = 0

    val buf = ByteArray(16384)
    try {
      while (open.get()) {
        val read = input.read(buf)
        if (read <= 0) break
        if (!wsClient.sendData(buf.copyOfRange(0, read))) break
      }
    } catch (_: Exception) {
    } finally {
      open.set(false)
      wsClient.close("Local socket EOF")
      runCatching { socket.close() }
    }
  }

  private fun readExact(input: InputStream, count: Int): ByteArray? {
    val out = ByteArray(count)
    var offset = 0
    while (offset < count) {
      val read = input.read(out, offset, count - offset)
      if (read <= 0) return null
      offset += read
    }
    return out
  }

  private fun readNullTerminatedString(input: InputStream): String {
    val baos = ByteArrayOutputStream()
    while (baos.size() < 512) {
      val b = input.read()
      if (b <= 0) break
      baos.write(b)
    }
    return baos.toString(Charsets.UTF_8.name())
  }

  private fun readHttpHeaderBlock(input: InputStream): ByteArray? {
    val baos = ByteArrayOutputStream(1024)
    var state = 0
    while (baos.size() < 16384) {
      val b = input.read()
      if (b == -1) return null
      baos.write(b)
      state = when {
        state == 0 && b == '\r'.code -> 1
        state == 1 && b == '\n'.code -> 2
        state == 2 && b == '\r'.code -> 3
        state == 3 && b == '\n'.code -> return baos.toByteArray()
        b == '\r'.code -> 1
        else -> 0
      }
    }
    return null
  }

  private fun parseHostPort(authority: String, defaultPort: Int): Pair<String, Int>? {
    val trimmed = authority.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("[")) {
      val endBracket = trimmed.indexOf(']')
      if (endBracket <= 1) return null
      val host = trimmed.substring(1, endBracket)
      val rest = trimmed.substring(endBracket + 1)
      val port = if (rest.startsWith(":")) rest.substring(1).toIntOrNull() ?: return null else defaultPort
      return host to port
    }
    val colon = trimmed.lastIndexOf(':')
    return if (colon > 0) {
      val host = trimmed.substring(0, colon)
      val port = trimmed.substring(colon + 1).toIntOrNull() ?: return null
      host to port
    } else {
      trimmed to defaultPort
    }
  }

  private fun parsePlainHttpTarget(target: String, hostHeader: String?): Triple<String, Int, String>? {
    return if (target.startsWith("http://", ignoreCase = true)) {
      val withoutScheme = target.substring(7)
      val slashIdx = withoutScheme.indexOf('/')
      val authority = if (slashIdx == -1) withoutScheme else withoutScheme.substring(0, slashIdx)
      val path = if (slashIdx == -1) "/" else withoutScheme.substring(slashIdx)
      val (host, port) = parseHostPort(authority, 80) ?: return null
      Triple(host, port, path)
    } else if (hostHeader != null) {
      val (host, port) = parseHostPort(hostHeader, 80) ?: return null
      Triple(host, port, target.ifEmpty { "/" })
    } else {
      null
    }
  }
}
