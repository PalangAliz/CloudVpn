package com.example.vpn

import com.example.model.ConnectionStatus
import com.example.model.TunnelLogEntry
import com.example.model.TunnelRuntimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Process-wide singleton holding live VPN/Proxy connection state, traffic telemetry,
 * and bounded session logs observed by the Jetpack Compose UI.
 */
object TunnelStateHolder {

  private const val MAX_LOG_ENTRIES = 200

  private val _state = MutableStateFlow(TunnelRuntimeState())
  val state: StateFlow<TunnelRuntimeState> = _state.asStateFlow()

  private val activeSessionsCounter = AtomicInteger(0)
  private val totalConnectionsCounter = AtomicLong(0)
  private val uploadedBytesCounter = AtomicLong(0)
  private val downloadedBytesCounter = AtomicLong(0)

  fun setConnecting(workerHost: String, cleanIp: String?) {
    activeSessionsCounter.set(0)
    totalConnectionsCounter.set(0)
    uploadedBytesCounter.set(0)
    downloadedBytesCounter.set(0)
    _state.update {
      it.copy(
        status = ConnectionStatus.CONNECTING,
        errorMessage = null,
        activeWorkerHost = workerHost,
        activeCleanIp = cleanIp?.takeIf { ip -> ip.isNotBlank() },
        connectedSinceMs = null,
        activeSessions = 0,
        totalConnections = 0,
        bytesUploaded = 0,
        bytesDownloaded = 0,
      )
    }
    log(
      level = TunnelLogEntry.LogLevel.INFO,
      tag = "TUNNEL",
      message = "Connecting to $workerHost" +
        (cleanIp?.takeIf { it.isNotBlank() }?.let { " via Clean IP $it" } ?: ""),
    )
  }

  fun setConnected(localProxyPort: Int?) {
    _state.update {
      it.copy(
        status = ConnectionStatus.CONNECTED,
        errorMessage = null,
        connectedSinceMs = System.currentTimeMillis(),
        localProxyRunningPort = localProxyPort,
      )
    }
    log(
      level = TunnelLogEntry.LogLevel.SUCCESS,
      tag = "TUNNEL",
      message = "Tunnel active (chat.v2)" +
        (localProxyPort?.let { " • Local proxy 127.0.0.1:$it" } ?: ""),
    )
  }

  fun setDisconnected(reason: String? = null) {
    activeSessionsCounter.set(0)
    _state.update {
      it.copy(
        status = ConnectionStatus.DISCONNECTED,
        errorMessage = null,
        connectedSinceMs = null,
        activeSessions = 0,
        localProxyRunningPort = null,
      )
    }
    log(
      level = TunnelLogEntry.LogLevel.INFO,
      tag = "TUNNEL",
      message = reason ?: "Tunnel disconnected",
    )
  }

  fun setError(message: String) {
    activeSessionsCounter.set(0)
    _state.update {
      it.copy(
        status = ConnectionStatus.ERROR,
        errorMessage = message,
        connectedSinceMs = null,
        activeSessions = 0,
        localProxyRunningPort = null,
      )
    }
    log(level = TunnelLogEntry.LogLevel.ERROR, tag = "ERROR", message = message)
  }

  fun onSessionOpened() {
    val active = activeSessionsCounter.incrementAndGet()
    val total = totalConnectionsCounter.incrementAndGet()
    _state.update { it.copy(activeSessions = active, totalConnections = total) }
  }

  fun onSessionClosed() {
    val active = activeSessionsCounter.decrementAndGet().coerceAtLeast(0)
    activeSessionsCounter.set(active)
    _state.update { it.copy(activeSessions = active) }
  }

  fun recordUpload(bytes: Int) {
    if (bytes <= 0) return
    val total = uploadedBytesCounter.addAndGet(bytes.toLong())
    _state.update { it.copy(bytesUploaded = total) }
  }

  fun recordDownload(bytes: Int) {
    if (bytes <= 0) return
    val total = downloadedBytesCounter.addAndGet(bytes.toLong())
    _state.update { it.copy(bytesDownloaded = total) }
  }

  fun log(level: TunnelLogEntry.LogLevel, tag: String, message: String) {
    val entry = TunnelLogEntry(
      timestampMs = System.currentTimeMillis(),
      level = level,
      tag = tag,
      message = message,
    )
    _state.update { current ->
      val updated = (current.logs + entry).takeLast(MAX_LOG_ENTRIES)
      current.copy(logs = updated)
    }
  }

  fun clearLogs() {
    _state.update { it.copy(logs = emptyList()) }
  }
}
