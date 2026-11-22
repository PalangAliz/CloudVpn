package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.config.ConfigValidator
import com.example.model.ConnectionStatus
import com.example.model.ProxyConfig
import com.example.model.RoutingMode
import com.example.model.TunnelLogEntry
import com.example.model.TunnelRuntimeState
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CrimsonError
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
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TunnelScreen(
  config: ProxyConfig,
  runtimeState: TunnelRuntimeState,
  onWorkerUrlChange: (String) -> Unit,
  onUuidChange: (String) -> Unit,
  onResetUuid: () -> Unit,
  onCleanIpChange: (String) -> Unit,
  onToggleConnect: () -> Unit,
  onNavigateToPerApp: () -> Unit,
  onClearLogs: () -> Unit,
  onCopyLogsFeedback: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val scrollState = rememberScrollState()
  val clipboardManager = LocalClipboardManager.current

  // Local text field buffers so typing never drops cursor while DataStore persists asynchronously
  var urlInput by remember(config.workerUrl) { mutableStateOf(config.workerUrl) }
  var uuidInput by remember(config.uuid) { mutableStateOf(config.uuid) }
  var cleanIpInput by remember(config.cleanIp) { mutableStateOf(config.cleanIp) }

  val parsedUrl = remember(urlInput) { ConfigValidator.normalizeWorkerUrl(urlInput) }
  val isUuidValid = remember(uuidInput) { ConfigValidator.isValidUuid(uuidInput) }
  val cleanIpParseResult = remember(cleanIpInput) { ConfigValidator.parseCleanIp(cleanIpInput) }

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.TopCenter,
  ) {
    Column(
      modifier = Modifier
        .widthIn(max = 680.dp)
        .fillMaxSize()
        .verticalScroll(scrollState)
        .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ConnectionHeroCard(
        runtimeState = runtimeState,
        parsedWorkerHost = parsedUrl?.host,
        cleanIpDisplay = cleanIpParseResult.getOrNull()?.displayString(),
        onToggleConnect = onToggleConnect,
      )

      RoutingSummaryBanner(
        config = config,
        onClick = onNavigateToPerApp,
      )

      EndpointConfigurationCard(
        urlInput = urlInput,
        parsedUrl = parsedUrl,
        onUrlInputChange = {
          urlInput = it
          onWorkerUrlChange(it)
        },
        uuidInput = uuidInput,
        isUuidValid = isUuidValid,
        onUuidInputChange = {
          uuidInput = it
          onUuidChange(it)
        },
        onResetUuid = onResetUuid,
        cleanIpInput = cleanIpInput,
        cleanIpError = cleanIpParseResult.exceptionOrNull()?.message,
        cleanIpParsed = cleanIpParseResult.getOrNull()?.displayString(),
        onCleanIpInputChange = {
          cleanIpInput = it
          onCleanIpChange(it)
        },
        isLockedWhileConnected = runtimeState.status == ConnectionStatus.CONNECTED ||
          runtimeState.status == ConnectionStatus.CONNECTING,
      )

      LiveLogConsoleCard(
        logs = runtimeState.logs,
        onClearLogs = onClearLogs,
        onCopyLogs = {
          val text = runtimeState.logs.joinToString("\n") { entry ->
            "${formatLogTime(entry.timestampMs)} [${entry.tag}] ${entry.message}"
          }
          clipboardManager.setText(AnnotatedString(text))
          onCopyLogsFeedback("Copied ${runtimeState.logs.size} log lines")
        },
      )

      Spacer(modifier = Modifier.height(12.dp))
    }
  }
}

