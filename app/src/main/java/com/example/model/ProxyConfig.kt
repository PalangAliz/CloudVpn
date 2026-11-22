package com.example.model

enum class RoutingMode(val wireName: String) {
  /** Only selected packages route through the Worker; unselected apps bypass directly. */
  PROXIED_APPS("proxied"),

  /** All packages route through the Worker except selected ones, which bypass directly. */
  BYPASS_SELECTED("bypass");

  companion object {
    fun fromWireName(value: String?): RoutingMode {
      return entries.firstOrNull { it.wireName.equals(value, ignoreCase = true) } ?: PROXIED_APPS
    }
  }
}

enum class ConnectionStatus {
  DISCONNECTED,
  CONNECTING,
  CONNECTED,
  ERROR,
}

data class CleanIpEndpoint(
  val host: String,
  val port: Int? = null,
) {
  fun displayString(): String = if (port != null) "$host:$port" else host
}

data class ProxyConfig(
  val workerUrl: String = DEFAULT_WORKER_URL,
  val uuid: String = DEFAULT_UUID,
  val cleanIp: String = "",
  val routingMode: RoutingMode = RoutingMode.BYPASS_SELECTED,
  val selectedPackages: Set<String> = emptySet(),
  val localProxyPort: Int = DEFAULT_LOCAL_PROXY_PORT,
  val enableLocalProxy: Boolean = true,
) {
  companion object {
    const val DEFAULT_UUID = "63ae8279-fafa-4dd0-b2ef-9d6a4ec3ffdd"
    const val DEFAULT_WORKER_URL = ""
    const val DEFAULT_LOCAL_PROXY_PORT = 10808
  }
}

data class TunnelLogEntry(
  val timestampMs: Long,
  val level: LogLevel,
  val tag: String,
  val message: String,
) {
  enum class LogLevel { INFO, SUCCESS, WARN, ERROR }
}

data class TunnelRuntimeState(
  val status: ConnectionStatus = ConnectionStatus.DISCONNECTED,
  val errorMessage: String? = null,
  val activeWorkerHost: String? = null,
  val activeCleanIp: String? = null,
  val connectedSinceMs: Long? = null,
  val activeSessions: Int = 0,
  val totalConnections: Long = 0,
  val bytesUploaded: Long = 0,
  val bytesDownloaded: Long = 0,
  val localProxyRunningPort: Int? = null,
  val logs: List<TunnelLogEntry> = emptyList(),
)
