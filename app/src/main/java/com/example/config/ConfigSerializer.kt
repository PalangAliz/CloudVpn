package com.example.config

import android.net.Uri
import com.example.model.ProxyConfig
import com.example.model.RoutingMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports and imports [ProxyConfig] using either structured JSON or `edgetunnel://` URIs.
 */
object ConfigSerializer {

  const val URI_SCHEME = "edgetunnel"
  const val URI_HOST = "config"

  fun toJson(config: ProxyConfig, pretty: Boolean = true): String {
    val root = JSONObject().apply {
      put("version", 1)
      put("workerUrl", config.workerUrl)
      put("uuid", config.uuid)
      put("cleanIp", config.cleanIp)
      put("routingMode", config.routingMode.wireName)
      put("localProxyPort", config.localProxyPort)
      put("enableLocalProxy", config.enableLocalProxy)
      put("selectedPackages", JSONArray(config.selectedPackages.sorted()))
    }
    return if (pretty) root.toString(2) else root.toString()
  }

  fun toShareUri(config: ProxyConfig): String {
    val builder = Uri.Builder()
      .scheme(URI_SCHEME)
      .authority(URI_HOST)
      .appendQueryParameter("url", config.workerUrl.trim())
      .appendQueryParameter("uuid", config.uuid.trim())
      .appendQueryParameter("mode", config.routingMode.wireName)
      .appendQueryParameter("port", config.localProxyPort.toString())
      .appendQueryParameter("localProxy", config.enableLocalProxy.toString())

    if (config.cleanIp.isNotBlank()) {
      builder.appendQueryParameter("cleanIp", config.cleanIp.trim())
    }
    if (config.selectedPackages.isNotEmpty()) {
      builder.appendQueryParameter("apps", config.selectedPackages.sorted().joinToString(","))
    }
    return builder.build().toString()
  }

  fun parse(rawInput: String): Result<ProxyConfig> {
    val trimmed = rawInput.trim()
    if (trimmed.isEmpty()) {
      return Result.failure(IllegalArgumentException("Configuration input is empty"))
    }
    return when {
      trimmed.startsWith("{") -> parseJson(trimmed)
      trimmed.startsWith("$URI_SCHEME://", ignoreCase = true) -> parseUri(trimmed)
      else -> Result.failure(
        IllegalArgumentException("Unsupported format. Paste JSON or an edgetunnel:// URI.")
      )
    }
  }

  private fun parseJson(jsonText: String): Result<ProxyConfig> {
    return try {
      val obj = JSONObject(jsonText)
      val workerUrl = obj.optString("workerUrl", "").trim()
      val uuid = obj.optString("uuid", ProxyConfig.DEFAULT_UUID).trim()
      val cleanIp = obj.optString("cleanIp", "").trim()
      val mode = RoutingMode.fromWireName(obj.optString("routingMode", null))
      val port = obj.optInt("localProxyPort", ProxyConfig.DEFAULT_LOCAL_PROXY_PORT)
        .takeIf { ConfigValidator.isValidPort(it) } ?: ProxyConfig.DEFAULT_LOCAL_PROXY_PORT
      val enableLocalProxy = obj.optBoolean("enableLocalProxy", true)
      val pkgArray = obj.optJSONArray("selectedPackages")
      val packages = buildSet {
        if (pkgArray != null) {
          for (i in 0 until pkgArray.length()) {
            val pkg = pkgArray.optString(i).trim()
            if (pkg.isNotEmpty()) add(pkg)
          }
        }
      }
      validateParsedConfig(
        ProxyConfig(
          workerUrl = workerUrl,
          uuid = uuid,
          cleanIp = cleanIp,
          routingMode = mode,
          selectedPackages = packages,
          localProxyPort = port,
          enableLocalProxy = enableLocalProxy,
        )
      )
    } catch (e: Exception) {
      Result.failure(IllegalArgumentException("Invalid JSON config: ${e.message}"))
    }
  }

  private fun parseUri(uriText: String): Result<ProxyConfig> {
    return try {
      val uri = Uri.parse(uriText)
      if (!URI_SCHEME.equals(uri.scheme, ignoreCase = true)) {
        return Result.failure(IllegalArgumentException("Invalid URI scheme"))
      }
      val workerUrl = uri.getQueryParameter("url")?.trim().orEmpty()
      val uuid = uri.getQueryParameter("uuid")?.trim()
        ?.takeIf { it.isNotEmpty() } ?: ProxyConfig.DEFAULT_UUID
      val cleanIp = uri.getQueryParameter("cleanIp")?.trim().orEmpty()
      val mode = RoutingMode.fromWireName(uri.getQueryParameter("mode"))
      val port = uri.getQueryParameter("port")?.toIntOrNull()
        ?.takeIf { ConfigValidator.isValidPort(it) } ?: ProxyConfig.DEFAULT_LOCAL_PROXY_PORT
      val enableLocalProxy = uri.getQueryParameter("localProxy")?.toBooleanStrictOrNull() ?: true
      val packages = uri.getQueryParameter("apps")
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        ?: emptySet()

      validateParsedConfig(
        ProxyConfig(
          workerUrl = workerUrl,
          uuid = uuid,
          cleanIp = cleanIp,
          routingMode = mode,
          selectedPackages = packages,
          localProxyPort = port,
          enableLocalProxy = enableLocalProxy,
        )
      )
    } catch (e: Exception) {
      Result.failure(IllegalArgumentException("Invalid edgetunnel:// URI: ${e.message}"))
    }
  }

  private fun validateParsedConfig(config: ProxyConfig): Result<ProxyConfig> {
    if (config.workerUrl.isNotEmpty() && ConfigValidator.normalizeWorkerUrl(config.workerUrl) == null) {
      return Result.failure(IllegalArgumentException("Invalid Worker URL in imported config"))
    }
    if (!ConfigValidator.isValidUuid(config.uuid)) {
      return Result.failure(IllegalArgumentException("Invalid UUID format in imported config"))
    }
    val cleanIpResult = ConfigValidator.parseCleanIp(config.cleanIp)
    if (cleanIpResult.isFailure) {
      return Result.failure(
        cleanIpResult.exceptionOrNull() ?: IllegalArgumentException("Invalid Clean IP")
      )
    }
    return Result.success(config)
  }
}