@Composable
private fun ConnectionHeroCard(
  runtimeState: TunnelRuntimeState,
  parsedWorkerHost: String?,
  cleanIpDisplay: String?,
  onToggleConnect: () -> Unit,
) {
  val statusColor by animateColorAsState(
    targetValue = when (runtimeState.status) {
      ConnectionStatus.CONNECTED -> EmeraldSecondary
      ConnectionStatus.CONNECTING -> AmberWarning
      ConnectionStatus.ERROR -> CrimsonError
      ConnectionStatus.DISCONNECTED -> CyanPrimary
    },
    label = "statusColor",
  )

  val statusBadgeBg = when (runtimeState.status) {
    ConnectionStatus.CONNECTED -> EmeraldContainer
    ConnectionStatus.CONNECTING -> AmberContainer
    ConnectionStatus.ERROR -> CrimsonContainer
    ConnectionStatus.DISCONNECTED -> ObsidianSurfaceElevated
  }

  val statusLabel = when (runtimeState.status) {
    ConnectionStatus.DISCONNECTED -> stringResource(R.string.status_disconnected)
    ConnectionStatus.CONNECTING -> stringResource(R.string.status_connecting)
    ConnectionStatus.CONNECTED -> stringResource(R.string.status_connected)
    ConnectionStatus.ERROR -> stringResource(R.string.status_error)
  }

  var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(runtimeState.status, runtimeState.connectedSinceMs) {
    while (runtimeState.status == ConnectionStatus.CONNECTED && runtimeState.connectedSinceMs != null) {
      nowMs = System.currentTimeMillis()
      delay(1_000L)
    }
  }

  val infiniteTransition = rememberInfiniteTransition(label = "ringSpin")
  val sweepAngle by infiniteTransition.animateFloat(
    initialValue = 0f,
    targetValue = 360f,
    animationSpec = infiniteRepeatable(
      animation = tween(durationMillis = 2400, easing = LinearEasing),
      repeatMode = RepeatMode.Restart,
    ),
    label = "sweepAngle",
  )

  Card(
    colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
    shape = RoundedCornerShape(24.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .background(
          Brush.radialGradient(
            colors = listOf(
              statusColor.copy(alpha = 0.14f),
              Color.Transparent,
            ),
            center = Offset(180f, 120f),
            radius = 540f,
          )
        )
        .padding(20.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.weight(1f),
          ) {
            Box(
              modifier = Modifier.size(60.dp),
              contentAlignment = Alignment.Center,
            ) {
              Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                  color = statusColor.copy(alpha = 0.18f),
                  style = Stroke(width = 3.dp.toPx()),
                )
                if (runtimeState.status == ConnectionStatus.CONNECTING ||
                  runtimeState.status == ConnectionStatus.CONNECTED
                ) {
                  drawArc(
                    color = statusColor,
                    startAngle = sweepAngle,
                    sweepAngle = if (runtimeState.status == ConnectionStatus.CONNECTING) 110f else 220f,
                    useCenter = false,
                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round),
                  )
                }
              }
              Surface(
                shape = CircleShape,
                color = statusBadgeBg,
                modifier = Modifier.size(44.dp),
              ) {
                Box(contentAlignment = Alignment.Center) {
                  Icon(
                    imageVector = if (runtimeState.status == ConnectionStatus.ERROR) {
                      Icons.Filled.ErrorOutline
                    } else {
                      Icons.Filled.Shield
                    },
                    contentDescription = statusLabel,
                    tint = statusColor,
                    modifier = Modifier.size(22.dp),
                  )
                }
              }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Surface(
                  color = statusBadgeBg,
                  shape = RoundedCornerShape(999.dp),
                  border = BorderStroke(1.dp, statusColor.copy(alpha = 0.4f)),
                ) {
                  Text(
                    text = statusLabel.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                  )
                }
                if (runtimeState.status == ConnectionStatus.CONNECTED &&
                  runtimeState.connectedSinceMs != null
                ) {
                  Text(
                    text = formatDuration(nowMs - runtimeState.connectedSinceMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = SlateTextSecondary,
                  )
                }
              }

              Text(
                text = runtimeState.activeWorkerHost ?: parsedWorkerHost ?: "Worker not configured",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )

              val routeHint = buildString {
                append("chat.v2")
                val activeClean = runtimeState.activeCleanIp ?: cleanIpDisplay
                if (!activeClean.isNullOrBlank()) {
                  append(" • Clean IP $activeClean")
                } else {
                  append(" • Direct TLS/SNI")
                }
              }
              Text(
                text = routeHint,
                style = MaterialTheme.typography.labelSmall,
                color = SlateTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }

        AnimatedVisibility(visible = runtimeState.errorMessage != null) {
          Surface(
            color = CrimsonContainer,
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, CrimsonError.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text(
              text = runtimeState.errorMessage.orEmpty(),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onErrorContainer,
              modifier = Modifier.padding(12.dp),
            )
          }
        }

        // Telemetry row
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          TelemetryTile(
            label = "UPLOAD",
            value = formatBytes(runtimeState.bytesUploaded),
            modifier = Modifier.weight(1f),
          )
          TelemetryTile(
            label = "DOWNLOAD",
            value = formatBytes(runtimeState.bytesDownloaded),
            modifier = Modifier.weight(1f),
          )
          TelemetryTile(
            label = "STREAMS",
            value = "${runtimeState.activeSessions} / ${runtimeState.totalConnections}",
            modifier = Modifier.weight(1f),
          )
        }

        val isConnectedOrConnecting = runtimeState.status == ConnectionStatus.CONNECTED ||
          runtimeState.status == ConnectionStatus.CONNECTING

        Button(
          onClick = onToggleConnect,
          modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .testTag("connect_toggle_button"),
          shape = RoundedCornerShape(16.dp),
          colors = ButtonDefaults.buttonColors(
            containerColor = if (isConnectedOrConnecting) CrimsonError else CyanPrimary,
            contentColor = if (isConnectedOrConnecting) Color.White else MaterialTheme.colorScheme.onPrimary,
          ),
        ) {
          Icon(
            imageVector = Icons.Filled.PowerSettingsNew,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
          )
          Spacer(modifier = Modifier.width(8.dp))
          Text(
            text = if (isConnectedOrConnecting) {
              stringResource(R.string.action_disconnect)
            } else {
              stringResource(R.string.action_connect)
            },
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
    }
  }
}

