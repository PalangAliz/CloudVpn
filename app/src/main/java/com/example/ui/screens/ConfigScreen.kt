package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.config.ConfigValidator
import com.example.model.ProxyConfig
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonError
import com.example.ui.theme.CyanPrimary
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldSecondary
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.ObsidianOutline
import com.example.ui.theme.ObsidianSurface
import com.example.ui.theme.ObsidianSurfaceVariant
import com.example.ui.theme.SlateTextSecondary
import com.example.vpn.BatteryOptimizationHelper

@Composable
fun ConfigScreen(
  config: ProxyConfig,
  isIgnoringBatteryOpt: Boolean,
  hasNotificationPermission: Boolean,
  onEnableLocalProxyChange: (Boolean) -> Unit,
  onLocalProxyPortChange: (Int) -> Unit,
  onExportJson: () -> String,
  onExportShareUri: () -> String,
  onImportRaw: (String) -> Result<ProxyConfig>,
  onRequestNotificationPermission: () -> Unit,
  onNotify: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val clipboardManager = LocalClipboardManager.current
  val scrollState = rememberScrollState()

  var showImportDialog by remember { mutableStateOf(false) }
  var importText by remember { mutableStateOf("") }
  var importError by remember { mutableStateOf<String?>(null) }

  var portText by remember(config.localProxyPort) {
    mutableStateOf(config.localProxyPort.toString())
  }
  val parsedPort = portText.toIntOrNull()
  val isPortValid = parsedPort != null && ConfigValidator.isValidPort(parsedPort)

  val exportFileLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.CreateDocument("application/json"),
  ) { uri ->
    if (uri != null) {
      runCatching {
        context.contentResolver.openOutputStream(uri)?.use { out ->
          out.write(onExportJson().toByteArray(Charsets.UTF_8))
        }
        onNotify("Exported config JSON file")
      }.onFailure {
        onNotify("Export failed: ${it.message}")
      }
    }
  }

  val importFileLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.OpenDocument(),
  ) { uri ->
    if (uri != null) {
      runCatching {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
          .orEmpty()
        onImportRaw(text).onFailure { err ->
          onNotify("Import failed: ${err.message}")
        }
      }.onFailure {
        onNotify("Failed to read file: ${it.message}")
      }
    }
  }

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
      // Export / Import Card
      Card(
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            text = stringResource(R.string.section_export_import),
            style = MaterialTheme.typography.titleLarge,
          )
          Text(
            text = "Export or import Worker URL, UUID, Clean IP, and Per-App routing policy as JSON or an edgetunnel:// URI.",
            style = MaterialTheme.typography.bodySmall,
            color = SlateTextSecondary,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedButton(
              onClick = {
                clipboardManager.setText(AnnotatedString(onExportJson()))
                onNotify("Copied JSON configuration")
              },
              modifier = Modifier
                .weight(1f)
                .testTag("copy_json_button"),
            ) {
              Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Copy JSON")
            }
            OutlinedButton(
              onClick = {
                clipboardManager.setText(AnnotatedString(onExportShareUri()))
                onNotify("Copied edgetunnel:// URI")
              },
              modifier = Modifier
                .weight(1f)
                .testTag("copy_uri_button"),
            ) {
              Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Copy URI")
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            OutlinedButton(
              onClick = { exportFileLauncher.launch("edgetunnel-config.json") },
              modifier = Modifier
                .weight(1f)
                .testTag("export_file_button"),
            ) {
              Icon(Icons.Filled.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Export .json")
            }
            OutlinedButton(
              onClick = { importFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
              modifier = Modifier
                .weight(1f)
                .testTag("import_file_button"),
            ) {
              Icon(Icons.Filled.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Import .json")
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            Button(
              onClick = {
                importText = clipboardManager.getText()?.text.orEmpty()
                importError = null
                showImportDialog = true
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = CyanPrimary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
              ),
              modifier = Modifier
                .weight(1f)
                .testTag("open_import_dialog_button"),
            ) {
              Text("Paste & Import")
            }
            OutlinedButton(
              onClick = {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_TEXT, onExportShareUri())
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share EdgeTunnel Config"))
              },
              modifier = Modifier.weight(1f),
            ) {
              Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Share URI")
            }
          }
        }
      }

      // Local Proxy & Protocol Card
      Card(
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            Icon(Icons.Filled.Tune, contentDescription = null, tint = CyanPrimary)
            Text(
              text = stringResource(R.string.section_engine_settings),
              style = MaterialTheme.typography.titleLarge,
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
              Text(
                text = "Local SOCKS5 / HTTP Bridge",
                style = MaterialTheme.typography.titleSmall,
              )
              Text(
                text = "Exposes 127.0.0.1:${config.localProxyPort} & attaches VPN HTTP proxy (API 29+)",
                style = MaterialTheme.typography.bodySmall,
                color = SlateTextSecondary,
              )
            }
            Switch(
              checked = config.enableLocalProxy,
              onCheckedChange = onEnableLocalProxyChange,
              colors = SwitchDefaults.colors(checkedThumbColor = CyanPrimary),
              modifier = Modifier.testTag("enable_local_proxy_switch"),
            )
          }

          OutlinedTextField(
            value = portText,
            onValueChange = { text ->
              portText = text.filter { it.isDigit() }.take(5)
              val p = portText.toIntOrNull()
              if (p != null && ConfigValidator.isValidPort(p)) {
                onLocalProxyPortChange(p)
              }
            },
            label = { Text("Local Proxy Port (1024–65535)") },
            enabled = config.enableLocalProxy,
            isError = portText.isNotEmpty() && !isPortValid,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = CyanPrimary,
              unfocusedBorderColor = ObsidianOutline,
            ),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("local_proxy_port_input"),
          )

          HorizontalDivider(color = ObsidianOutline)

          Surface(
            color = ObsidianSurfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
          ) {
            Column(
              modifier = Modifier.padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              Text(
                text = "Wire Protocol Specification",
                style = MaterialTheme.typography.labelMedium,
                color = CyanPrimary,
              )
              Text(
                text = "Subprotocol: chat.v2 • Key: tb[i%len] ^ nonce[i] (16B)\n" +
                  "Init: nonce(16) || xor(cmd|port|at|addr|payload, key) || trailer(8)\n" +
                  "ACK: xor(HMAC-SHA256(key, \"OK\")[0..15], key)",
                style = MaterialTheme.typography.labelSmall,
                color = SlateTextSecondary,
              )
            }
          }
        }
      }

      // Battery Optimization & Background Persistence Card
      Card(
        colors = CardDefaults.cardColors(containerColor = ObsidianSurface),
        border = BorderStroke(1.dp, ObsidianOutline),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          Text(
            text = stringResource(R.string.section_battery_background),
            style = MaterialTheme.typography.titleLarge,
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.BatteryChargingFull,
                contentDescription = null,
                tint = if (isIgnoringBatteryOpt) EmeraldSecondary else AmberWarning,
              )
              Column {
                Text("Battery Optimization", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = if (isIgnoringBatteryOpt) {
                    "Unrestricted (Recommended for persistent VPN)"
                  } else {
                    "Optimized — Doze mode may pause background sockets"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = SlateTextSecondary,
                )
              }
            }
            Surface(
              color = if (isIgnoringBatteryOpt) EmeraldContainer else AmberContainer,
              shape = RoundedCornerShape(999.dp),
            ) {
              Text(
                text = if (isIgnoringBatteryOpt) "UNRESTRICTED" else "OPTIMIZED",
                style = MaterialTheme.typography.labelSmall,
                color = if (isIgnoringBatteryOpt) EmeraldSecondary else AmberWarning,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              )
            }
          }

          if (!isIgnoringBatteryOpt) {
            OutlinedButton(
              onClick = {
                runCatching {
                  context.startActivity(BatteryOptimizationHelper.createRequestIgnoreBatteryIntent(context))
                }.onFailure {
                  onNotify("Open system battery settings manually for EdgeTunnel")
                }
              },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("request_battery_exemption_button"),
            ) {
              Text("Allow Unrestricted Background Usage")
            }
          }

          HorizontalDivider(color = ObsidianOutline)

          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
          ) {
            Row(
              modifier = Modifier.weight(1f),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
              Icon(
                imageVector = if (hasNotificationPermission) {
                  Icons.Filled.CheckCircle
                } else {
                  Icons.Filled.NotificationsActive
                },
                contentDescription = null,
                tint = if (hasNotificationPermission) EmeraldSecondary else AmberWarning,
              )
              Column {
                Text("Foreground Status Notification", style = MaterialTheme.typography.titleSmall)
                Text(
                  text = if (hasNotificationPermission) {
                    "Granted — quick disconnect notification enabled"
                  } else {
                    "Recommended for Android 13+ VPN status indicator"
                  },
                  style = MaterialTheme.typography.bodySmall,
                  color = SlateTextSecondary,
                )
              }
            }
            if (!hasNotificationPermission) {
              TextButton(onClick = onRequestNotificationPermission) {
                Text("Enable")
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))
    }
  }

  if (showImportDialog) {
    AlertDialog(
      onDismissRequest = { showImportDialog = false },
      title = { Text("Import Configuration") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "Paste an EdgeTunnel JSON document or edgetunnel:// URI:",
            style = MaterialTheme.typography.bodySmall,
            color = SlateTextSecondary,
          )
          OutlinedTextField(
            value = importText,
            onValueChange = {
              importText = it
              importError = null
            },
            minLines = 4,
            maxLines = 8,
            textStyle = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            isError = importError != null,
            modifier = Modifier
              .fillMaxWidth()
              .testTag("import_payload_input"),
          )
          if (importError != null) {
            Text(
              text = importError.orEmpty(),
              style = MaterialTheme.typography.bodySmall,
              color = CrimsonError,
            )
          }
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onImportRaw(importText)
              .onSuccess { showImportDialog = false }
              .onFailure { importError = it.message ?: "Invalid configuration" }
          },
          modifier = Modifier.testTag("confirm_import_button"),
        ) {
          Text("Import")
        }
      },
      dismissButton = {
        TextButton(onClick = { showImportDialog = false }) {
          Text("Cancel")
        }
      },
    )
  }
}
