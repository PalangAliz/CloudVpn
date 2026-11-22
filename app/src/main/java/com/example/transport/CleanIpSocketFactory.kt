package com.example.transport

import com.example.model.CleanIpEndpoint
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * Custom [Socket] subclass that:
 * 1. Protects the underlying file descriptor via [socketProtector] (`VpnService.protect`)
 *    before connecting, preventing VPN routing loops.
 * 2. When [cleanEndpoint] is configured, redirects the TCP connection to the Clean IP
 *    (and optional Clean IP port) while OkHttp/Conscrypt still performs TLS SNI, certificate
 *    verification, and HTTP `Host` routing using the original Worker hostname.
 */
class ProtectedCleanSocket(
  private val cleanEndpoint: CleanIpEndpoint?,
  private val socketProtector: ((Socket) -> Boolean)?,
) : Socket() {

  override fun connect(endpoint: SocketAddress?) {
    connect(endpoint, 0)
  }

  override fun connect(endpoint: SocketAddress?, timeout: Int) {
    socketProtector?.invoke(this)
    val resolvedEndpoint = if (cleanEndpoint != null && endpoint is InetSocketAddress) {
      val targetPort = cleanEndpoint.port ?: endpoint.port
      val resolvedAddr = InetAddress.getByName(cleanEndpoint.host)
      InetSocketAddress(resolvedAddr, targetPort)
    } else {
      endpoint
    }
    super.connect(resolvedEndpoint, timeout)
  }
}

/**
 * [SocketFactory] producing [ProtectedCleanSocket] instances for OkHttp connections.
 */
class CleanIpSocketFactory(
  private val cleanEndpoint: CleanIpEndpoint?,
  private val socketProtector: ((Socket) -> Boolean)?,
) : SocketFactory() {

  override fun createSocket(): Socket = ProtectedCleanSocket(cleanEndpoint, socketProtector)

  override fun createSocket(host: String?, port: Int): Socket {
    return createSocket().apply {
      val targetHost = cleanEndpoint?.host ?: host
      val targetPort = cleanEndpoint?.port ?: port
      connect(InetSocketAddress(InetAddress.getByName(targetHost), targetPort))
    }
  }

  override fun createSocket(
    host: String?,
    port: Int,
    localHost: InetAddress?,
    localPort: Int,
  ): Socket {
    return createSocket().apply {
      bind(InetSocketAddress(localHost, localPort))
      val targetHost = cleanEndpoint?.host ?: host
      val targetPort = cleanEndpoint?.port ?: port
      connect(InetSocketAddress(InetAddress.getByName(targetHost), targetPort))
    }
  }

  override fun createSocket(host: InetAddress?, port: Int): Socket {
    return createSocket().apply {
      val targetAddr = cleanEndpoint?.let { InetAddress.getByName(it.host) } ?: host
      val targetPort = cleanEndpoint?.port ?: port
      connect(InetSocketAddress(targetAddr, targetPort))
    }
  }

  override fun createSocket(
    address: InetAddress?,
    port: Int,
    localAddress: InetAddress?,
    localPort: Int,
  ): Socket {
    return createSocket().apply {
      bind(InetSocketAddress(localAddress, localPort))
      val targetAddr = cleanEndpoint?.let { InetAddress.getByName(it.host) } ?: address
      val targetPort = cleanEndpoint?.port ?: port
      connect(InetSocketAddress(targetAddr, targetPort))
    }
  }
}

/**
 * [Dns] implementation that short-circuits hostname resolution to [cleanEndpoint.host] when
 * Clean IP mode is enabled, avoiding DNS queries for potentially blocked Worker domains.
 */
class CleanIpDns(
  private val workerHost: String,
  private val cleanEndpoint: CleanIpEndpoint?,
) : Dns {
  override fun lookup(hostname: String): List<InetAddress> {
    if (cleanEndpoint != null && hostname.equals(workerHost, ignoreCase = true)) {
      return listOf(InetAddress.getByName(cleanEndpoint.host))
    }
    return Dns.SYSTEM.lookup(hostname)
  }
}

/**
 * Factory building configured [OkHttpClient] instances for `chat.v2` WebSocket transport.
 */
object CleanIpOkHttpFactory {

  fun createClient(
    workerHost: String,
    cleanEndpoint: CleanIpEndpoint?,
    socketProtector: ((Socket) -> Boolean)?,
  ): OkHttpClient {
    return OkHttpClient.Builder()
      .socketFactory(CleanIpSocketFactory(cleanEndpoint, socketProtector))
      .dns(CleanIpDns(workerHost, cleanEndpoint))
      .connectTimeout(12, TimeUnit.SECONDS)
      .readTimeout(0, TimeUnit.MILLISECONDS)
      .writeTimeout(15, TimeUnit.SECONDS)
      .pingInterval(25, TimeUnit.SECONDS)
      .retryOnConnectionFailure(true)
      .build()
  }
}
