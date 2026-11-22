package com.example.protocol

import com.example.model.TunnelLogEntry
import com.example.vpn.TunnelStateHolder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages a single `chat.v2` WebSocket tunnel stream to the Cloudflare Worker:
 * 1. Upgrades HTTP/TLS with `Sec-WebSocket-Protocol: chat.v2`.
 * 2. Sends encrypted `parseInit` packet (`nonce || xor(body, key) || trailer`).
 * 3. Verifies 16-byte HMAC-SHA256 ACK (`xor(hmac(key, "OK")[0..15], key)`).
 * 4. Encrypts/decrypts all subsequent binary payload frames via repeating XOR with `sessionKey`.
 */
class ChatV2WebSocketClient(
  private val okHttpClient: OkHttpClient,
  private val workerWsUrl: String,
  private val uuidToken: String,
  private val targetHost: String,
  private val targetPort: Int,
  private val command: Byte = ChatV2Packet.CMD_TCP,
  private val initialPayload: ByteArray = ByteArray(0),
  private val listener: TunnelListener,
) {

  interface TunnelListener {
    fun onHandshakeVerified()
    fun onDataReceived(plainData: ByteArray)
    fun onClosed(reason: String)
    fun onFailure(error: Throwable)
  }

  private enum class State {
    CONNECTING,
    AWAITING_ACK,
    OPEN,
    CLOSED,
  }

  private val state = AtomicReference(State.CONNECTING)
  private val lock = Any()
  private val pendingOutbound = ArrayDeque<ByteArray>()

  @Volatile
  private var webSocket: WebSocket? = null

  @Volatile
  private var sessionKey: ByteArray? = null

  fun connect() {
    val request = Request.Builder()
      .url(workerWsUrl)
      .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
      .build()

    TunnelStateHolder.onSessionOpened()

    webSocket = okHttpClient.newWebSocket(
      request,
      object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
          val initResult = try {
            ChatV2Packet.buildInitPacket(
              uuidToken = uuidToken,
              targetHost = targetHost,
              targetPort = targetPort,
              command = command,
              initialPayload = initialPayload,
            )
          } catch (e: Exception) {
            state.set(State.CLOSED)
            webSocket.close(1008, "Init build failure")
            TunnelStateHolder.onSessionClosed()
            listener.onFailure(e)
            return
          }

          sessionKey = initResult.sessionKey
          state.set(State.AWAITING_ACK)
          val sent = webSocket.send(initResult.packetBytes.toByteString())
          if (sent) {
            TunnelStateHolder.recordUpload(initResult.packetBytes.size)
          } else {
            state.set(State.CLOSED)
            TunnelStateHolder.onSessionClosed()
            listener.onFailure(IllegalStateException("Failed to send chat.v2 init frame"))
          }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
          val key = sessionKey ?: return
          val raw = bytes.toByteArray()
          TunnelStateHolder.recordDownload(raw.size)

          when (state.get()) {
            State.AWAITING_ACK -> {
              if (ChatV2Crypto.verifyAck(raw, key)) {
                state.set(State.OPEN)
                flushPendingOutbound(webSocket, key)
                listener.onHandshakeVerified()
              } else {
                state.set(State.CLOSED)
                TunnelStateHolder.log(
                  TunnelLogEntry.LogLevel.ERROR,
                  "ACK",
                  "Invalid HMAC ACK from Worker for $targetHost:$targetPort (len=${raw.size})",
                )
                webSocket.close(1002, "ACK verification failed")
                TunnelStateHolder.onSessionClosed()
                listener.onFailure(SecurityException("Worker chat.v2 ACK verification failed"))
              }
            }

            State.OPEN -> {
              if (raw.isNotEmpty()) {
                val plain = ChatV2Crypto.xorBuf(raw, key)
                listener.onDataReceived(plain)
              }
            }

            else -> Unit
          }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
          // chat.v2 operates strictly on binary frames; log unexpected text frame for diagnostics.
          TunnelStateHolder.log(
            TunnelLogEntry.LogLevel.WARN,
            "WS",
            "Unexpected text frame from Worker: ${text.take(80)}",
          )
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
          webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
          val prev = state.getAndSet(State.CLOSED)
          if (prev != State.CLOSED) {
            TunnelStateHolder.onSessionClosed()
            listener.onClosed(reason.ifEmpty { "Closed ($code)" })
          }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
          val prev = state.getAndSet(State.CLOSED)
          if (prev != State.CLOSED) {
            TunnelStateHolder.onSessionClosed()
            val detail = response?.let { "HTTP ${it.code}: ${t.message}" } ?: (t.message ?: "WebSocket failure")
            listener.onFailure(IllegalStateException(detail, t))
          }
        }
      },
    )
  }

  /**
   * Sends application payload through the `chat.v2` session (XOR-encrypted with `sessionKey`).
   * Queues safely if called while the ACK handshake is still in flight.
   */
  fun sendData(plainData: ByteArray): Boolean {
    if (plainData.isEmpty()) return true
    val currentState = state.get()
    if (currentState == State.CLOSED) return false

    synchronized(lock) {
      if (state.get() == State.OPEN) {
        val ws = webSocket ?: return false
        val key = sessionKey ?: return false
        val encrypted = ChatV2Crypto.xorBuf(plainData, key)
        val ok = ws.send(encrypted.toByteString())
        if (ok) {
          TunnelStateHolder.recordUpload(encrypted.size)
        }
        return ok
      } else if (state.get() != State.CLOSED) {
        if (pendingOutbound.size < MAX_PENDING_CHUNKS) {
          pendingOutbound.addLast(plainData.copyOf())
          return true
        }
        return false
      }
      return false
    }
  }

  private fun flushPendingOutbound(ws: WebSocket, key: ByteArray) {
    synchronized(lock) {
      while (pendingOutbound.isNotEmpty()) {
        val chunk = pendingOutbound.removeFirst()
        val encrypted = ChatV2Crypto.xorBuf(chunk, key)
        if (ws.send(encrypted.toByteString())) {
          TunnelStateHolder.recordUpload(encrypted.size)
        }
      }
    }
  }

  fun close(reason: String = "Client closed") {
    val prev = state.getAndSet(State.CLOSED)
    synchronized(lock) {
      pendingOutbound.clear()
    }
    if (prev != State.CLOSED) {
      TunnelStateHolder.onSessionClosed()
      webSocket?.close(1000, reason)
    }
  }

  fun queueSizeBytes(): Long = webSocket?.queueSize() ?: 0L

  companion object {
    const val SUBPROTOCOL = "chat.v2"
    private const val MAX_PENDING_CHUNKS = 256
  }
}
