package com.example.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.config.ConfigRepository
import com.example.config.ConfigValidator
import com.example.model.ProxyConfig
import com.example.model.RoutingMode
import com.example.model.TunnelLogEntry
import com.example.protocol.ChatV2Packet
import com.example.protocol.ChatV2WebSocketClient
import com.example.proxy.LocalProxyServer
import com.example.transport.CleanIpOkHttpFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Persistent foreground [VpnService] managing:
 * 1. Configuration validation (Worker URL, UUID, Clean IP, Per-App selection).
 * 2. Initial `chat.v2` handshake & HMAC-SHA256 ACK verification with the Worker.
 * 3. Per-App routing via `VpnService.Builder.addAllowedApplication` / `addDisallowedApplication`.
 * 4. Local SOCKS5/HTTP proxy server + TUN IPv4 forwarder (`TunPacketForwarder`).
 */
class EdgeTunnelVpnService : VpnService() {

  private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var startupJob: Job? = null

  @Volatile
  private var tunInterface: ParcelFileDescriptor? = null

  @Volatile
  private var localProxyServer: LocalProxyServer? = null

  @Volatile
  private var tunForwarder: TunPacketForwarder? = null

  @Volatile
  private var okHttpClient: OkHttpClient? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_DISCONNECT -> {
        stopTunnel("Disconnected by user")
        return START_NOT_STICKY
      }