@Composable
private fun TelemetryTile(
  label: String,
  value: String,
  modifier: Modifier = Modifier,
) {
  Surface(
    color = ObsidianSurfaceVariant,
    shape = RoundedCornerShape(14.dp),
    border = BorderStroke(1.dp, ObsidianOutline),
    modifier = modifier,
  ) {
    Column(
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
      verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = SlateTextMuted,
      )
      Text(
        text = value,
        style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun RoutingSummaryBanner(
  config: ProxyConfig,
  onClick: () -> Unit,
) {
  val modeTitle = when (config.routingMode) {
    RoutingMode.PROXIED_APPS -> stringResource(R.string.mode_proxied_title)
    RoutingMode.BYPASS_SELECTED -> stringResource(R.string.mode_bypass_title)
  }
  val subtitle = when (config.routingMode) {
    RoutingMode.PROXIED_APPS -> {
      if (config.selectedPackages.isEmpty()) {
        "No apps selected yet — tap to pick apps for Worker"
      } else {
        "${config.selectedPackages.size} selected apps routed via Worker"
      }
    }
    RoutingMode.BYPASS_SELECTED -> {
      if (config.selectedPackages.isEmpty()) {
        "All device apps routed via Worker (0 bypassed)"
      } else {
        "All apps via Worker except ${config.selectedPackages.size} bypassed"
      }
    }
  }

  Surface(
    color = ObsidianSurface,
    shape = RoundedCornerShape(18.dp),
    border = BorderStroke(1.dp, ObsidianOutline),
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(18.dp))
      .clickable(onClick = onClick)
      .testTag("routing_mode_summary_card"),
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 16.dp, vertical = 14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Surface(
        color = CyanPrimaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.size(40.dp),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Icon(
            imageVector = Icons.Filled.Apps,
            contentDescription = null,
            tint = CyanPrimary,
            modifier = Modifier.size(20.dp),
          )
        }
      }
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = "Per-App Policy: $modeTitle",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall,
          color = SlateTextSecondary,
        )
      }
      Icon(
        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = "Manage Per-App Proxy",
        tint = SlateTextSecondary,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

@Composable
private fun EndpointConfigurationCard(
  urlInput: String,
  parsedUrl: ConfigValidator.ParsedWorkerUrl?,
  onUrlInputChange: (String) -> Unit,
  uuidInput: String,
  isUuidValid: Boolean,
  onUuidInputChange: (String) -> Unit,
  onResetUuid: () -> Unit,
  cleanIpInput: String,
  cleanIpError: String?,
  cleanIpParsed: String?,
  onCleanIpInputChange: (String) -> Unit,
  isLockedWhileConnected: Boolean,
) {
  Card(
    colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
    border = BorderStroke(1.dp, ObsidianOutline),
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.section_worker_endpoint),
          style = MaterialTheme.typography.titleLarge,
        )
        if (isLockedWhileConnected) {
          Surface(
            color = ObsidianSurfaceElevated,
            shape = RoundedCornerShape(999.dp),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.Lock,
                contentDescription = null,
                tint = AmberWarning,
                modifier = Modifier.size(12.dp),
              )
              Text(
                text = "Reconnect to apply edits",
                style = MaterialTheme.typography.labelSmall,
                color = AmberWarning,
              )
            }
          }
        }
      }

      // Worker URL
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
          value = urlInput,
          onValueChange = onUrlInputChange,
          label = { Text(stringResource(R.string.label_worker_url)) },
          placeholder = { Text(stringResource(R.string.hint_worker_url)) },
          leadingIcon = {
            Icon(Icons.Filled.Language, contentDescription = null, tint = CyanPrimary)
          },
          trailingIcon = {
            if (parsedUrl != null) {
              Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "Valid Worker URL",
                tint = EmeraldSecondary,
              )
            }
          },
          isError = urlInput.isNotBlank() && parsedUrl == null,
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CyanPrimary,
            unfocusedBorderColor = ObsidianOutline,
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("worker_url_input"),
        )
        if (parsedUrl != null) {
          Text(
            text = "Normalized WebSocket: ${parsedUrl.normalizedWsUrl}",
            style = MaterialTheme.typography.labelSmall,
            color = EmeraldSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        } else if (urlInput.isNotBlank()) {
          Text(
            text = "Enter a valid Cloudflare Worker domain or https:///wss:// URL",
            style = MaterialTheme.typography.bodySmall,
            color = CrimsonError,
          )
        }
      }

      // UUID Token
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
          value = uuidInput,
          onValueChange = onUuidInputChange,
          label = { Text(stringResource(R.string.label_uuid)) },
          placeholder = { Text(stringResource(R.string.hint_uuid)) },
          leadingIcon = {
            Icon(Icons.Filled.Key, contentDescription = null, tint = CyanPrimary)
          },
          trailingIcon = {
            IconButton(
              onClick = onResetUuid,
              modifier = Modifier.testTag("reset_uuid_button"),
            ) {
              Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Reset UUID to Worker default",
                tint = SlateTextSecondary,
              )
            }
          },
          isError = uuidInput.isNotBlank() && !isUuidValid,
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CyanPrimary,
            unfocusedBorderColor = ObsidianOutline,
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("uuid_input"),
        )
        if (uuidInput.isNotBlank() && !isUuidValid) {
          Text(
            text = "UUID must match 8-4-4-4-12 hexadecimal format",
            style = MaterialTheme.typography.bodySmall,
            color = CrimsonError,
          )
        }
      }

      // Clean IP
      Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
          value = cleanIpInput,
          onValueChange = onCleanIpInputChange,
          label = { Text(stringResource(R.string.label_clean_ip)) },
          placeholder = { Text(stringResource(R.string.hint_clean_ip)) },
          leadingIcon = {
            Icon(Icons.Filled.Dns, contentDescription = null, tint = CyanPrimary)
          },
          trailingIcon = {
            if (cleanIpInput.isNotEmpty()) {
              IconButton(
                onClick = { onCleanIpInputChange("") },
                modifier = Modifier.testTag("clear_clean_ip_button"),
              ) {
                Icon(
                  imageVector = Icons.Filled.CleaningServices,
                  contentDescription = "Clear Clean IP",
                  tint = SlateTextSecondary,
                )
              }
            }
          },
          isError = cleanIpError != null,
          singleLine = true,
          textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CyanPrimary,
            unfocusedBorderColor = ObsidianOutline,
          ),
          modifier = Modifier
            .fillMaxWidth()
            .testTag("clean_ip_input"),
        )
        if (cleanIpError != null) {
          Text(
            text = cleanIpError,
            style = MaterialTheme.typography.bodySmall,
            color = CrimsonError,
          )
        } else if (cleanIpParsed != null) {
          Text(
            text = "Dialing $cleanIpParsed directly while preserving SNI & Host header",
            style = MaterialTheme.typography.labelSmall,
            color = CyanPrimary,
          )
        } else {
          Text(
            text = "Leave blank to resolve Worker DNS directly, or enter edge IP / IP:port",
            style = MaterialTheme.typography.bodySmall,
            color = SlateTextMuted,
          )
        }
      }
    }
  }
}

