package com.example.config

import com.example.model.CleanIpEndpoint
import java.net.URI

/**
 * Validates and normalizes user configuration inputs:
 * - Worker URL -> normalized `wss://` (or `ws://` for plaintext dev endpoints)
 * - UUID token -> strict 8-4-4-4-12 format
 * - Clean IP -> optional `ip`, `ip:port`, `[ipv6]`, `[ipv6]:port`, or `host:port`
 */
object ConfigValidator {

  private val UUID_REGEX =
    Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

  private val IPV4_REGEX =
    Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

  data class ParsedWorkerUrl(
    val normalizedWsUrl: String,
    val host: String,
    val port: Int,
    val isTls: Boolean,
  )

  fun normalizeWorkerUrl(rawInput: String): ParsedWorkerUrl? {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return null

    val withScheme = when {
      trimmed.startsWith("https://", ignoreCase = true) -> "wss://" + trimmed.substring(8)
      trimmed.startsWith("http://", ignoreCase = true) -> "ws://" + trimmed.substring(7)
      trimmed.startsWith("wss://", ignoreCase = true) -> "wss://" + trimmed.substring(6)
      trimmed.startsWith("ws://", ignoreCase = true) -> "ws://" + trimmed.substring(5)
      trimmed.contains("://") -> return null
      else -> "wss://$trimmed"
    }

    return try {
      val uri = URI(withScheme)
      val scheme = uri.scheme?.lowercase() ?: return null
      if (scheme != "wss" && scheme != "ws") return null
      val host = uri.host?.takeIf { it.isNotBlank() } ?: return null
      val isTls = scheme == "wss"
      val port = when {
        uri.port in 1..65535 -> uri.port
        uri.port == -1 -> if (isTls) 443 else 80
        else -> return null
      }
      val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
      val query = uri.rawQuery?.let { "?$it" } ?: ""
      val portSuffix = if ((isTls && port == 443) || (!isTls && port == 80)) "" else ":$port"
      val normalized = "$scheme://$host$portSuffix$path$query"
      ParsedWorkerUrl(
        normalizedWsUrl = normalized,
        host = host,
        port = port,
        isTls = isTls,
      )
    } catch (_: Exception) {
      null
    }
  }

  fun isValidUuid(uuid: String): Boolean {
    return UUID_REGEX.matches(uuid.trim())
  }

  fun parseCleanIp(rawInput: String): Result<CleanIpEndpoint?> {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) return Result.success(null)

    // Bracketed IPv6: [2606:4700::1] or [2606:4700::1]:443
    if (trimmed.startsWith("[")) {
      val closeBracket = trimmed.indexOf(']')
      if (closeBracket <= 1) {
        return Result.failure(IllegalArgumentException("Invalid IPv6 bracket format"))
      }
      val host = trimmed.substring(1, closeBracket).trim()
      if (host.isEmpty() || !host.contains(":")) {
        return Result.failure(IllegalArgumentException("Invalid IPv6 address"))
      }
      val remainder = trimmed.substring(closeBracket + 1)
      if (remainder.isEmpty()) {
        return Result.success(CleanIpEndpoint(host = host, port = null))
      }
      if (!remainder.startsWith(":")) {
        return Result.failure(IllegalArgumentException("Expected :port after IPv6 address"))
      }
      val port = remainder.substring(1).toIntOrNull()
      if (port == null || port !in 1..65535) {
        return Result.failure(IllegalArgumentException("Port must be between 1 and 65535"))
      }
      return Result.success(CleanIpEndpoint(host = host, port = port))
    }

    // Raw IPv6 without brackets (more than 1 colon)
    val firstColon = trimmed.indexOf(':')
    val lastColon = trimmed.lastIndexOf(':')
    if (firstColon != -1 && firstColon != lastColon) {
      return Result.success(CleanIpEndpoint(host = trimmed, port = null))
    }

    // host or host:port or ipv4:port
    if (firstColon != -1) {
      val host = trimmed.substring(0, firstColon).trim()
      val portStr = trimmed.substring(firstColon + 1).trim()
      if (host.isEmpty()) {
        return Result.failure(IllegalArgumentException("Clean IP host cannot be empty"))
      }
      val port = portStr.toIntOrNull()
      if (port == null || port !in 1..65535) {
        return Result.failure(IllegalArgumentException("Port must be between 1 and 65535"))
      }
      return Result.success(CleanIpEndpoint(host = host, port = port))
    }

    if (trimmed.contains(" ") || trimmed.contains("/")) {
      return Result.failure(IllegalArgumentException("Clean IP must be a valid IP or host:port"))
    }
    return Result.success(CleanIpEndpoint(host = trimmed, port = null))
  }

  fun isValidPort(port: Int): Boolean = port in 1024..65535

  fun isIpv4Literal(host: String): Boolean = IPV4_REGEX.matches(host)
}
