package com.example.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.model.ProxyConfig
import com.example.model.RoutingMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.edgeTunnelDataStore: DataStore<Preferences> by preferencesDataStore(
  name = "edgetunnel_settings"
)

class ConfigRepository(private val context: Context) {

  private object Keys {
    val WORKER_URL = stringPreferencesKey("worker_url")
    val UUID = stringPreferencesKey("uuid")
    val CLEAN_IP = stringPreferencesKey("clean_ip")
    val ROUTING_MODE = stringPreferencesKey("routing_mode")
    val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
    val LOCAL_PROXY_PORT = intPreferencesKey("local_proxy_port")
    val ENABLE_LOCAL_PROXY = booleanPreferencesKey("enable_local_proxy")
  }

  val configFlow: Flow<ProxyConfig> = context.edgeTunnelDataStore.data.map { prefs ->
    ProxyConfig(
      workerUrl = prefs[Keys.WORKER_URL] ?: ProxyConfig.DEFAULT_WORKER_URL,
      uuid = prefs[Keys.UUID] ?: ProxyConfig.DEFAULT_UUID,
      cleanIp = prefs[Keys.CLEAN_IP] ?: "",
      routingMode = RoutingMode.fromWireName(prefs[Keys.ROUTING_MODE]),
      selectedPackages = prefs[Keys.SELECTED_PACKAGES] ?: emptySet(),
      localProxyPort = prefs[Keys.LOCAL_PROXY_PORT] ?: ProxyConfig.DEFAULT_LOCAL_PROXY_PORT,
      enableLocalProxy = prefs[Keys.ENABLE_LOCAL_PROXY] ?: true,
    )
  }

  suspend fun getCurrentConfig(): ProxyConfig = configFlow.first()

  suspend fun saveConfig(config: ProxyConfig) {
    context.edgeTunnelDataStore.edit { prefs ->
      prefs[Keys.WORKER_URL] = config.workerUrl.trim()
      prefs[Keys.UUID] = config.uuid.trim()
      prefs[Keys.CLEAN_IP] = config.cleanIp.trim()
      prefs[Keys.ROUTING_MODE] = config.routingMode.wireName
      prefs[Keys.SELECTED_PACKAGES] = config.selectedPackages
      prefs[Keys.LOCAL_PROXY_PORT] = config.localProxyPort
      prefs[Keys.ENABLE_LOCAL_PROXY] = config.enableLocalProxy
    }
  }

  suspend fun updateWorkerUrl(url: String) {
    context.edgeTunnelDataStore.edit { it[Keys.WORKER_URL] = url.trim() }
  }

  suspend fun updateUuid(uuid: String) {
    context.edgeTunnelDataStore.edit { it[Keys.UUID] = uuid.trim() }
  }

  suspend fun updateCleanIp(cleanIp: String) {
    context.edgeTunnelDataStore.edit { it[Keys.CLEAN_IP] = cleanIp.trim() }
  }

  suspend fun updateRoutingMode(mode: RoutingMode) {
    context.edgeTunnelDataStore.edit { it[Keys.ROUTING_MODE] = mode.wireName }
  }

  suspend fun updateSelectedPackages(packages: Set<String>) {
    context.edgeTunnelDataStore.edit { it[Keys.SELECTED_PACKAGES] = packages }
  }

  suspend fun togglePackage(packageName: String) {
    context.edgeTunnelDataStore.edit { prefs ->
      val current = prefs[Keys.SELECTED_PACKAGES] ?: emptySet()
      prefs[Keys.SELECTED_PACKAGES] = if (packageName in current) {
        current - packageName
      } else {
        current + packageName
      }
    }
  }

  suspend fun updateLocalProxyPort(port: Int) {
    context.edgeTunnelDataStore.edit { it[Keys.LOCAL_PROXY_PORT] = port }
  }

  suspend fun updateEnableLocalProxy(enabled: Boolean) {
    context.edgeTunnelDataStore.edit { it[Keys.ENABLE_LOCAL_PROXY] = enabled }
  }
}
