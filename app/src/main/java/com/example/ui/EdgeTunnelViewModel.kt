package com.example.ui

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.apps.InstalledAppInfo
import com.example.apps.InstalledAppsRepository
import com.example.config.ConfigRepository
import com.example.config.ConfigSerializer
import com.example.config.ConfigValidator
import com.example.model.ConnectionStatus
import com.example.model.ProxyConfig
import com.example.model.RoutingMode
import com.example.model.TunnelLogEntry
import com.example.model.TunnelRuntimeState
import com.example.vpn.BatteryOptimizationHelper
import com.example.vpn.EdgeTunnelVpnService
import com.example.vpn.TunnelStateHolder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppListFilter(val label: String) {
  ALL("All"),
  USER_ONLY("User"),
  SYSTEM_ONLY("System"),
  SELECTED_ONLY("Selected"),
}

data class InstalledAppsUiState(
  val isLoading: Boolean = true,
  val allApps: List<InstalledAppInfo> = emptyList(),
  val searchQuery: String = "",
  val filter: AppListFilter = AppListFilter.USER_ONLY,
)

class EdgeTunnelViewModel(application: Application) : AndroidViewModel(application) {

  private val configRepository = ConfigRepository(application)
  private val appsRepository = InstalledAppsRepository(application)

  val config: StateFlow<ProxyConfig> = configRepository.configFlow.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = ProxyConfig(),
  )

  val runtimeState: StateFlow<TunnelRuntimeState> = TunnelStateHolder.state

  private val _appsUiState = MutableStateFlow(InstalledAppsUiState())
  val appsUiState: StateFlow<InstalledAppsUiState> = _appsUiState.asStateFlow()

  val filteredApps: StateFlow<List<InstalledAppInfo>> = combine(
    _appsUiState,
    config,
  ) { appsState, cfg ->
    val query = appsState.searchQuery.trim().lowercase()
    appsState.allApps.filter { app ->
      val matchesFilter = when (appsState.filter) {
        AppListFilter.ALL -> true
        AppListFilter.USER_ONLY -> !app.isSystemApp
        AppListFilter.SYSTEM_ONLY -> app.isSystemApp
        AppListFilter.SELECTED_ONLY -> app.packageName in cfg.selectedPackages
      }
      val matchesQuery = query.isEmpty() ||
        app.appName.lowercase().contains(query) ||
        app.packageName.lowercase().contains(query)
      matchesFilter && matchesQuery
    }
  }.stateIn(
    scope = viewModelScope,
    started = SharingStarted.WhileSubscribed(5_000),
    initialValue = emptyList(),
  )

  private val _isIgnoringBatteryOpt = MutableStateFlow(
    BatteryOptimizationHelper.isIgnoringBatteryOptimizations(application)
  )
  val isIgnoringBatteryOpt: StateFlow<Boolean> = _isIgnoringBatteryOpt.asStateFlow()

  private val _hasNotificationPermission = MutableStateFlow(checkNotificationPermission(application))
  val hasNotificationPermission: StateFlow<Boolean> = _hasNotificationPermission.asStateFlow()

  private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
  val toastMessage = _toastMessage.asSharedFlow()

  init {
    refreshInstalledApps()
  }

  fun refreshInstalledApps() {
    viewModelScope.launch {
      _appsUiState.update { it.copy(isLoading = true) }
      val apps = appsRepository.loadInstalledApps()
      _appsUiState.update { it.copy(isLoading = false, allApps = apps) }
    }
  }

  fun refreshSystemStatus() {
    val ctx = getApplication<Application>()
    _isIgnoringBatteryOpt.value = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(ctx)
    _hasNotificationPermission.value = checkNotificationPermission(ctx)
  }

  fun updateWorkerUrl(url: String) {
    viewModelScope.launch { configRepository.updateWorkerUrl(url) }
  }

  fun updateUuid(uuid: String) {
    viewModelScope.launch { configRepository.updateUuid(uuid) }
  }

  fun resetDefaultUuid() {
    viewModelScope.launch {
      configRepository.updateUuid(ProxyConfig.DEFAULT_UUID)
      _toastMessage.tryEmit("UUID reset to default Worker token")
    }
  }

  fun updateCleanIp(cleanIp: String) {
    viewModelScope.launch { configRepository.updateCleanIp(cleanIp) }
  }

  fun updateRoutingMode(mode: RoutingMode) {
    viewModelScope.launch { configRepository.updateRoutingMode(mode) }
  }

  fun toggleAppSelection(packageName: String) {
    viewModelScope.launch { configRepository.togglePackage(packageName) }
  }

  fun selectAllVisibleApps() {
    viewModelScope.launch {
      val visiblePackages = filteredApps.value.map { it.packageName }.toSet()
      val merged = config.value.selectedPackages + visiblePackages
      configRepository.updateSelectedPackages(merged)
      _toastMessage.tryEmit("Selected ${visiblePackages.size} visible apps")
    }
  }

  fun clearSelectedApps() {
    viewModelScope.launch {
      configRepository.updateSelectedPackages(emptySet())
      _toastMessage.tryEmit("Cleared all selected apps")
    }
  }

  fun updateSearchQuery(query: String) {
    _appsUiState.update { it.copy(searchQuery = query) }
  }

  fun updateAppFilter(filter: AppListFilter) {
    _appsUiState.update { it.copy(filter = filter) }
  }

  fun updateLocalProxyPort(port: Int) {
    if (ConfigValidator.isValidPort(port)) {
      viewModelScope.launch { configRepository.updateLocalProxyPort(port) }
    }
  }

  fun updateEnableLocalProxy(enabled: Boolean) {
    viewModelScope.launch { configRepository.updateEnableLocalProxy(enabled) }
  }

  fun validateConfigForConnection(): String? {
    val cfg = config.value
    if (ConfigValidator.normalizeWorkerUrl(cfg.workerUrl) == null) {
      return "Enter a valid Worker URL (e.g. https://your-worker.workers.dev)"
    }
    if (!ConfigValidator.isValidUuid(cfg.uuid)) {
      return "UUID must be a valid 8-4-4-4-12 token"
    }
    val cleanIpRes = ConfigValidator.parseCleanIp(cfg.cleanIp)
    if (cleanIpRes.isFailure) {
      return cleanIpRes.exceptionOrNull()?.message ?: "Invalid Clean IP"
    }
    if (cfg.routingMode == RoutingMode.PROXIED_APPS && cfg.selectedPackages.isEmpty()) {
      return "Select at least one app in Per-App Proxy or switch to Bypass mode"
    }
    return null
  }

  fun startTunnel() {
    val validationError = validateConfigForConnection()
    if (validationError != null) {
      TunnelStateHolder.log(TunnelLogEntry.LogLevel.ERROR, "CONFIG", validationError)
      _toastMessage.tryEmit(validationError)
      return
    }
    EdgeTunnelVpnService.startVpn(getApplication())
  }

  fun stopTunnel() {
    EdgeTunnelVpnService.stopVpn(getApplication())
  }

  fun isTunnelRunningOrConnecting(): Boolean {
    return when (runtimeState.value.status) {
      ConnectionStatus.CONNECTED, ConnectionStatus.CONNECTING -> true
      ConnectionStatus.DISCONNECTED, ConnectionStatus.ERROR -> false
    }
  }

  fun clearLogs() {
    TunnelStateHolder.clearLogs()
  }

  fun exportJson(): String = ConfigSerializer.toJson(config.value, pretty = true)

  fun exportShareUri(): String = ConfigSerializer.toShareUri(config.value)

  fun importConfig(rawInput: String): Result<ProxyConfig> {
    val parsed = ConfigSerializer.parse(rawInput)
    parsed.onSuccess { imported ->
      viewModelScope.launch {
        configRepository.saveConfig(imported)
        TunnelStateHolder.log(
          TunnelLogEntry.LogLevel.SUCCESS,
          "CONFIG",
          "Imported configuration (${imported.selectedPackages.size} apps, ${imported.routingMode.wireName})",
        )
        _toastMessage.tryEmit("Configuration imported successfully")
      }
    }
    return parsed
  }

  fun notifyMessage(msg: String) {
    _toastMessage.tryEmit(msg)
  }

  private fun checkNotificationPermission(app: Application): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      ContextCompat.checkSelfPermission(
        app,
        android.Manifest.permission.POST_NOTIFICATIONS,
      ) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }
}
