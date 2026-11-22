package com.example.ui.screens

import android.graphics.drawable.Drawable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.example.R
import com.example.apps.InstalledAppInfo
import com.example.model.ProxyConfig
import com.example.model.RoutingMode
import com.example.ui.AppListFilter
import com.example.ui.InstalledAppsUiState
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.CyanPrimaryContainer
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSecondary
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceElevated
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.SlateTextMuted
import com.example.ui.theme.SlateTextSecondary

@Composable
fun PerAppScreen(
  config: ProxyConfig,
  appsUiState: InstalledAppsUiState,
  filteredApps: List<InstalledAppInfo>,
  onRoutingModeChange: (RoutingMode) -> Unit,
  onSearchQueryChange: (String) -> Unit,
  onFilterChange: (AppListFilter) -> Unit,
  onToggleApp: (String) -> Unit,
  onSelectVisible: () -> Unit,
  onClearAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 720.dp)
        .fillMaxSize()
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      RoutingModeSelectorCard(
        selectedMode = config.routingMode,
        selectedCount = config.selectedPackages.size,
        onSelectMode = onRoutingModeChange,
      )

      // Search Input
      OutlinedTextField(
        value = appsUiState.searchQuery,
        onValueChange = onSearchQueryChange,
        placeholder = { Text(stringResource(R.string.hint_search_apps)) },
        leadingIcon = {
          Icon(Icons.Filled.Search, contentDescription = null, tint = CyanPrimary)
        },
        trailingIcon = {
          if (appsUiState.searchQuery.isNotEmpty()) {
            IconButton(
              onClick = { onSearchQueryChange("") },
              modifier = Modifier.testTag("clear_app_search_button"),
            ) {
              Icon(Icons.Filled.Clear, contentDescription = "Clear search")
            }
          }
        },
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = CyanPrimary,
          unfocusedBorderColor = ObsidianOutline,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("app_search_input"),
      )

      // Filter chips + bulk actions
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        AppListFilter.entries.forEach { filter ->
          val selected = appsUiState.filter == filter
          FilterChip(
            selected = selected,
            onClick = { onFilterChange(filter) },
            label = {
              val labelText = if (filter == AppListFilter.SELECTED_ONLY) {
                "${filter.label} (${config.selectedPackages.size})"
              } else {
                filter.label
              }
              Text(labelText)
            },
            colors = FilterChipDefaults.filterChipColors(
              selectedContainerColor = CyanPrimaryContainer,
              selectedLabelColor = CyanPrimary,
            ),
            modifier = Modifier.testTag("app_filter_${filter.name.lowercase()}"),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Showing ${filteredApps.size} apps",
          style = MaterialTheme.typography.bodySmall,
          color = SlateTextSecondary,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(
            onClick = onSelectVisible,
            enabled = filteredApps.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("select_visible_apps_button"),
          ) {
            Icon(
              imageVector = Icons.Filled.SelectAll,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.action_select_visible), style = MaterialTheme.typography.labelMedium)
          }
          OutlinedButton(
            onClick = onClearAll,
            enabled = config.selectedPackages.isNotEmpty(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            modifier = Modifier.testTag("clear_selected_apps_button"),
          ) {
            Icon(
              imageVector = Icons.Outlined.Deselect,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(stringResource(R.string.action_clear_all), style = MaterialTheme.typography.labelMedium)
          }
        }
      }

      if (appsUiState.isLoading) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            CircularProgressIndicator(color = CyanPrimary)
            Text(
              text = "Scanning installed applications…",
              style = MaterialTheme.typography.bodyMedium,
              color = SlateTextSecondary,
            )
          }
        }
      } else if (filteredApps.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
          contentAlignment = Alignment.Center,
        ) {
          Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.FilterList,
              contentDescription = null,
              tint = SlateTextMuted,
              modifier = Modifier.size(36.dp),
            )
            Text(
              text = "No apps match the active filter",
              style = MaterialTheme.typography.titleSmall,
              color = SlateTextSecondary,
            )
          }
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .testTag("installed_apps_list"),
          verticalArrangement = Arrangement.spacedBy(8.dp),
          contentPadding = PaddingValues(bottom = 16.dp),
        ) {
          items(
            items = filteredApps,
            key = { it.packageName },
          ) { app ->
            val isSelected = app.packageName in config.selectedPackages
            InstalledAppRow(
              app = app,
              isSelected = isSelected,
              routingMode = config.routingMode,
              onToggle = { onToggleApp(app.packageName) },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun RoutingModeSelectorCard(
  selectedMode: RoutingMode,
  selectedCount: Int,
  onSelectMode: (RoutingMode) -> Unit,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
    border = BorderStroke(1.dp, ObsidianOutline),
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(14.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = "Per-App Routing Mode",
          style = MaterialTheme.typography.titleMedium,
        )
        Surface(
          color = CyanPrimaryContainer,
          shape = RoundedCornerShape(999.dp),
          border = BorderStroke(1.dp, CyanPrimary.copy(alpha = 0.4f)),
        ) {
          Text(
            text = "$selectedCount Selected",
            style = MaterialTheme.typography.labelSmall,
            color = CyanPrimary,
            modifier = Modifier
              .padding(horizontal = 10.dp, vertical = 4.dp)
              .testTag("selected_apps_badge"),
          )
        }
      }

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        ModeOptionCard(
          title = stringResource(R.string.mode_proxied_title),
          description = stringResource(R.string.mode_proxied_desc),
          selected = selectedMode == RoutingMode.PROXIED_APPS,
          onClick = { onSelectMode(RoutingMode.PROXIED_APPS) },
          modifier = Modifier
            .weight(1f)
            .testTag("routing_mode_proxied"),
        )
        ModeOptionCard(
          title = stringResource(R.string.mode_bypass_title),
          description = stringResource(R.string.mode_bypass_desc),
          selected = selectedMode == RoutingMode.BYPASS_SELECTED,
          onClick = { onSelectMode(RoutingMode.BYPASS_SELECTED) },
          modifier = Modifier
            .weight(1f)
            .testTag("routing_mode_bypass"),
        )
      }
    }
  }
}