      ACTION_CONNECT, null -> {
        startForegroundCompat(
          buildNotification(
            title = getString(R.string.vpn_notification_title_connecting),
            text = "Validating endpoint & chat.v2 handshake…",
          )
        )
        startupJob?.cancel()
        startupJob = serviceScope.launch {
          startTunnelInternal()
        }
        return START_STICKY
      }
    }
    return START_STICKY
  }

  private suspend fun startTunnelInternal() {
    tearDownTransport()

    val config = ConfigRepository(applicationContext).getCurrentConfig()
    val parsedUrl = ConfigValidator.normalizeWorkerUrl(config.workerUrl)
    if (parsedUrl == null) {
      failAndStop("Invalid Worker URL. Enter a valid workers.dev or wss:// endpoint.")
      return
    }
    if (!ConfigValidator.isValidUuid(config.uuid)) {
      failAndStop("Invalid UUID format. Expected 8-4-4-4-12 UUID token.")
      return
    }
    val cleanIpResult = ConfigValidator.parseCleanIp(config.cleanIp)
    if (cleanIpResult.isFailure) {
      failAndStop(cleanIpResult.exceptionOrNull()?.message ?: "Invalid Clean IP")
      return
    }
    val cleanEndpoint = cleanIpResult.getOrNull()

    if (config.routingMode == RoutingMode.PROXIED_APPS && config.selectedPackages.isEmpty()) {
      failAndStop("Selected Use Proxy mode requires at least one selected app.")
      return
    }

    TunnelStateHolder.setConnecting(
      workerHost = parsedUrl.host,
      cleanIp = cleanEndpoint?.displayString(),
    )

    val client = CleanIpOkHttpFactory.createClient(
      workerHost = parsedUrl.host,
      cleanEndpoint = cleanEndpoint,
      socketProtector = { socket -> protect(socket) },
    )
    okHttpClient = client

    // Probe Worker with a real chat.v2 handshake + HMAC ACK check before bringing up TUN
    val probeError = verifyWorkerHandshake(
      client = client,
      workerWsUrl = parsedUrl.normalizedWsUrl,
      uuidToken = config.uuid,
    )
    if (probeError != null) {
      failAndStop("Worker handshake failed: $probeError")
      return
    }

    TunnelStateHolder.log(
      TunnelLogEntry.LogLevel.SUCCESS,
      "CRYPTO",
      "chat.v2 HMAC-SHA256 ACK verified for ${parsedUrl.host}",
    )

    var boundProxyPort: Int? = null
    if (config.enableLocalProxy) {
      try {
        val proxy = LocalProxyServer(
          port = config.localProxyPort,
          okHttpClient = client,
          workerWsUrl = parsedUrl.normalizedWsUrl,
          uuidToken = config.uuid,
        )
        boundProxyPort = proxy.start()
        localProxyServer = proxy
        TunnelStateHolder.log(
          TunnelLogEntry.LogLevel.INFO,
          "PROXY",
          "SOCKS5/HTTP proxy listening on 127.0.0.1:$boundProxyPort",
        )
      } catch (e: Exception) {
        TunnelStateHolder.log(
          TunnelLogEntry.LogLevel.WARN,
          "PROXY",
          "Local proxy port ${config.localProxyPort} unavailable: ${e.message}",
        )
      }
    }

    val vpnFd = try {
      establishVpnInterface(config, boundProxyPort)
    } catch (e: Exception) {
      failAndStop("Failed to establish VPN interface: ${e.message}")
      return
    }

    if (vpnFd == null) {
      failAndStop("VPN permission revoked or TUN interface creation returned null.")
      return
    }
    tunInterface = vpnFd

    val forwarder = TunPacketForwarder(
      tunFd = vpnFd.fileDescriptor,
      okHttpClient = client,
      workerWsUrl = parsedUrl.normalizedWsUrl,
      uuidToken = config.uuid,
      socketProtector = { udpSocket -> protect(udpSocket) },
    )
    forwarder.start()
    tunForwarder = forwarder

    TunnelStateHolder.setConnected(boundProxyPort)

    val modeSummary = when (config.routingMode) {
      RoutingMode.PROXIED_APPS -> "${config.selectedPackages.size} selected apps"
      RoutingMode.BYPASS_SELECTED -> "All except ${config.selectedPackages.size} bypassed"
    }
    val notificationManager = getSystemService(NotificationManager::class.java)
    notificationManager.notify(
      NOTIFICATION_ID,
      buildNotification(
        title = getString(R.string.vpn_notification_title_connected),
        text = "${parsedUrl.host} • $modeSummary",
      ),
    )
  }

  private fun verifyWorkerHandshake(
    client: OkHttpClient,
    workerWsUrl: String,
    uuidToken: String,
  ): String? {
    val latch = CountDownLatch(1)
    val failureMsg = AtomicReference<String?>(null)

    val probe = ChatV2WebSocketClient(
      okHttpClient = client,
      workerWsUrl = workerWsUrl,
      uuidToken = uuidToken,
      targetHost = "cp.cloudflare.com",
      targetPort = 80,
      command = ChatV2Packet.CMD_TCP,
      listener = object : ChatV2WebSocketClient.TunnelListener {
        override fun onHandshakeVerified() {
          latch.countDown()
        }

        override fun onDataReceived(plainData: ByteArray) = Unit

        override fun onClosed(reason: String) {
          latch.countDown()
        }

        override fun onFailure(error: Throwable) {
          failureMsg.compareAndSet(null, error.message ?: "Connection failed")
          latch.countDown()
        }
      },
    )

    probe.connect()
    val completed = latch.await(12, TimeUnit.SECONDS)
    probe.close("Handshake probe complete")

    if (!completed) return "Timed out waiting for chat.v2 ACK"
    return failureMsg.get()
  }

  private fun establishVpnInterface(
    config: ProxyConfig,
    localHttpProxyPort: Int?,
  ): ParcelFileDescriptor? {
    val builder = Builder()
      .setSession("EdgeTunnel")
      .setMtu(1500)
      .addAddress("10.233.233.1", 30)
      .addRoute("0.0.0.0", 0)
      .addDnsServer("1.1.1.1")
      .addDnsServer("8.8.8.8")

    when (config.routingMode) {
      RoutingMode.PROXIED_APPS -> {
        var addedCount = 0
        for (pkg in config.selectedPackages) {
          if (pkg == packageName) continue
          runCatching {
            builder.addAllowedApplication(pkg)
            addedCount++
          }.onFailure {
            TunnelStateHolder.log(
              TunnelLogEntry.LogLevel.WARN,
              "PER-APP",
              "Skipped unavailable package $pkg",
            )
          }
        }
        TunnelStateHolder.log(
          TunnelLogEntry.LogLevel.INFO,
          "PER-APP",
          "Allowed $addedCount apps through Worker",
        )
      }

      RoutingMode.BYPASS_SELECTED -> {
        runCatching { builder.addDisallowedApplication(packageName) }
        var bypassedCount = 0
        for (pkg in config.selectedPackages) {
          if (pkg == packageName) continue
          runCatching {
            builder.addDisallowedApplication(pkg)
            bypassedCount++
          }
        }
        TunnelStateHolder.log(
          TunnelLogEntry.LogLevel.INFO,
          "PER-APP",
          "Bypassing $bypassedCount apps; all other traffic routed via Worker",
        )
      }
    }

    if (localHttpProxyPort != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", localHttpProxyPort))
    }

    return builder.establish()
  }

  private fun failAndStop(message: String) {
    tearDownTransport()
    TunnelStateHolder.setError(message)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun stopTunnel(reason: String) {
    startupJob?.cancel()
    tearDownTransport()
    TunnelStateHolder.setDisconnected(reason)
    stopForeground(STOP_FOREGROUND_REMOVE)
    stopSelf()
  }

  private fun tearDownTransport() {
    tunForwarder?.stop()
    tunForwarder = null

    localProxyServer?.stop()
    localProxyServer = null

    runCatching { tunInterface?.close() }
    tunInterface = null

    okHttpClient?.let { client ->
      client.dispatcher.executorService.shutdown()
      client.connectionPool.evictAll()
    }
    okHttpClient = null
  }

  override fun onRevoke() {
    stopTunnel("VPN permission revoked by system")
    super.onRevoke()
  }

  override fun onDestroy() {
    startupJob?.cancel()
    tearDownTransport()
    serviceScope.cancel()
    super.onDestroy()
  }

  private fun startForegroundCompat(notification: Notification) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val manager = getSystemService(NotificationManager::class.java)
      val channel = NotificationChannel(
        CHANNEL_ID,
        getString(R.string.vpn_notification_channel_name),
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = getString(R.string.vpn_notification_channel_desc)
        setShowBadge(false)
      }
      manager.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(title: String, text: String): Notification {
    val openIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val disconnectIntent = PendingIntent.getService(
      this,
      1,
      Intent(this, EdgeTunnelVpnService::class.java).apply { action = ACTION_DISCONNECT },
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle(title)
      .setContentText(text)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openIntent)
      .addAction(
        0,
        getString(R.string.vpn_notification_action_disconnect),
        disconnectIntent,
      )
      .build()
  }

  companion object {
    const val ACTION_CONNECT = "com.example.vpn.ACTION_CONNECT"
    const val ACTION_DISCONNECT = "com.example.vpn.ACTION_DISCONNECT"
    private const val CHANNEL_ID = "edgetunnel_vpn_channel"
    private const val NOTIFICATION_ID = 4041

    fun startVpn(context: Context) {
      val intent = Intent(context, EdgeTunnelVpnService::class.java).apply {
        action = ACTION_CONNECT
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stopVpn(context: Context) {
      val intent = Intent(context, EdgeTunnelVpnService::class.java).apply {
        action = ACTION_DISCONNECT
      }
      context.startService(intent)
    }
  }
}