@Composable
private fun LiveLogConsoleCard(
  logs: List<TunnelLogEntry>,
  onClearLogs: () -> Unit,
  onCopyLogs: () -> Unit,
) {
  val listState = rememberLazyListState()
  LaunchedEffect(logs.size) {
    if (logs.isNotEmpty()) {
      listState.animateScrollToItem(logs.lastIndex)
    }
  }

  Card(
    colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
    border = BorderStroke(1.dp, ObsidianOutline),
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("logs_console"),
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(
          text = stringResource(R.string.section_live_logs),
          style = MaterialTheme.typography.titleLarge,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          TextButton(onClick = onCopyLogs, enabled = logs.isNotEmpty()) {
            Icon(
              imageVector = Icons.Filled.ContentCopy,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Copy")
          }
          TextButton(onClick = onClearLogs, enabled = logs.isNotEmpty()) {
            Icon(
              imageVector = Icons.Filled.DeleteSweep,
              contentDescription = null,
              modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Clear")
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))
      HorizontalDivider(color = ObsidianOutline)
      Spacer(modifier = Modifier.height(8.dp))

      if (logs.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(110.dp),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            text = "Handshake, Clean IP SNI, and tunnel events appear here.",
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
            color = SlateTextMuted,
          )
        }
      } else {
        LazyColumn(
          state = listState,
          modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 110.dp, max = 240.dp),
          contentPadding = PaddingValues(vertical = 4.dp),
          verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          items(logs) { entry ->
            val tagColor = when (entry.level) {
              TunnelLogEntry.LogLevel.SUCCESS -> EmeraldSecondary
              TunnelLogEntry.LogLevel.WARN -> AmberWarning
              TunnelLogEntry.LogLevel.ERROR -> CrimsonError
              TunnelLogEntry.LogLevel.INFO -> CyanPrimary
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Text(
                text = formatLogTime(entry.timestampMs),
                style = MaterialTheme.typography.labelSmall,
                color = SlateTextMuted,
              )
              Text(
                text = "[${entry.tag}]",
                style = MaterialTheme.typography.labelSmall,
                color = tagColor,
              )
              Text(
                text = entry.message,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
              )
            }
          }
        }
      }
    }
  }
}

private fun formatDuration(durationMs: Long): String {
  val totalSec = (durationMs / 1000L).coerceAtLeast(0L)
  val hours = totalSec / 3600
  val minutes = (totalSec % 3600) / 60
  val seconds = totalSec % 60
  return if (hours > 0) {
    String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.US, "%02d:%02d", minutes, seconds)
  }
}

private fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val kb = bytes / 1024.0
  if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
  val mb = kb / 1024.0
  if (mb < 1024) return String.format(Locale.US, "%.2f MB", mb)
  val gb = mb / 1024.0
  return String.format(Locale.US, "%.2f GB", gb)
}

private fun formatLogTime(timestampMs: Long): String {
  return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(timestampMs))
}