@Composable
private fun ModeOptionCard(
  title: String,
  description: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = if (selected) CyanPrimaryContainer.copy(alpha = 0.7f) else ObsidianSurfaceVariant,
    shape = RoundedCornerShape(14.dp),
    border = BorderStroke(
      width = if (selected) 1.5.dp else 1.dp,
      color = if (selected) CyanPrimary else ObsidianOutline,
    ),
    modifier = modifier
      .clip(RoundedCornerShape(14.dp))
      .clickable(onClick = onClick),
  ) {
    Column(
      modifier = Modifier.padding(12.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = if (selected) Icons.Filled.CheckBox else Icons.Filled.Shield,
          contentDescription = null,
          tint = if (selected) CyanPrimary else SlateTextSecondary,
          modifier = Modifier.size(16.dp),
        )
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          color = if (selected) CyanPrimary else MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = SlateTextSecondary,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun InstalledAppRow(
  app: InstalledAppInfo,
  isSelected: Boolean,
  routingMode: RoutingMode,
  onToggle: () -> Unit,
) {
  val iconBitmap: ImageBitmap? = remember(app.packageName, app.icon) {
    drawableToBitmapOrNull(app.icon)
  }

  val actionHint = when {
    routingMode == RoutingMode.PROXIED_APPS && isSelected -> "VIA WORKER"
    routingMode == RoutingMode.BYPASS_SELECTED && isSelected -> "BYPASSED"
    else -> null
  }

  Surface(
    color = if (isSelected) ObsidianSurfaceElevated else ObsidianSurface,
    shape = RoundedCornerShape(16.dp),
    border = BorderStroke(
      width = 1.dp,
      color = if (isSelected) CyanPrimary.copy(alpha = 0.45f) else ObsidianOutline,
    ),
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(16.dp))
      .clickable(onClick = onToggle)
      .testTag("app_row_${app.packageName}"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (iconBitmap != null) {
        Image(
          bitmap = iconBitmap,
          contentDescription = app.appName,
          modifier = Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(10.dp)),
        )
      } else {
        Surface(
          color = ObsidianSurfaceVariant,
          shape = RoundedCornerShape(10.dp),
          modifier = Modifier.size(40.dp),
        ) {
          Box(contentAlignment = Alignment.Center) {
            Icon(
              imageVector = Icons.Filled.Android,
              contentDescription = app.appName,
              tint = CyanPrimary,
              modifier = Modifier.size(24.dp),
            )
          }
        }
      }

      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = app.appName,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (app.isSystemApp) {
            Surface(
              color = ObsidianSurfaceVariant,
              shape = RoundedCornerShape(6.dp),
            ) {
              Text(
                text = "SYS",
                style = MaterialTheme.typography.labelSmall,
                color = SlateTextMuted,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
              )
            }
          }
          if (actionHint != null) {
            Surface(
              color = if (routingMode == RoutingMode.PROXIED_APPS) EmeraldContainer else CyanPrimaryContainer,
              shape = RoundedCornerShape(6.dp),
            ) {
              Text(
                text = actionHint,
                style = MaterialTheme.typography.labelSmall,
                color = if (routingMode == RoutingMode.PROXIED_APPS) EmeraldSecondary else CyanPrimary,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
              )
            }
          }
        }
        Text(
          text = app.packageName,
          style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
          color = SlateTextSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Checkbox(
        checked = isSelected,
        onCheckedChange = { onToggle() },
        colors = CheckboxDefaults.colors(
          checkedColor = CyanPrimary,
          uncheckedColor = SlateTextSecondary,
        ),
      )
    }
  }
}

private fun drawableToBitmapOrNull(drawable: Drawable?): ImageBitmap? {
  if (drawable == null) return null
  return runCatching {
    drawable.toBitmap(width = 96, height = 96).asImageBitmap()
  }.getOrNull()
}
