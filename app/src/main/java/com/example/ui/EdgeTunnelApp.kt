package com.example.ui

import android.Manifest
import android.app.Activity
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.R
import com.example.ui.screens.ConfigScreen
import com.example.ui.screens.PerAppScreen
import com.example.ui.screens.TunnelScreen
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.CyanPrimaryContainer
import com.example.ui.theme.ObsidianBg
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.SlateTextSecondary

object EdgeTunnelRoutes {
  const val TUNNEL = "tunnel"
  const val PER_APP = "per_app"
  const val CONFIG = "config"
}

private data class NavDestinationItem(
  val route: String,
  val labelRes: Int,
  val selectedIcon: ImageVector,
  val unselectedIcon: ImageVector,
  val testTag: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EdgeTunnelApp(
  viewModel: EdgeTunnelViewModel,
) {
  val context = LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val navController = rememberNavController()
  val snackbarHostState = remember { SnackbarHostState() }

  val config by viewModel.config.collectAsStateWithLifecycle()
  val runtimeState by viewModel.runtimeState.collectAsStateWithLifecycle()
  val appsUiState by viewModel.appsUiState.collectAsStateWithLifecycle()
  val filteredApps by viewModel.filteredApps.collectAsStateWithLifecycle()
  val isIgnoringBatteryOpt by viewModel.isIgnoringBatteryOpt.collectAsStateWithLifecycle()
  val hasNotificationPermission by viewModel.hasNotificationPermission.collectAsStateWithLifecycle()

  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_RESUME) {
        viewModel.refreshSystemStatus()
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  LaunchedEffect(Unit) {
    viewModel.toastMessage.collect { msg ->
      snackbarHostState.showSnackbar(msg)
    }
  }

  val vpnPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult(),
  ) { result ->
    if (result.resultCode == Activity.RESULT_OK) {
      viewModel.startTunnel()
    } else {
      viewModel.notifyMessage("VPN permission is required to start EdgeTunnel")
    }
  }

  val notificationPermissionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission(),
  ) { granted ->
    viewModel.refreshSystemStatus()
    if (!granted) {
      viewModel.notifyMessage("Notification permission denied; VPN runs without status banner")
    }
  }

  val requestConnectOrDisconnect: () -> Unit = {
    if (viewModel.isTunnelRunningOrConnecting()) {
      viewModel.stopTunnel()
    } else {
      val validationError = viewModel.validateConfigForConnection()
      if (validationError != null) {
        viewModel.startTunnel() // logs and shows snackbar
      } else {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
          notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
          vpnPermissionLauncher.launch(prepareIntent)
        } else {
          viewModel.startTunnel()
        }
      }
    }
  }

  val destinations = remember {
    listOf(
      NavDestinationItem(
        route = EdgeTunnelRoutes.TUNNEL,
        labelRes = R.string.nav_tunnel,
        selectedIcon = Icons.Filled.Shield,
        unselectedIcon = Icons.Outlined.Shield,
        testTag = "nav_tunnel",
      ),
      NavDestinationItem(
        route = EdgeTunnelRoutes.PER_APP,
        labelRes = R.string.nav_apps,
        selectedIcon = Icons.Filled.Apps,
        unselectedIcon = Icons.Outlined.Apps,
        testTag = "nav_apps",
      ),
      NavDestinationItem(
        route = EdgeTunnelRoutes.CONFIG,
        labelRes = R.string.nav_config,
        selectedIcon = Icons.Filled.Settings,
        unselectedIcon = Icons.Outlined.Settings,
        testTag = "nav_config",
      ),
    )
  }

  val navBackStackEntry by navController.currentBackStackEntryAsState()
  val currentRoute = navBackStackEntry?.destination?.route ?: EdgeTunnelRoutes.TUNNEL

  val navigateToTab: (String) -> Unit = { route ->
    navController.navigate(route) {
      popUpTo(navController.graph.findStartDestination().id) {
        saveState = true
      }
      launchSingleTop = true
      restoreState = true
    }
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
    val useNavigationRail = maxWidth >= 600.dp

    Scaffold(
      containerColor = ObsidianBg,
      contentWindowInsets = WindowInsets.safeDrawing,
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
      topBar = {
        TopAppBar(
          title = {
            Text(
              text = stringResource(R.string.app_name),
              style = MaterialTheme.typography.headlineSmall,
            )
          },
          colors = TopAppBarDefaults.topAppBarColors(
            containerColor = ObsidianBg,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
          ),
        )
      },
      bottomBar = {
        if (!useNavigationRail) {
          NavigationBar(
            containerColor = ObsidianSurface,
          ) {
            destinations.forEach { item ->
              val selected = currentRoute == item.route
              NavigationBarItem(
                selected = selected,
                onClick = { navigateToTab(item.route) },
                icon = {
                  if (item.route == EdgeTunnelRoutes.PER_APP && config.selectedPackages.isNotEmpty()) {
                    BadgedBox(
                      badge = {
                        Badge(containerColor = CyanPrimary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                          Text(config.selectedPackages.size.toString())
                        }
                      },
                    ) {
                      Icon(
                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                        contentDescription = stringResource(item.labelRes),
                      )
                    }
                  } else {
                    Icon(
                      imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                      contentDescription = stringResource(item.labelRes),
                    )
                  }
                },
                label = { Text(stringResource(item.labelRes)) },
                colors = NavigationBarItemDefaults.colors(
                  selectedIconColor = CyanPrimary,
                  selectedTextColor = CyanPrimary,
                  indicatorColor = CyanPrimaryContainer,
                  unselectedIconColor = SlateTextSecondary,
                  unselectedTextColor = SlateTextSecondary,
                ),
                modifier = Modifier.testTag(item.testTag),
              )
            }
          }
        }
      },
    ) { innerPadding ->
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(innerPadding),
      ) {
        if (useNavigationRail) {
          NavigationRail(
            containerColor = ObsidianSurface,
          ) {
            destinations.forEach { item ->
              val selected = currentRoute == item.route
              NavigationRailItem(
                selected = selected,
                onClick = { navigateToTab(item.route) },
                icon = {
                  Icon(
                    imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                    contentDescription = stringResource(item.labelRes),
                  )
                },
                label = { Text(stringResource(item.labelRes)) },
                colors = NavigationRailItemDefaults.colors(
                  selectedIconColor = CyanPrimary,
                  selectedTextColor = CyanPrimary,
                  indicatorColor = CyanPrimaryContainer,
                  unselectedIconColor = SlateTextSecondary,
                  unselectedTextColor = SlateTextSecondary,
                ),
                modifier = Modifier.testTag(item.testTag),
              )
            }
          }
        }

        NavHost(
          navController = navController,
          startDestination = EdgeTunnelRoutes.TUNNEL,
          modifier = Modifier.weight(1f),
        ) {
          composable(EdgeTunnelRoutes.TUNNEL) {
            TunnelScreen(
              config = config,
              runtimeState = runtimeState,
              onWorkerUrlChange = viewModel::updateWorkerUrl,
              onUuidChange = viewModel::updateUuid,
              onResetUuid = viewModel::resetDefaultUuid,
              onCleanIpChange = viewModel::updateCleanIp,
              onToggleConnect = requestConnectOrDisconnect,
              onNavigateToPerApp = { navigateToTab(EdgeTunnelRoutes.PER_APP) },
              onClearLogs = viewModel::clearLogs,
              onCopyLogsFeedback = viewModel::notifyMessage,
            )
          }
          composable(EdgeTunnelRoutes.PER_APP) {
            PerAppScreen(
              config = config,
              appsUiState = appsUiState,
              filteredApps = filteredApps,
              onRoutingModeChange = viewModel::updateRoutingMode,
              onSearchQueryChange = viewModel::updateSearchQuery,
              onFilterChange = viewModel::updateAppFilter,
              onToggleApp = viewModel::toggleAppSelection,
              onSelectVisible = viewModel::selectAllVisibleApps,
              onClearAll = viewModel::clearSelectedApps,
            )
          }
          composable(EdgeTunnelRoutes.CONFIG) {
            ConfigScreen(
              config = config,
              isIgnoringBatteryOpt = isIgnoringBatteryOpt,
              hasNotificationPermission = hasNotificationPermission,
              onEnableLocalProxyChange = viewModel::updateEnableLocalProxy,
              onLocalProxyPortChange = viewModel::updateLocalProxyPort,
              onExportJson = viewModel::exportJson,
              onExportShareUri = viewModel::exportShareUri,
              onImportRaw = viewModel::importConfig,
              onRequestNotificationPermission = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                  notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
              },
              onNotify = viewModel::notifyMessage,
            )
          }
        }
      }
    }
  }
}
