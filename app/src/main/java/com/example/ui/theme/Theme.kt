package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val EdgeTunnelDarkColorScheme = darkColorScheme(
  primary = CyanPrimary,
  onPrimary = CyanOnPrimary,
  primaryContainer = CyanPrimaryContainer,
  onPrimaryContainer = CyanOnPrimaryContainer,
  secondary = EmeraldSecondary,
  onSecondary = CyanOnPrimary,
  secondaryContainer = EmeraldContainer,
  onSecondaryContainer = EmeraldOnContainer,
  tertiary = IndigoTertiary,
  onTertiary = SlateTextPrimary,
  tertiaryContainer = IndigoContainer,
  onTertiaryContainer = IndigoOnContainer,
  background = ObsidianBg,
  onBackground = SlateTextPrimary,
  surface = ObsidianSurface,
  onSurface = SlateTextPrimary,
  surfaceVariant = ObsidianSurfaceVariant,
  onSurfaceVariant = SlateTextSecondary,
  surfaceContainerLowest = ObsidianBg,
  surfaceContainerLow = ObsidianSurface,
  surfaceContainer = ObsidianSurfaceVariant,
  surfaceContainerHigh = ObsidianSurfaceElevated,
  surfaceContainerHighest = ObsidianOutline,
  outline = ObsidianOutline,
  outlineVariant = ObsidianOutlineSubtle,
  error = CrimsonError,
  onError = SlateTextPrimary,
  errorContainer = CrimsonContainer,
  onErrorContainer = CrimsonOnContainer,
)

private val EdgeTunnelShapes = Shapes(
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(16.dp),
  large = RoundedCornerShape(22.dp),
  extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun EdgeTunnelTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = EdgeTunnelDarkColorScheme,
    typography = EdgeTunnelTypography,
    shapes = EdgeTunnelShapes,
    content = content,
  )
}
